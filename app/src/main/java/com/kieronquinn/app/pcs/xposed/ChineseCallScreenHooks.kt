package com.kieronquinn.app.pcs.xposed

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.AtomicFile
import com.kieronquinn.app.pcs.BuildConfig
import com.kieronquinn.app.pcs.callscreen.ChineseCallScreenAssets
import com.kieronquinn.app.pcs.callscreen.SodaLanguageAliases
import com.kieronquinn.app.pcs.model.phone.PhoneSettings
import com.kieronquinn.app.pcs.repositories.SettingsRepository.DobbyRegion
import com.kieronquinn.app.pcs.repositories.DeviceConfigPropertiesRepository.Companion.DEBUG_PROPERTY_NAME
import com.kieronquinn.app.pcs.utils.extensions.SystemProperties_getBoolean
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/** Only installed for the explicit Simplified Chinese Call Screen setting. */
object ChineseCallScreenHooks {
    private const val ASR_LOCALE = SodaLanguageAliases.MANDARIN
    private const val CALL_SCREEN_LOCALE = SodaLanguageAliases.SIMPLIFIED_CHINESE
    private const val RESOURCE_LOCALE = "en-US"
    private const val DUPLEX_RUNNER =
        "com/android/dialer/audioprism/internal/models/duplex/impl/AudioPrismDuplexModelRunner"
    private val recognitionMethods = setOf("startListening", "checkRecognitionSupport", "triggerModelDownload")
    private val offlineLanguageStates = listOf("SUPPORTED", "AVAILABLE", "PENDING")

    fun install(
        loadPackage: LoadPackageParam,
        application: Application,
        dexKit: DexKitBridge,
        settings: PhoneSettings
    ) {
        if (!settings.dobbyEnabled || settings.dobbyRegion != DobbyRegion.CN) return
        try {
            // Resolve all obfuscated targets before installing any of the language hooks.
            val runnerMethods = dexKit.findClass {
                matcher { usingStrings(DUPLEX_RUNNER) }
            }.flatMap { it.getInstance(loadPackage.classLoader).declaredMethods.toList() }
            val locale = hookLocaleProvider(loadPackage, dexKit)
            val prompt = hookPromptLanguage(loadPackage, dexKit)
            val resources = hookResources(application, runnerMethods)
            val availability = hookResourceLanguages(loadPackage, dexKit, runnerMethods)
            val androidSpeech = hookAndroidSpeechLanguages(loadPackage, dexKit)
            val grpc = hookGrpcLanguage(loadPackage, dexKit)
            repairLanguagePackCache(application)
            locale()
            prompt()
            resources()
            availability()
            androidSpeech()
            grpc()
            hookRecognitionLanguage()
            log("Simplified Chinese GACS hooks installed")
        } catch (error: Exception) {
            log("Unable to initialize Chinese GACS for this Dialer version: $error")
        }
    }

    private fun repairLanguagePackCache(application: Application) {
        val file = AtomicFile(File(application.filesDir, "PersistedLanguagePackInfo.pb"))
        if (!file.baseFile.exists()) return
        try {
            val original = file.readFully()
            val updated = SodaLanguageAliases.withChineseCachedAlias(original)
            if (updated.contentEquals(original)) return
            val output = file.startWrite()
            try {
                output.write(updated)
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
            log("SODA cached Mandarin state mapped to zh-CN before Phone startup")
        } catch (error: Exception) {
            log("SODA cached language migration deferred: $error")
        }
    }

    private fun hookLocaleProvider(loadPackage: LoadPackageParam, dexKit: DexKitBridge): () -> Unit {
        val picker = dexKit.findClass {
            matcher { usingStrings("/call_assistant_extraction-v6_2-en_us.zip") }
        }.singleOrNull()?.getInstance(loadPackage.classLoader)
            ?: error("Dobby model picker not found")
        // The locale provider's unused logger is stripped in newer Dialer versions. It remains
        // the first dependency of the model picker. Its lazy delegate is created internally,
        // unlike all the injected feature-flag Providers.
        val provider = picker.declaredConstructors.single { it.parameterCount == 11 }.parameterTypes[0]
        val injectedTypes = provider.declaredConstructors.flatMap { it.parameterTypes.toList() }.toSet()
        val lazyField = provider.declaredFields.singleOrNull { field ->
            val type = field.type
            !Modifier.isStatic(field.modifiers) && type.isInterface && type !in injectedTypes &&
                type.methods.any { it.parameterCount == 0 && it.returnType == Any::class.java }
        } ?: error("Dobby locale delegate not found")
        lazyField.isAccessible = true
        return {
            XposedBridge.hookAllConstructors(provider, object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val delegate = Proxy.newProxyInstance(lazyField.type.classLoader, arrayOf(lazyField.type)) { proxy, method, args ->
                        when {
                            method.name == "equals" -> proxy === args?.get(0)
                            method.name == "hashCode" -> System.identityHashCode(proxy)
                            method.returnType == Boolean::class.javaPrimitiveType -> true
                            else -> CALL_SCREEN_LOCALE
                        }
                    }
                    lazyField.set(param.thisObject, delegate)
                    debug("Dobby locale: zh-CN")
                }
            })
        }
    }

    private fun hookResourceLanguages(
        loadPackage: LoadPackageParam,
        dexKit: DexKitBridge,
        runnerMethods: List<Method>
    ): () -> Unit {
        val check = dexKit.findClass {
            matcher { usingStrings("com/android/dialer/audioprism/internal/models/duplex/impl/AudioPrismDuplexModelAvailability") }
        }.flatMap { it.getInstance(loadPackage.classLoader).declaredMethods.toList() }.singleOrNull {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 3 &&
                it.parameterTypes[1] == String::class.java && it.returnType == Any::class.java
        } ?: error("GACS resource availability method not found")
        val start = runnerMethods.singleOrNull { candidate ->
            !Modifier.isStatic(candidate.modifiers) && candidate.parameterCount == 6 &&
                candidate.parameterTypes[3] == String::class.java && candidate.returnType == Any::class.java &&
                candidate.declaringClass.interfaces.any { contract ->
                    contract.methods.any { method ->
                        method.name == candidate.name &&
                            method.parameterTypes.contentEquals(candidate.parameterTypes)
                    }
                }
        } ?: error("GACS resource runner not found")
        return {
            XposedBridge.hookMethod(check, object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[1] != CALL_SCREEN_LOCALE) return
                    // The downloaded GACS bundle remains Google's US bundle. Its recordings
                    // and intent text are localized by the overlay at load time.
                    param.args[1] = RESOURCE_LOCALE
                    debug("GACS resource check: en-US bundle for Chinese session")
                }
            })
            XposedBridge.hookMethod(start, object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[3] != CALL_SCREEN_LOCALE) return
                    // The runner independently selects the unzipped MDD group by locale.
                    // This argument only selects that group; the conversation context inside
                    // the model config and the separate SODA runner retain Chinese.
                    param.args[3] = RESOURCE_LOCALE
                    debug("GACS resource runner: en-US bundle with Chinese conversation context")
                }
            })
        }
    }

    private fun hookAndroidSpeechLanguages(
        loadPackage: LoadPackageParam,
        dexKit: DexKitBridge
    ): () -> Unit {
        val languageDetails = BroadcastReceiver::class.java.getDeclaredMethod(
            "getResultExtras", Boolean::class.javaPrimitiveType)
        val download = dexKit.findClass {
            matcher { usingStrings("com/android/dialer/sodatranscription/impl/androidspeech/SodaDownloadEnqueueContractImpl") }
        }.singleOrNull()?.getInstance(loadPackage.classLoader)?.declaredMethods?.singleOrNull {
            it.name == "createIntent" && it.returnType == Intent::class.java
        } ?: error("SODA download intent factory not found")
        return {
            XposedBridge.hookMethod(languageDetails, object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val extras = param.result as? Bundle ?: return
                    if (!extras.containsKey("com.google.recognition.extra.OFFLINE_SUPPORTED_LANGUAGES")) return
                    val translated = Bundle(extras)
                    // Dialer's LanguageCode formatter removes script subtags, so it cannot
                    // query cmn-Hans-CN directly. Alias the real service response instead.
                    // Each state stays in its own list; an absent pack stays unavailable.
                    for (state in offlineLanguageStates) {
                        val key = "com.google.recognition.extra.OFFLINE_${state}_LANGUAGES"
                        val bytes = extras.getByteArray(key) ?: continue
                        val aliased = SodaLanguageAliases.withChineseAlias(bytes)
                        translated.putByteArray(key, aliased)
                        if (!bytes.contentEquals(aliased)) debug("SODA $state: $ASR_LOCALE aliased as zh-CN")
                    }
                    param.result = translated
                }
            })
            XposedBridge.hookMethod(download, object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val intent = param.result as? Intent ?: return
                    if (intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) != CALL_SCREEN_LOCALE) return
                    param.result = Intent(intent).putExtra(RecognizerIntent.EXTRA_LANGUAGE, ASR_LOCALE)
                    debug("SODA download: $ASR_LOCALE")
                }
            })
        }
    }

    private fun hookGrpcLanguage(loadPackage: LoadPackageParam, dexKit: DexKitBridge): () -> Unit {
        val packs = dexKit.findClass {
            matcher { usingStrings("com/android/dialer/sodatranscription/impl/ondevicegrpc/SodaOnDeviceGrpcModelAvailability") }
        }.flatMap { it.getInstance(loadPackage.classLoader).declaredMethods.toList() }.singleOrNull {
            Modifier.isStatic(it.modifiers) && it.returnType == Map::class.java &&
                it.parameterCount == 2 && it.parameterTypes[0] == List::class.java &&
                it.parameterTypes[1].isEnum
        } ?: error("SODA gRPC language pack mapper not found")
        val session = dexKit.findClass {
            matcher { usingStrings("com/android/dialer/sodatranscription/impl/ondevicegrpc/SodaOnDeviceGrpcSession", "sodaConfig") }
        }.singleOrNull()?.getInstance(loadPackage.classLoader)
            ?: error("SODA gRPC session not found")
        val locales = session.declaredFields.single {
            Modifier.isStatic(it.modifiers) && it.type == Map::class.java
        }.apply { isAccessible = true }
        val original = locales.get(null) as Map<*, *>
        require(original is java.util.HashMap<*, *>) { "Unexpected immutable SODA locale map" }
        val languageType = original.values.first()!!.javaClass
        // LanguageCode's public factories canonicalize cmn to zh. The constructor's
        // preserve-alias mode keeps the exact model tag required by Speech Services.
        val constructor = languageType.getDeclaredConstructor(String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val mandarin = constructor.newInstance(ASR_LOCALE, 2, 2)
        require(mandarin.toString().equals(ASR_LOCALE, ignoreCase = true)) {
            "SODA gRPC did not preserve the Mandarin model tag: $mandarin"
        }
        return {
            XposedBridge.hookMethod(packs, object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val result = param.result as? Map<*, *> ?: return
                    val model = result[ASR_LOCALE] ?: return
                    if (result.containsKey(CALL_SCREEN_LOCALE)) return
                    // The cached model must carry the alias too. Returning cmn-Hans-CN in
                    // a zh-CN entry otherwise causes Dobby to refresh its settings endlessly.
                    val type = model.javaClass
                    val serialize = type.methods.single {
                        it.parameterCount == 0 && it.returnType == ByteArray::class.java
                    }
                    val parse = type.superclass.declaredMethods.single {
                        Modifier.isStatic(it.modifiers) && it.parameterCount == 2 &&
                            it.parameterTypes[0] == type.superclass &&
                            it.parameterTypes[1] == ByteArray::class.java
                    }
                    val bytes = SodaLanguageAliases.withChineseInfoLocale(serialize.invoke(model) as ByteArray)
                    val alias = parse.invoke(null, model, bytes)
                    param.result = LinkedHashMap(result).apply { put(CALL_SCREEN_LOCALE, alias) }
                    debug("SODA gRPC ${(param.args[1] as Enum<*>).name}: $ASR_LOCALE aliased as zh-CN")
                }
            })
            // Otherwise the native whitelist silently maps an unknown locale to English.
            @Suppress("UNCHECKED_CAST")
            (original as MutableMap<Any?, Any?>)[CALL_SCREEN_LOCALE] = mandarin
            log("SODA gRPC Chinese recognition: $ASR_LOCALE")
        }
    }

    private fun hookRecognitionLanguage() {
        val hooked = ConcurrentHashMap.newKeySet<Method>()
        fun hookType(type: Class<*>) {
            type.methods.filter { method ->
                method.name in recognitionMethods &&
                    method.parameterTypes.firstOrNull() == Intent::class.java &&
                    !Modifier.isAbstract(method.modifiers)
            }.filter { hooked.add(it) }.forEach { method ->
                XposedBridge.hookMethod(method, object: XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val request = param.args[0] as? Intent ?: return
                        if (request.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) != CALL_SCREEN_LOCALE) return
                        param.args[0] = Intent(request).putExtra(RecognizerIntent.EXTRA_LANGUAGE, ASR_LOCALE)
                        debug("${method.name}: $ASR_LOCALE")
                    }
                })
            }
        }
        // New Android releases return subclasses with their own overrides.
        hookType(SpeechRecognizer::class.java)
        SpeechRecognizer::class.java.declaredMethods.filter {
            it.name in setOf("createSpeechRecognizer", "createOnDeviceSpeechRecognizer")
        }.forEach { factory ->
            XposedBridge.hookMethod(factory, object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.result as? SpeechRecognizer)?.let { hookType(it.javaClass) }
                }
            })
        }
    }

    private fun hookPromptLanguage(loadPackage: LoadPackageParam, dexKit: DexKitBridge): () -> Unit {
        val processor = dexKit.findClass {
            matcher { usingStrings("com/google/quality/views/extraction/kcube/bg/ondevice/modules/callassistant/agenticcallscreen/AgenticCallScreenLlmInferenceTriggerProcessor") }
        }.singleOrNull()?.getInstance(loadPackage.classLoader)
            ?: error("GACS inference trigger not found")
        val initialize = processor.declaredMethods.singleOrNull {
            it.parameterCount == 6 && it.returnType == Void.TYPE
        } ?: error("GACS inference initialization not found")
        val fields = processor.declaredFields.filter {
            it.type == String::class.java && !Modifier.isStatic(it.modifiers)
        }.onEach { it.isAccessible = true }
        require(fields.size == 3) { "Unexpected GACS language parameters" }
        return {
            XposedBridge.hookMethod(initialize, object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val values = fields.associateWith { it.get(param.thisObject) as String }
                    val language = values.values.groupingBy { it }.eachCount().entries
                        .singleOrNull { it.value == 2 }?.key ?: return
                    fields.forEach {
                        it.set(param.thisObject, if (values[it] == language) "Chinese" else "China")
                    }
                    debug("GACS language parameters: Chinese / Chinese / China")
                }
            })
        }
    }

    private fun hookResources(
        application: Application,
        runnerMethods: List<Method>
    ): () -> Unit {
        val module = application.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
        val cache = File(application.filesDir, "pcs-call-screen/$CALL_SCREEN_LOCALE").canonicalFile
        val assets = ChineseCallScreenAssets(cache) { module.assets.open(it) }
        val bootstraps = runnerMethods.filter {
            !Modifier.isStatic(it.modifiers) && it.parameterCount in 4..5 &&
                it.parameterTypes[1] == String::class.java && it.parameterTypes[2].isEnum &&
                it.returnType == Any::class.java
        }
        require(bootstraps.isNotEmpty() && bootstraps.map { it.declaringClass }.distinct().size == 1) {
            "GACS conversation resource bootstrap not found"
        }
        return {
            // The playback event builder snapshots the model root before specs are opened.
            // Redirect that root first so its absolute WAV paths use the same overlay as text.
            bootstraps.forEach { bootstrap ->
                XposedBridge.hookMethod(bootstrap, object: XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val root = param.args[1] as? String ?: return
                        try {
                            val original = File(root).canonicalFile
                            if (original.toPath().startsWith(cache.toPath())) return
                            val specs = File(original, ChineseCallScreenAssets.SPEC_FILE)
                            if (!specs.isFile) return
                            val overlay = assets.overlay(original, specs.readBytes())
                            param.args[1] = overlay.absolutePath
                            debug("GACS text and audio resource root: ${overlay.name}")
                        } catch (error: Exception) {
                            log("Cannot prepare Chinese GACS conversation: $error")
                            param.throwable = java.io.IOException("Chinese GACS conversation resources unavailable", error)
                        }
                    }
                })
            }
            Thread({
                val downloads = File(application.filesDir, "dobby-duplex-files")
                downloads.listFiles()?.filter { File(it, ChineseCallScreenAssets.SPEC_FILE).isFile }
                    ?.forEach { directory ->
                        try {
                            val bytes = File(directory, ChineseCallScreenAssets.SPEC_FILE).readBytes()
                            val prepared = assets.overlay(directory, bytes)
                            log("Chinese recordings ready: ${prepared.name}")
                        } catch (error: Exception) {
                            log("Chinese recording preparation deferred: $error")
                        }
                    }
            }, "pcs-chinese-recordings").start()
        }
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG || SystemProperties_getBoolean(DEBUG_PROPERTY_NAME, false)) log(message)
    }

    private fun log(message: String) = XposedBridge.log("ChineseCallScreen: $message")
}

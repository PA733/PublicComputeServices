package com.kieronquinn.app.pcs.xposed

import android.app.Application
import android.util.Base64
import com.kieronquinn.app.pcs.model.PcsManifestList
import com.kieronquinn.app.pcs.model.phone.PhoneFlag
import com.kieronquinn.app.pcs.model.phone.PhoneSettings
import com.kieronquinn.app.pcs.providers.PhoneSettingsProvider
import com.kieronquinn.app.pcs.repositories.AstreaRepository.Companion.PORT_PHONE
import com.kieronquinn.app.pcs.repositories.DeviceConfigPropertiesRepository.Companion.PHONE_ENABLED
import com.kieronquinn.app.pcs.repositories.SettingsRepository.BeeslyRegion
import com.kieronquinn.app.pcs.repositories.SettingsRepository.DobbyRegion
import com.kieronquinn.app.pcs.repositories.SettingsRepository.PatrickPhase
import com.kieronquinn.app.pcs.utils.extensions.SystemProperties_get
import com.kieronquinn.app.pcs.utils.extensions.SystemProperties_getBoolean
import com.kieronquinn.app.pcs.utils.extensions.loadDexKit
import com.kieronquinn.app.pcs.utils.extensions.reflectParseProto
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

object PhoneHooks: GrpcHooks() {

    /**
     *  Selects the Call Screen model Dialer uses. Unset, `0` or `false` forces the older duplex
     *  model that carries its own screening audio, `force` picks the agentic GACS model backed by
     *  AICore even when Dialer's own experiment flags are off, and any other value leaves Dialer's
     *  flags in charge.
     */
    private const val CALL_SCREEN_AGENTIC = "persist.pcs.call_screen_agentic"

    override val tag = "PhoneHooks"
    override val applicationClassName = "com.android.dialer.Dialer_Application"
    override val activityClassName =
        "com.android.dialer.multibindingsettings.impl.DialerSettingsActivity"
    override val port = PORT_PHONE

    private val flagHolders = ConcurrentHashMap<PhoneFlag, MutableSet<Any>>()
    private val seenFlags = ConcurrentHashMap.newKeySet<String>()
    private val missingFlags = ConcurrentHashMap.newKeySet<PhoneFlag>()
    private val appliedFlags = ConcurrentHashMap.newKeySet<PhoneFlag>()
    private val typeMismatches = ConcurrentHashMap.newKeySet<PhoneFlag>()
    private val lateAssociations = ConcurrentHashMap.newKeySet<PhoneFlag>()
    private val unassociatedReads = ConcurrentHashMap.newKeySet<String>()
    private val pendingFlag = ThreadLocal<PhoneFlag?>()

    override fun isEnabled(): Boolean {
        return SystemProperties_getBoolean(PHONE_ENABLED, false)
    }

    override fun LoadPackageParam.onBeforeApplicationOnCreate(application: Application) {
        val dexKit = loadDexKit(appInfo.sourceDir)
        val settings = PhoneSettingsProvider.getSettings(application) ?: run {
            log("Unable to get phone settings")
            return
        }
        logDobbyData(settings)
        hookFlagDataStore(dexKit, settings)
        hookCallScreen(dexKit, settings)
        hookDobbyModel(dexKit, settings)
        hookDobbySettings(dexKit, settings)
        if (settings.patrickPhase > PatrickPhase.DISABLED) {
            hookPatrick(dexKit)
        }
        if (settings.fermatEnabled && settings.callRecordingEnabled) {
            hookCallRecording(dexKit)
        }
    }

    private fun LoadPackageParam.hookFlagDataStore(dexKit: DexKitBridge, settings: PhoneSettings) {
        val flagValueHolder = dexKit.findClass {
            matcher {
                usingStrings("null cannot be cast to non-null type T of com.google.apps.tiktok.experiments.FlagValueHolder.getProtoValue")
            }
        }.singleOrNull()
        val flagValueHolderClass = flagValueHolder?.getInstance(classLoader) ?: run {
            log("Unable to find FlagValueHolder")
            return
        }
        val flagValueHolderProtoMethod = flagValueHolder.findMethod {
            matcher {
                usingStrings("null cannot be cast to non-null type T of com.google.apps.tiktok.experiments.FlagValueHolder.getProtoValue")
            }
        }.singleOrNull()?.getMethodInstance(classLoader) ?: run {
            log("Unable to find FlagValueHolder proto method")
            return
        }
        val flagDataStores = dexKit.findMethod {
            matcher {
                usingStrings("mendelPackage", "Unknown package ")
            }
        }.mapNotNull { method ->
            try {
                method.declaredClass?.getInstance(classLoader)
            }catch (e: Throwable) {
                null
            }
        }.distinct()
        if (flagDataStores.isEmpty()) {
            log("Unable to find Flag DataStore")
            return
        }
        flagDataStores.forEach { hookFlagCreator(it, flagValueHolderClass) }
        hookFlagValueHolder(flagValueHolderClass, flagValueHolderProtoMethod, settings)
    }

    private fun LoadPackageParam.hookFlagCreator(
        creator: Class<*>,
        flagValueHolder: Class<*>
    ) {
        val creatorMethods = creator.declaredMethods.filter {
            it.returnType == flagValueHolder
        }
        if (creatorMethods.isEmpty()) {
            log("Unable to find creator method for flags")
            return
        }
        creatorMethods.forEach { creatorMethod ->
            log("Creator ${creator.name} method: ${creatorMethod.declaringClass.name}.${creatorMethod.name}(${creatorMethod.parameterTypes.joinToString(", ") { it.name }})")
            XposedBridge.hookMethod(creatorMethod, object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    pendingFlag.set(
                        resolveFlag(param.args.getOrNull(0), param.args.getOrNull(1))
                    )
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val flagPackage = param.args.getOrNull(0) as? String ?: return
                    val flagName = param.args.getOrNull(1) as? String ?: return
                    if (seenFlags.add("$flagPackage/$flagName")) {
                        log("Flag lookup: $flagPackage -> $flagName")
                    }
                    pendingFlag.get()?.let {
                        log("Overriding ${it.name} ($flagPackage/$flagName) -> ${flagHolders[it]?.size ?: 0} holder(s)")
                        if (param.result != null) {
                            it.getHolders().add(param.result)
                        }
                    }
                    pendingFlag.remove()
                }
            })
        }
    }

    private fun resolveFlag(flagPackage: Any?, flagName: Any?): PhoneFlag? {
        if (flagPackage !is String || flagName !is String) return null
        return PhoneFlag.getOrNull(flagPackage, flagName)
    }

    private fun hookFlagValueHolder(
        flagValueHolder: Class<*>,
        protoMethod: Method,
        settings: PhoneSettings
    ) {
        val hookMethod: (Method) -> Unit = { method: Method ->
            XposedBridge.hookMethod(method, object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val flag = getFlagForHolder(param.thisObject, method) ?: run {
                        logUnassociatedRead(param.thisObject, method)
                        return
                    }
                    val override = try {
                        flag.getValueOrNull(param.result, settings)
                    }catch (e: Throwable) {
                        if (missingFlags.add(flag)) {
                            log("Failed to build override value for ${flag.name}: $e")
                        }
                        null
                    }
                    if (override == null) {
                        if (missingFlags.add(flag)) {
                            log("No override value for ${flag.name}: ${flag.describeMissingValue(settings)}")
                        }
                        return
                    }
                    if (!method.returnType.accepts(override)) {
                        if (typeMismatches.add(flag)) {
                            log("Cannot apply ${flag.name} to ${method.name}(): ${method.returnType.name}")
                        }
                        return
                    }
                    param.result = override
                    if (appliedFlags.add(flag)) {
                        log(
                            "Applied override value for ${flag.name} via ${method.name}(), " +
                                    "value: ${override.describeValue()}"
                        )
                    }
                }
            })
        }
        // Flag values are read through the holder's zero argument accessors, which have changed
        // between Dialer versions (and now include a primitive boolean), so hook all of them
        // rather than assuming a fixed set of return types
        (flagValueHolder.declaredMethods.filter {
            it.parameterCount == 0
                    && !Modifier.isStatic(it.modifiers)
                    && it.returnType != Void.TYPE
        } + protoMethod).distinct().forEach(hookMethod)
    }

    /**
     *  Maps a flag value holder back to its flag. Dialer sometimes reads the value of a holder
     *  while the creator that built it is still on the stack, before it has been recorded, so fall
     *  back to the flag the creator is currently resolving on this thread.
     */
    private fun getFlagForHolder(holder: Any, method: Method): PhoneFlag? {
        flagHolders.entries.firstOrNull { holder in it.value }?.let { return it.key }
        val pending = pendingFlag.get() ?: return null
        pending.getHolders().add(holder)
        if (lateAssociations.add(pending)) {
            log("Associating ${pending.name} with holder read from ${method.name}()")
        }
        return pending
    }

    private fun PhoneFlag.getHolders(): MutableSet<Any> {
        return flagHolders.getOrPut(this) { ConcurrentHashMap.newKeySet() }
    }

    private fun Any.describeValue(): String {
        return toString().take(200)
    }

    /**
     *  A value was read from a holder that no known flag was created for, so we cannot override
     *  it. Log the caller once per holder type/accessor so the missed path is identifiable
     */
    private fun logUnassociatedRead(holder: Any, method: Method) {
        val key = "${holder.javaClass.name}.${method.name}()"
        if (unassociatedReads.add(key)) {
            val caller = try {
                getCallingInformation()?.let { "${it.first}.${it.second}" }
            }catch (e: Throwable) {
                null
            }
            log("Unassociated read from $key, caller $caller")
        }
    }

    private fun Class<*>.accepts(value: Any): Boolean {
        if (isInstance(value)) return true
        return when (this) {
            java.lang.Boolean.TYPE -> value is Boolean
            java.lang.Long.TYPE -> value is Long
            java.lang.Integer.TYPE -> value is Int
            java.lang.Double.TYPE -> value is Double
            java.lang.Float.TYPE -> value is Float
            java.lang.Short.TYPE -> value is Short
            java.lang.Byte.TYPE -> value is Byte
            java.lang.Character.TYPE -> value is Char
            else -> false
        }
    }

    /**
     *  Whether Dialer should pick its own Call Screen model (agentic/GACS) rather than the
     *  packaged duplex model.
     */
    private fun callScreenAgenticMode(): CallScreenMode {
        val value = SystemProperties_get(CALL_SCREEN_AGENTIC) ?: return CallScreenMode.DUPLEX
        if (value.isBlank() || value == "0" || value.equals("false", ignoreCase = true)) {
            return CallScreenMode.DUPLEX
        }
        return if (value.equals("force", ignoreCase = true)) {
            CallScreenMode.FORCE_AGENTIC
        } else {
            CallScreenMode.DIALER_DEFAULT
        }
    }

    private enum class CallScreenMode {
        /** Force the older duplex model that carries its own screening audio. */
        DUPLEX,
        /** Leave Dialer's own experiment flags in charge of the model choice. */
        DIALER_DEFAULT,
        /** Force the agentic GACS model even if Dialer's flags are off. */
        FORCE_AGENTIC
    }

    /**
     *  Explains why no value could be built for a tracked flag, so the reason is visible in logs
     *  without having to dump the flag data itself
     */
    private fun PhoneFlag.describeMissingValue(settings: PhoneSettings): String {
        return when (this) {
            PhoneFlag.DOBBY_DUPLEX_FILES, PhoneFlag.DOBBY_MODELS,
            PhoneFlag.DOBBY_DOWNLOAD_PATH, PhoneFlag.DOBBY_ENABLED -> buildString {
                append("dobbyEnabled=${settings.dobbyEnabled}")
                append(", dobbyUrl=${settings.dobbyUrl != null}")
                append(", manifestSize=${settings.dobbyDuplexFiles?.length ?: 0}")
                append(", region=${settings.dobbyRegion.locale}")
                append(", entry=${settings.dobbyDuplexFiles?.getListManifestOrNull(settings.dobbyRegion.locale) != null}")
            }
            else -> "not enabled in settings"
        }
    }

    /**
     *  Call Screen is gated behind the CallScreenI18n (Tidepods) flag, which Dialer reads through
     *  a path the flag overrides do not reach, so enable the feature check itself
     */
    private fun LoadPackageParam.hookCallScreen(dexKit: DexKitBridge, settings: PhoneSettings) {
        if (!settings.dobbyEnabled) return
        val callScreenEnabledFn = dexKit.findClass {
            matcher {
                usingStrings("feature disabled by tidepods call screen flag")
            }
        }.singleOrNull()?.getInstance(classLoader) ?: run {
            log("Unable to find CallScreenEnabledFn")
            return
        }
        val isEnabledMethod = callScreenEnabledFn.declaredMethods.firstOrNull {
            it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE
        } ?: run {
            log("Unable to find CallScreenEnabledFn method")
            return
        }
        log("Enabling Call Screen via ${callScreenEnabledFn.name}.${isEnabledMethod.name}()")
        XposedBridge.hookMethod(isEnabledMethod, object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = true
            }
        })
    }

    /**
     *  Dialer picks the "agentic" Call Screen model (`call_assistant_extraction-v6_2-*`, driven by
     *  AICore's GACS feature 305) when `enableAgenticCallScreen` is set, or when
     *  `enableGacsManualScreening` is set in a region where manual screening is supported, and
     *  otherwise falls back to the earlier duplex model. Force the two flags the model picker is
     *  built with to the mode [callScreenAgenticMode] selects, so the model does not depend on
     *  experiment flags the module cannot reliably pin. The flags are exposed as providers and
     *  read through Dialer's obfuscated experiment layer, which the flag value overrides do not
     *  reach, so replace the providers at construction time instead.
     */
    private fun LoadPackageParam.hookDobbyModel(dexKit: DexKitBridge, settings: PhoneSettings) {
        if (!settings.dobbyEnabled) return
        val mode = callScreenAgenticMode()
        if (mode == CallScreenMode.DIALER_DEFAULT) {
            log("Leaving Dialer's Call Screen model choice alone (agentic mode requested)")
            return
        }
        val modelPicker = dexKit.findClass {
            matcher {
                usingStrings("/call_assistant_extraction-v6_2-en_us.zip")
            }
        }.singleOrNull()?.getInstance(classLoader) ?: run {
            log("Unable to find Dobby model picker")
            return
        }
        val constructor = modelPicker.declaredConstructors.firstOrNull {
            it.parameterCount == 11
        } ?: run {
            log("Unable to find Dobby model picker constructor")
            return
        }
        // (localeProvider, downloadPath, enableAgenticCallScreen, enableGacsManualScreening,
        //  isUserInUs, isUserInUk, isUserInJp, isUserInCa, isUserInIe, isUserInAu, isUserInIn)
        val flagType = constructor.parameterTypes.getOrNull(3)
        if (flagType == null || !flagType.isInterface ||
            constructor.parameterTypes.drop(2).distinct().size != 1) {
            log("Unexpected Dobby model picker signature in ${modelPicker.name}")
            return
        }
        val disabled = flagType.constantProvider(false)
        val enabled = flagType.constantProvider(true)
        val agentic = flagType.constantProvider(mode == CallScreenMode.FORCE_AGENTIC)
        // Dialer's own region flags are not reliable without the experiment overrides, so select
        // the model from the region PCS is configured for
        val regionIndex = when (settings.dobbyRegion) {
            DobbyRegion.US -> 4
            DobbyRegion.GB -> 5
            DobbyRegion.JP -> 6
            DobbyRegion.CA -> 7
            DobbyRegion.IE -> 8
            DobbyRegion.AU -> 9
            DobbyRegion.IN -> 10
        }
        XposedBridge.hookMethod(constructor, object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.args[2] = agentic
                param.args[3] = agentic
                for (index in 4..10) {
                    param.args[index] = if (index == regionIndex) enabled else disabled
                }
            }
        })
        log(
            "Forcing the ${if (mode == CallScreenMode.FORCE_AGENTIC) "agentic" else "non-agentic"} " +
                    "Call Screen model for ${settings.dobbyRegion} in ${modelPicker.name}"
        )
    }

    /**
     *  Dialer builds the "Problem downloading required resources" banner for the GACS flow as soon
     *  as the unconditional resources banner flag is set, regardless of whether that flow can
     *  actually run. Disable that flag, the stuck download banner and the GACS manual screening
     *  flag on the settings data source when the duplex model is forced, so a banner for a model
     *  that is not in use is never built.
     */
    private fun LoadPackageParam.hookDobbySettings(dexKit: DexKitBridge, settings: PhoneSettings) {
        if (!settings.dobbyEnabled) return
        if (callScreenAgenticMode() != CallScreenMode.DUPLEX) {
            log("Leaving Dialer's GACS banner flags alone (agentic mode requested)")
            return
        }
        val dataSource = dexKit.findClass {
            matcher {
                usingStrings("gacsMode: [%s], aiCoreModelAvailabilityStatus: [%s], isAstreaUpToDate: %b")
            }
        }.singleOrNull()?.getInstance(classLoader) ?: run {
            log("Unable to find Dobby settings data source")
            return
        }
        val constructor = dataSource.declaredConstructors.firstOrNull {
            it.parameterCount == 21 && it.parameterTypes.drop(14).distinct().size == 1
        } ?: run {
            log("Unable to find Dobby settings data source constructor")
            return
        }
        // (..., enableLlmSmartReplyConsentFlow, enableUnconditionalResourcesBanner,
        //  enableFixForStuckDownloadingBanner, enableDobbyGemini, allowTtsFallback,
        //  enableGacsManualScreening, enableAgenticCallScreen)
        val flagType = constructor.parameterTypes[15]
        if (!flagType.isInterface) {
            log("Unexpected Dobby settings data source signature in ${dataSource.name}")
            return
        }
        val disabled = flagType.constantProvider(false)
        XposedBridge.hookMethod(constructor, object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                log("Applying GACS banner overrides to ${dataSource.name}")
                param.args[15] = disabled
                param.args[19] = disabled
                param.args[20] = disabled
            }
        })
        log("Disabling the GACS resources banner in ${dataSource.name}")
    }

    /**
     *  Builds a provider that always returns [value], so a flag can be pinned without relying on
     *  Dialer's obfuscated experiment layer
     */
    private fun Class<*>.constantProvider(value: Any): Any {
        return Proxy.newProxyInstance(classLoader, arrayOf(this)) { proxy, method, args ->
            when (method.name) {
                "a" -> value
                "equals" -> args?.firstOrNull() === proxy
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "PCS constant provider ($value)"
                else -> null
            }
        }
    }

    /**
     *  Additional hooks required to make Patrick work on some devices
     */
    private fun LoadPackageParam.hookPatrick(dexKit: DexKitBridge) {
        val patrickClass = dexKit.findClass {
            matcher {
                usingStrings("com/android/dialer/patrick/impl/checker/PatrickAvailabilityChecker")
            }
        }.singleOrNull()?.getInstance(classLoader) ?: run {
            log("Unable to find Patrick class")
            return
        }
        val patrickMethod = patrickClass.methods.firstOrNull {
            it.returnType == Object::class.java && Modifier.isFinal(it.modifiers)
        } ?: run {
            log("Unable to find Patrick method")
            return
        }
        XposedBridge.hookMethod(patrickMethod, object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val objectField = param.args[0]::class.java.declaredFields.firstOrNull {
                    it.type == Object::class.java && !Modifier.isFinal(it.modifiers)
                } ?: return
                objectField.isAccessible = true
                objectField.set(param.args[0], true)
            }
        })
    }

    /**
     *  Call recording is particularly annoying to get to behave when Call Notes are enabled, so
     *  force it
     */
    private fun LoadPackageParam.hookCallRecording(dexKit: DexKitBridge) {
        val callRecordingClass = dexKit.findClass {
            matcher {
                usingStrings("Call recording is enabled by call_recording_audio system feature")
            }
        }.singleOrNull()?.getInstance(classLoader) ?: run {
            log("Unable to find Call Recording class")
            return
        }
        XposedHelpers.findAndHookMethod(
            callRecordingClass,
            "a",
            object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })
    }

    private fun PhoneFlag.getValueOrNull(originalValue: Any?, settings: PhoneSettings): Any? {
        return when (this) {
            PhoneFlag.DOBBY_DOWNLOAD_PATH -> settings.dobbyUrl?.takeIf {
                settings.dobbyEnabled
            }?.decodeRawBase64()
            PhoneFlag.DOBBY_DUPLEX_FILES, PhoneFlag.DOBBY_MODELS -> settings.dobbyDuplexFiles
                ?.takeIf { settings.dobbyEnabled }
                ?.getListManifestOrNull(settings.dobbyRegion.locale)
                ?.reflectParseProto(originalValue?.javaClass ?: return null)
            PhoneFlag.DOBBY_IS_USER_IN_US -> if (settings.dobbyEnabled) {
                settings.dobbyRegion == DobbyRegion.US
            } else null
            PhoneFlag.DOBBY_IS_USER_IN_UK -> if (settings.dobbyEnabled) {
                settings.dobbyRegion == DobbyRegion.GB
            } else null
            PhoneFlag.DOBBY_IS_USER_IN_JP -> if (settings.dobbyEnabled) {
                settings.dobbyRegion == DobbyRegion.JP
            } else null
            PhoneFlag.DOBBY_IS_USER_IN_CA -> if (settings.dobbyEnabled) {
                settings.dobbyRegion == DobbyRegion.CA
            } else null
            PhoneFlag.DOBBY_IS_USER_IN_IE -> if (settings.dobbyEnabled) {
                settings.dobbyRegion == DobbyRegion.IE
            } else null
            PhoneFlag.DOBBY_IS_USER_IN_AU -> if (settings.dobbyEnabled) {
                settings.dobbyRegion == DobbyRegion.AU
            } else null
            PhoneFlag.DOBBY_IS_USER_IN_IN -> if (settings.dobbyEnabled) {
                settings.dobbyRegion == DobbyRegion.IN
            } else null
            PhoneFlag.DOBBY_ENABLE_V57_INDIA -> if (settings.dobbyEnabled) {
                true
            } else null
            PhoneFlag.DOBBY_INDIA_PB_FIX -> if (settings.dobbyEnabled) {
                true
            } else null
            PhoneFlag.ATLAS_MODELS -> settings.atlasModels
                ?.takeIf { settings.beeslyEnabled }
                ?.fromBase64()
                ?.reflectParseProto(originalValue?.javaClass ?: return null)
            PhoneFlag.BEESLY_MODEL_FILENAME_US -> settings.beesly
                ?.takeIf { settings.beeslyEnabled && settings.beeslyRegion != BeeslyRegion.GB }
                ?.getListManifestOrNull(settings.dobbyRegion.locale)
                ?.let { String(it) }
            PhoneFlag.BEESLY_MODEL_FILENAME_UK -> settings.beesly
                ?.takeIf { settings.beeslyEnabled && settings.beeslyRegion == BeeslyRegion.GB }
                ?.getListManifestOrNull(settings.beeslyRegion.locale)
                ?.let { String(it) }
            PhoneFlag.BEESLY_IS_USER_IN_US -> if (settings.beeslyEnabled) {
                settings.beeslyRegion == BeeslyRegion.US
            } else null
            PhoneFlag.BEESLY_IS_USER_IN_CA -> if (settings.beeslyEnabled) {
                settings.beeslyRegion == BeeslyRegion.CA
            } else null
            PhoneFlag.BEESLY_IS_USER_IN_UK -> if (settings.beeslyEnabled) {
                settings.beeslyRegion == BeeslyRegion.GB
            } else null
            PhoneFlag.BEESLY_IS_USER_IN_IE -> if (settings.beeslyEnabled) {
                settings.beeslyRegion == BeeslyRegion.IE
            } else null
            PhoneFlag.BEESLY_IS_USER_IN_AU -> if (settings.beeslyEnabled) {
                settings.beeslyRegion == BeeslyRegion.AU
            } else null
            PhoneFlag.XATU_MODELS -> settings.xatuModels
                ?.takeIf { settings.xatuEnabled }
                ?.fromBase64()
                ?.reflectParseProto(originalValue?.javaClass ?: return null)
            PhoneFlag.SHARPIE_ENABLED -> if (settings.sharpieEnabled) {
                true
            } else null
            PhoneFlag.DOBBY_ENABLED -> if (settings.dobbyEnabled) {
                true
            } else null
            PhoneFlag.ATLAS_ENABLED -> if (settings.atlasEnabled) {
                true
            } else null
            PhoneFlag.BEESLY_ENABLED -> if (settings.beeslyEnabled) {
                true
            } else null
            PhoneFlag.BEESLY_ACTIONS_ENABLED -> if (settings.beeslyEnabled) {
                true
            } else null
            PhoneFlag.BEESLY_GREETING_ENABLED -> if (settings.beeslyEnabled) {
                true
            } else null
            PhoneFlag.NAUTILUS_ENABLED -> if (settings.nautilusEnabled) {
                true
            } else null
            PhoneFlag.SONIC_ENABLED -> if (settings.sonicEnabled) {
                true
            } else null
            PhoneFlag.XATU_ENABLED -> if (settings.xatuEnabled) {
                true
            } else null
            PhoneFlag.CALLER_TAG_EXPERIMENT_ID -> if (settings.callerTagsEnabled) {
                1L
            } else null
            PhoneFlag.FERMAT_ENABLED -> when {
                settings.fermatEnabled -> 3L // Enables Call Notes at the top
                settings.callRecordingEnabled -> 4L // Enables Call Recording at the top
                else -> null
            }
            PhoneFlag.FERMAT_GEOFENCE -> if (settings.callRecordingEnabled || settings.fermatEnabled) {
                true
            } else null
            PhoneFlag.EXPRESSO_ENABLED -> if (settings.expressoEnabled) {
                true
            } else null
            PhoneFlag.PATRICK_ENABLED -> if (settings.patrickPhase >= PatrickPhase.PHASE_ONE) {
                true
            } else null
            PhoneFlag.PATRICK_PHASE_ONE_ENABLED -> if (settings.patrickPhase >= PatrickPhase.PHASE_ONE) {
                true
            } else null
            PhoneFlag.PATRICK_PHASE_TWO_ENABLED -> if (settings.patrickPhase >= PatrickPhase.PHASE_TWO) {
                true
            } else null
            PhoneFlag.PATRICK_PHASE_TWO_ENABLE_REPOSITORY -> if (settings.patrickPhase >= PatrickPhase.PHASE_TWO) {
                true
            } else null
            PhoneFlag.CALL_RECORDING_OVERRIDE_ENABLED -> if (settings.callRecordingEnabled) {
                true
            } else null
            PhoneFlag.CALL_RECORDING_ENABLED -> if (settings.callRecordingEnabled) {
                true
            } else null
            PhoneFlag.CALL_RECORDING_FORCE_OVERRIDE_ENABLED -> if (settings.callRecordingEnabled) {
                true
            } else null
            PhoneFlag.CALL_RECORDING_CROSBY_ENABLED -> if (settings.callRecordingEnabled) {
                true
            } else null
            PhoneFlag.CALL_RECORDING_FERMAT_DISABLE -> if (settings.callRecordingEnabled) {
                false
            } else null
            PhoneFlag.CALL_SCREEN_I18N_TIDEPODS -> if (settings.dobbyEnabled) {
                true
            } else null
        }
    }

    /**
     *  Dumps the Dobby data PCS has synced, so it is clear which manifest/download URLs the
     *  overrides are built from
     */
    private fun logDobbyData(settings: PhoneSettings) {
        log(
            "Dobby: enabled=${settings.dobbyEnabled}, region=${settings.dobbyRegion.locale}, " +
                    "url=${settings.dobbyUrl.describeDobbyUrl()}"
        )
        log("Dobby manifest: ${settings.dobbyDuplexFiles.describeManifestEntries()}")
        settings.dobbyDuplexFiles?.getListManifestOrNull(settings.dobbyRegion.locale)?.let {
            log("Dobby manifest entry (${it.size} bytes): ${it.describeBytes()}")
        }
    }

    private fun String?.describeDobbyUrl(): String {
        if (this == null) return "not set"
        return try {
            decodeRawBase64()
        }catch (e: Exception) {
            "invalid base64"
        }
    }

    private fun String?.describeManifestEntries(): String {
        if (this == null) return "not set"
        return try {
            PcsManifestList.parseFrom(fromBase64()).manifestList
                .joinToString(", ") { "${it.id}(${it.manifest.size()})" }
        }catch (e: Exception) {
            "unparseable"
        }
    }

    private fun ByteArray.describeBytes(): String {
        val text = String(this, Charsets.UTF_8)
        val printable = text.all { it.code in 0x20..0x7e || it == '\n' || it == '\t' }
        return if (printable) text.take(400) else joinToString("") { "%02x".format(it) }.take(400)
    }

    private fun String.fromBase64(): ByteArray {
        return Base64.decode(this, Base64.DEFAULT)
    }

    private fun String.decodeRawBase64(): String {
        return String(fromBase64())
    }

    private fun String.getListManifestOrNull(id: String): ByteArray? {
        val rawManifest = fromBase64()
        return try {
            PcsManifestList.parseFrom(rawManifest)
                .manifestList.firstOrNull { it.id.startsWith(id) }?.manifest?.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private fun ClassData.findMethodMultiple(vararg search: String): MethodData? {
        return search.firstNotNullOfOrNull { term ->
            findMethod {
                matcher {
                    usingStrings(term)
                }
            }.singleOrNull()
        }
    }

}

package com.kieronquinn.app.pcs.xposed

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import com.kieronquinn.app.pcs.repositories.DeviceConfigPropertiesRepository.Companion.AICORE_UNLOAD_INFERENCE
import com.kieronquinn.app.pcs.utils.extensions.SystemProperties_get
import com.kieronquinn.app.pcs.utils.extensions.SystemProperties_getBoolean
import com.kieronquinn.app.pcs.utils.extensions.loadDexKit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File
import java.lang.reflect.Modifier

/**
 * Prevents AICore from keeping the on-device inference service bound indefinitely after use. AICore
 * normally writes -1 to disable the framework's idle unbind timeout, then preloads the model again
 * when the inference service disconnects.
 */
object AiCoreHooks: XposedHooks {

    private const val SETTING_INFERENCE_SERVICE_UNBIND_TIMEOUT =
        "on_device_inference_unbind_timeout_ms"
    private const val INFERENCE_SERVICE_UNBIND_TIMEOUT_MILLIS = 5 * 60 * 1000L
    private const val PERSISTENT_MODE_PRELOAD_LOG =
        "Persistent mode is enabled. Scheduling preload model"
    /**
     *  Comma separated `key=value` list of AICore manifest labels to force, or `log` to only
     *  print the labels the device sends when asking Google for a manifest.
     */
    private const val MANIFEST_LABELS = "persist.aicore.manifest_labels"
    /**
     *  Comma separated `key=value` list of labels to force on the protected download manifest
     *  request itself, or `log` to only print the requests. Unlike [MANIFEST_LABELS] this runs
     *  directly on the request map, which is the only place `build_id` is present.
     */
    private const val MANIFEST_REQUEST_LABELS = "persist.aicore.manifest_request_labels"
    /**
     *  Set to any value to log AICore's per-caller feature visibility decisions (`cmo.b`), which is
     *  where features that are restricted to certain client groups are hidden from callers.
     */
    private const val LOG_FEATURE_VISIBILITY = "persist.aicore.log_feature_visibility"
    /**
     *  Set to any value to pretend AICore's local model store is empty, forcing it to fetch and
     *  apply the manifest at init instead of skipping the refresh because the store already has
     *  file groups in it.
     */
    private const val FORCE_MDD_REFRESH = "persist.aicore.force_mdd_refresh"
    /**
     *  Set to any value to log every Phenotype flag AICore reads, and use `key=value,key=value`
     *  to override them without having to change them on the device and restart.
     */
    private const val FLAG_OVERRIDES = "persist.aicore.flag_overrides"
    /**
     *  Path to a file with `key=value` lines of flag overrides, for when the list is too long for
     *  a system property. Keys may contain `*` for a wildcard match on the flag name.
     */
    private const val FLAG_OVERRIDES_FILE = "persist.aicore.flag_overrides_file"
    /**
     *  Set to any value to log AICore's model download pipeline: the file key lookups that
     *  resolve a file group's file list (which is where downloads fail with
     *  `File key <hash> not found`), and the requests the on device safety client makes.
     */
    private const val LOG_MDD_DOWNLOAD = "persist.aicore.log_mdd_download"
    @Volatile
    private var changedInferenceServiceUnbindTimeout = false

    @Volatile
    private var lastManifestLabels: String? = null

    @Volatile
    private var lastManifestRequest: String? = null

    @Volatile
    private var lastVisibility: String? = null

    override val tag = "AiCoreHooks"

    override fun hook(loadPackageParam: LoadPackageParam) {
        hookManifestLabels(loadPackageParam)
        hookManifestRequest(loadPackageParam)
        hookFeatureVisibility(loadPackageParam)
        hookForcedManifestRefresh(loadPackageParam)
        hookFlagOverrides(loadPackageParam)
        hookMddDownloadDiagnostics(loadPackageParam)
        if (!SystemProperties_getBoolean(AICORE_UNLOAD_INFERENCE, false)) return
        val onInferenceServiceDisconnected = dexKit(loadPackageParam)
            .findClass {
                matcher { usingStrings(PERSISTENT_MODE_PRELOAD_LOG) }
            }.singleOrNull()?.findMethod {
                matcher { usingStrings(PERSISTENT_MODE_PRELOAD_LOG) }
            }?.singleOrNull()?.getMethodInstance(loadPackageParam.classLoader)?.takeIf {
                it.name == "onInferenceServiceDisconnected" &&
                        it.parameterCount == 0 && it.returnType == Void.TYPE
            } ?: run {
                log("Unable to find supported inference service disconnect callback")
                return
            }
        XposedHelpers.findAndHookMethod(
            Settings.Secure::class.java,
            "putLong",
            ContentResolver::class.java,
            String::class.java,
            Long::class.java,
            object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[1] == SETTING_INFERENCE_SERVICE_UNBIND_TIMEOUT &&
                        param.args[2] == -1L) {
                        param.args[2] = INFERENCE_SERVICE_UNBIND_TIMEOUT_MILLIS
                        changedInferenceServiceUnbindTimeout = true
                        log("Changed inference service unbind timeout to " +
                            "$INFERENCE_SERVICE_UNBIND_TIMEOUT_MILLIS ms")
                    }
                }
            }
        )
        XposedBridge.hookMethod(
            onInferenceServiceDisconnected,
            object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (changedInferenceServiceUnbindTimeout && Settings.Secure.getLong(
                            (param.thisObject as Context).contentResolver,
                            SETTING_INFERENCE_SERVICE_UNBIND_TIMEOUT, -1L
                        ) == INFERENCE_SERVICE_UNBIND_TIMEOUT_MILLIS) {
                        param.result = null
                        log("Prevented inference model preload after idle disconnect")
                    }
                }
            }
        )
    }

    /**
     *  The AICore data package manifest is not bundled with the app, it is fetched from Google
     *  with the device labels `client_group`, `variant`, `device_tier` and `build_id`. Which
     *  features (and therefore which models, eg the `GACS` call screen LoRA) exist at all is
     *  decided server side from those labels, so log them and allow overriding them to see
     *  whether a different label combination yields a different feature set.
     */
    private fun hookManifestLabels(loadPackageParam: LoadPackageParam) {
        if (!propertyEnabled(MANIFEST_LABELS)) return
        val labelSupplier = dexKit(loadPackageParam).findClass {
            matcher {
                usingStrings("client_group", "device_tier", "build_id", "variant")
            }
        }.mapNotNull { classData ->
            runCatching { classData.getInstance(loadPackageParam.classLoader) }.getOrNull()
        }.firstOrNull {
            java.util.function.Supplier::class.java.isAssignableFrom(it)
        } ?: run {
            log("Unable to find the AICore manifest label supplier")
            return
        }
        val labelMethods = labelSupplier.declaredMethods.filter {
            it.parameterCount == 0 && it.returnType == Any::class.java
        }
        if (labelMethods.isEmpty()) {
            log("Unable to find the AICore manifest label supplier method")
            return
        }
        labelMethods.forEach { method ->
            XposedBridge.hookMethod(method, object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val labels = param.result as? MutableMap<*, *> ?: return
                    if (lastManifestLabels != labels.toString()) {
                        lastManifestLabels = labels.toString()
                        log("AICore manifest labels: $labels")
                    }
                    applyManifestLabelOverrides(labels)
                }
            })
        }
        log("Hooked AICore manifest labels via ${labelSupplier.name}")
    }

    private fun applyManifestLabelOverrides(labels: MutableMap<*, *>) {
        applyLabelOverrides(labels, MANIFEST_LABELS)
    }

    private fun applyLabelOverrides(labels: MutableMap<*, *>, property: String) {
        val overrides = SystemProperties_get(property) ?: return
        if (overrides.isBlank() || overrides == "log") return
        overrides.split(",").forEach { entry ->
            val parts = entry.split("=", limit = 2).map { it.trim() }
            if (parts.size != 2 || !labels.containsKey(parts[0])) return@forEach
            (labels as MutableMap<Any?, Any?>)[parts[0]] = parts[1]
            log("Overriding AICore manifest label ${parts[0]} -> ${parts[1]}")
        }
    }

    /**
     *  AICore builds one manifest request per client (role) from a map carrying `client_group`,
     *  `variant`, `device_tier` and `build_id`. `build_id` decides which data release the server
     *  hands out, and is not part of the map returned by the label supplier, so log and override
     *  it here instead.
     */
    private fun hookManifestRequest(loadPackageParam: LoadPackageParam) {
        if (!propertyEnabled(MANIFEST_LABELS) &&
            !propertyEnabled(MANIFEST_REQUEST_LABELS)
        ) return
        val requestClass = dexKit(loadPackageParam).findClass {
            matcher {
                usingStrings(
                    "Fetching manifest config for client %s",
                    "Unsupported label: "
                )
            }
        }.singleOrNull()?.let {
            runCatching {
                XposedHelpers.findClass(it.name, loadPackageParam.classLoader)
            }.getOrNull()
        } ?: run {
            log("Unable to find the AICore manifest request class")
            return
        }
        val requestMethod = requestClass.declaredMethods.firstOrNull {
            it.parameterCount == 3 && Map::class.java.isAssignableFrom(it.parameterTypes[1])
        } ?: run {
            log("Unable to find the AICore manifest request method")
            return
        }
        XposedBridge.hookMethod(
            requestMethod,
            object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val labels = param.args[1] as? MutableMap<*, *> ?: return
                    val clientId = runCatching {
                        param.args[0].javaClass.getMethod("a").invoke(param.args[0]) as? String
                    }.getOrNull() ?: param.args[0].toString()
                    val labelsString = "$clientId$labels"
                    if (lastManifestRequest != labelsString) {
                        lastManifestRequest = labelsString
                        log("AICore manifest request for $clientId with labels $labels")
                    }
                    applyLabelOverrides(labels, MANIFEST_REQUEST_LABELS)
                }
            }
        )
        log("Hooked the AICore manifest request via ${requestClass.name}")
    }

    private fun dexKit(loadPackageParam: LoadPackageParam) =
        loadDexKit(loadPackageParam.appInfo.sourceDir)

    /**
     *  `persist` properties cannot be removed, only set to an empty value, so treat the usual
     *  "off" spellings as disabled.
     */
    private fun propertyEnabled(name: String): Boolean {
        val value = SystemProperties_get(name) ?: return false
        return value.isNotBlank() && value != "0" && !value.equals("false", ignoreCase = true)
    }

    /**
     *  AICore only fetches and applies the manifest when its local model store is empty, otherwise
     *  it logs `MDD2 not empty, skipping refresh at init` and does nothing, so a manifest that was
     *  provisioned incorrectly stays that way forever. Force that check to see an empty store to
     *  make AICore re-provision from the manifest served by PCS.
     */
    private fun hookForcedManifestRefresh(loadPackageParam: LoadPackageParam) {
        if (!propertyEnabled(FORCE_MDD_REFRESH)) return
        val refreshClass = dexKit(loadPackageParam).findClass {
            matcher {
                usingStrings(
                    "MDD2 not empty, skipping refresh at init",
                    "MDD2 empty, fetching manifest"
                )
            }
        }.singleOrNull()?.let {
            runCatching {
                XposedHelpers.findClass(it.name, loadPackageParam.classLoader)
            }.getOrNull()
        } ?: run {
            log("Unable to find the AICore model store refresh class")
            return
        }
        val refreshMethod = refreshClass.declaredMethods.firstOrNull {
            it.parameterCount == 1 && List::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: run {
            log("Unable to find the AICore model store refresh method")
            return
        }
        XposedBridge.hookMethod(refreshMethod, object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val groups = param.args[0] as? List<*> ?: return
                if (groups.isEmpty()) return
                log("Forcing AICore manifest refresh, ignoring ${groups.size} local file group(s)")
                param.args[0] = emptyList<Any>()
            }
        })
        log("Hooked the AICore model store refresh via ${refreshClass.name}")
    }

    /**
     *  AICore reads its configuration (manifest build ids, model download behaviour, whether a
     *  whole class of features is enabled at all) through Phenotype flags. Log every read and
     *  allow overriding them by name so a flag can be tried out without touching the device.
     */
    private fun hookFlagOverrides(loadPackageParam: LoadPackageParam) {
        val property = SystemProperties_get(FLAG_OVERRIDES)
        val file = SystemProperties_get(FLAG_OVERRIDES_FILE)
        if (property == null && file == null) return
        val overrides = buildMap {
            putAll(parseFlagOverrides(property ?: ""))
            if (file != null) {
                runCatching {
                    putAll(parseFlagOverrides(File(file).readText().replace("\n", ",")))
                }.onFailure {
                    log("Unable to read AICore flag overrides from $file: $it")
                }
            }
        }
        val flagsClass = dexKit(loadPackageParam).findClass {
            matcher {
                usingStrings(
                    "AicModels__mdd_maintenance_at_init_enabled",
                    "AicDataRelease__build_id_18103149225492435673"
                )
            }
        }.singleOrNull()?.let {
            runCatching {
                XposedHelpers.findClass(it.name, loadPackageParam.classLoader)
            }.getOrNull()
        } ?: run {
            log("Unable to find the AICore flags class")
            return
        }
        val readerType = flagsClass.declaredConstructors
            .flatMap { it.parameterTypes.toList() }
            .firstOrNull { type ->
                type.isInterface && type.declaredMethods.any {
                    it.parameterCount == 1 && (it.returnType == java.lang.Boolean::class.java ||
                            it.returnType == Boolean::class.javaPrimitiveType)
                }
            } ?: run {
            log("Unable to find the AICore flag reader type in ${flagsClass.name}")
            return
        }
        val readerField = flagsClass.declaredFields.firstOrNull { field ->
            !Modifier.isStatic(field.modifiers) && field.type == readerType
        } ?: run {
            log("Unable to find the AICore flag reader field in ${flagsClass.name}")
            return
        }
        val hookedReaders = mutableSetOf<Class<*>>()
        flagsClass.declaredConstructors.forEach { constructor ->
            XposedBridge.hookMethod(constructor, object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val fieldValue = runCatching {
                        readerField.isAccessible = true
                        readerField.get(param.thisObject)
                    }.onFailure {
                        log("Unable to read the AICore flag reader: $it")
                    }.getOrNull() ?: return
                    val readerClass = fieldValue.javaClass
                    if (!hookedReaders.add(readerClass)) return
                    var count = 0
                    generateSequence(readerClass) { it.superclass }.forEach { type ->
                        type.declaredMethods.forEach { method ->
                            if (method.parameterCount != 1) return@forEach
                            if (method.parameterTypes[0] != String::class.java) return@forEach
                            if (method.returnType != String::class.java) return@forEach
                            XposedBridge.hookMethod(method, rawFlagHook(overrides))
                            count++
                        }
                    }
                    log("Hooked $count AICore flag lookups via ${readerClass.name}")
                }
            })
        }
        log("Hooked the AICore flags via ${flagsClass.name}")
    }

    /**
     *  Flag lookups end up as a `flagName -> raw string` read, which is the easiest place to both
     *  log and override them.
     */
    private fun rawFlagHook(overrides: Map<String, String>): XC_MethodHook =
        object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val name = param.args[0] as? String ?: return
                val override = flagOverride(overrides, name) ?: return
                log("Overriding AICore flag $name -> $override")
                param.result = override
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val name = param.args[0] as? String ?: return
                logFlagRead(name, param.result)
            }
        }

    /**
     *  Matches an override exactly, or by `*` wildcard (prefix or suffix) when the flag name is
     *  too long to spell out.
     */
    private fun flagOverride(overrides: Map<String, String>, name: String): String? {
        overrides[name]?.let { return it }
        if (overrides.size > WILDCARD_OVERRIDE_LIMIT) return null
        return overrides.entries.firstOrNull { (key, _) ->
            val star = key.indexOf('*')
            if (star < 0) return@firstOrNull false
            val prefix = key.substring(0, star)
            val suffix = key.substring(star + 1)
            name.length >= prefix.length + suffix.length &&
                    name.startsWith(prefix) && name.endsWith(suffix)
        }?.value
    }

    private const val WILDCARD_OVERRIDE_LIMIT = 64

    private fun parseFlagOverrides(property: String): Map<String, String> {
        if (property.isBlank() || property == "log") return emptyMap()
        return property.split(",").mapNotNull { entry ->
            val parts = entry.split("=", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            parts[0].trim() to parts[1].trim()
        }.toMap()
    }

    private fun logFlagRead(name: String, result: Any?) {
        if (loggedFlags.size > MAX_LOGGED_FLAGS) return
        if (loggedFlags.add(name)) log("AICore flag $name = $result")
    }

    private val loggedFlags = mutableSetOf<String>()

    private const val MAX_LOGGED_FLAGS = 400

    /**
     *  A model file group is downloaded by resolving the file keys its manifest entry lists
     *  against the file key map of the group AICore already has stored. When the two disagree
     *  (`grk.a`) the download aborts with `File key <hash> not found` before any network call,
     *  which is what happens for the Dialer agentic call screen model. Log both sides of the
     *  lookup, plus the URL downloads that follow it, so the mismatch can be attributed to
     *  either the manifest served by PCS or the store AICore kept from an earlier release.
     */
    private fun hookMddDownloadDiagnostics(loadPackageParam: LoadPackageParam) {
        if (!propertyEnabled(LOG_MDD_DOWNLOAD)) return
        val classes = dexKit(loadPackageParam).findClass {
            matcher { usingStrings("File key ", " not found") }
        }
        var hooked = 0
        classes.forEach { classData ->
            val clazz = runCatching {
                XposedHelpers.findClass(classData.name, loadPackageParam.classLoader)
            }.getOrNull() ?: return@forEach
            clazz.declaredMethods.filter {
                Modifier.isStatic(it.modifiers) && it.parameterCount == 2
            }.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val throwable = param.throwable ?: return
                        log("MDD file key lookup failed: $throwable")
                        param.args.forEachIndexed { index, arg ->
                            log("  MDD arg[$index]: ${describeValue(arg)}")
                        }
                    }
                })
                hooked++
            }
        }
        log("Hooked $hooked AICore MDD file key lookup method(s)")
        hookMddDownloadRequests(loadPackageParam)
        hookMddErrorWrapper(loadPackageParam)
        val downloadMethod = dexKit(loadPackageParam).findMethod {
            matcher { usingStrings("Preparing to start downloading from url='%s'") }
        }.singleOrNull()?.let {
            runCatching { it.getMethodInstance(loadPackageParam.classLoader) }.getOrNull()
        } ?: run {
            log("Unable to find the AICore URL download method")
            return
        }
        XposedBridge.hookMethod(downloadMethod, object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                log(
                    "AICore URL download via ${downloadMethod.declaringClass.simpleName}: " +
                            param.args.joinToString { it.toString().take(160) }
                )
            }
        })
        log("Hooked AICore URL download via ${downloadMethod.declaringClass.name}")
    }

    /**
     *  Failed downloads are re-thrown through `dpc.aV`, which logs the file group a download
     *  belonged to together with the underlying error. Log both so a `File key ... not found`
     *  can be tied to the group it was requested for, and to the class that threw it.
     */
    private fun hookMddErrorWrapper(loadPackageParam: LoadPackageParam) {
        val wrapperClass = dexKit(loadPackageParam).findClass {
            matcher { usingStrings("MetadataStore interaction failed with error: ") }
        }.singleOrNull()?.let {
            runCatching {
                XposedHelpers.findClass(it.name, loadPackageParam.classLoader)
            }.getOrNull()
        } ?: run {
            log("Unable to find the AICore MDD error wrapper")
            return
        }
        val wrapperMethod = wrapperClass.declaredMethods.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.parameterCount == 2 &&
                    it.parameterTypes[0] == Exception::class.java
        } ?: run {
            log("Unable to find the AICore MDD error wrapper method")
            return
        }
        XposedBridge.hookMethod(wrapperMethod, object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val exception = param.args[0] as? Exception ?: return
                val group = param.args[1]
                val message = exception.message ?: exception.toString()
                val line = "$group|$message"
                if (line == lastMddError) return
                lastMddError = line
                log("MDD download failed for group $group: $message")
                exception.stackTrace.take(ERROR_STACK_TRACE_DEPTH).forEach {
                    log("  MDD error at $it")
                }
            }
        })
        log("Hooked the AICore MDD error wrapper via ${wrapperClass.name}")
    }

    /**
     *  Logs the download requests that reach the file key lookup so a failed key can be tied to
     *  the group (and role) it was requested for.
     */
    private fun hookMddDownloadRequests(loadPackageParam: LoadPackageParam) {
        dexKit(loadPackageParam).findClass {
            matcher { usingStrings("File key ", " not found") }
        }.forEach { classData ->
            val clazz = runCatching {
                XposedHelpers.findClass(classData.name, loadPackageParam.classLoader)
            }.getOrNull() ?: return@forEach
            clazz.declaredMethods.filter {
                it.parameterCount == 2 && !Modifier.isStatic(it.modifiers) &&
                        it.returnType == Any::class.java
            }.forEach { method ->
                XposedBridge.hookMethod(method, object: XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val request = param.args[0] ?: return
                        val line = "${method.declaringClass.simpleName}.${method.name}|$request"
                        if (line == lastMddRequest) return
                        lastMddRequest = line
                        log("MDD download request ${method.declaringClass.simpleName}." +
                                "${method.name}: ${describeValue(request)}")
                        log("MDD download context: ${describeInstance(param.thisObject)}")
                    }
                })
                log("Hooked AICore MDD request via ${clazz.name}.${method.name}")
            }
        }
    }

    @Volatile
    private var lastMddRequest: String? = null

    /**
     *  Prints the type of every field of an AICore object, which is enough to tell which
     *  collaborators a download resolution step is using without dumping their whole state.
     */
    private fun describeInstance(value: Any?): String {
        if (value == null) return "null"
        val builder = StringBuilder(value.javaClass.name)
        runCatching {
            value.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
                .forEach { field ->
                    field.isAccessible = true
                    val fieldValue = runCatching { field.get(value) }.getOrNull()
                    val detail = when (fieldValue) {
                        is Map<*, *> -> "size=${fieldValue.size}"
                        is Collection<*> -> "size=${fieldValue.size}"
                        null -> "null"
                        else -> fieldValue.javaClass.simpleName
                    }
                    builder.append("\n      ${field.name}: ${field.type.simpleName} = $detail")
                }
        }
        return builder.toString()
    }

    @Volatile
    private var lastMddError: String? = null

    private const val ERROR_STACK_TRACE_DEPTH = 10

    /**
     *  Prints the fields of an AICore protobuf so the two sides of a failed file key lookup can
     *  be compared from the logs alone, without having to pull the store off the device again.
     */
    private fun describeValue(value: Any?): String {
        if (value == null) return "null"
        val builder = StringBuilder(value.javaClass.name)
        runCatching {
            value.javaClass.declaredFields.filterNot {
                Modifier.isStatic(it.modifiers)
            }.forEach { field ->
                field.isAccessible = true
                val fieldValue = runCatching { field.get(value) }.getOrNull()
                when (fieldValue) {
                    is Map<*, *> -> builder.append(
                        "\n      ${field.name} = map(${fieldValue.size}) " +
                                "keys ${fieldValue.keys.take(DIAGNOSTIC_SAMPLE_SIZE)}"
                    )
                    is Collection<*> -> builder.append(
                        "\n      ${field.name} = list(${fieldValue.size}) " +
                                "sample ${fieldValue.take(DIAGNOSTIC_SAMPLE_SIZE)}"
                    )
                    else -> builder.append(
                        "\n      ${field.name} = ${fieldValue.toString().take(200)}"
                    )
                }
            }
        }
        return builder.toString()
    }

    private const val DIAGNOSTIC_SAMPLE_SIZE = 8

    /**
     *  Features are only handed to a caller if their declared clients (or the client group the
     *  device is in) allow it. Log that decision for every feature so features that exist in the
     *  store but are hidden from the caller can be told apart from features that were never
     *  provisioned.
     */
    private fun hookFeatureVisibility(loadPackageParam: LoadPackageParam) {
        if (!propertyEnabled(LOG_FEATURE_VISIBILITY)) return
        val visibilityClass = dexKit(loadPackageParam).findClass {
            matcher {
                usingStrings(
                    "com.google.android.aicore.demo",
                    "com.google.android.apps.aicore.e2e.mh.llm.download"
                )
            }
        }.singleOrNull()?.let {
            runCatching {
                XposedHelpers.findClass(it.name, loadPackageParam.classLoader)
            }.getOrNull()
        } ?: run {
            log("Unable to find the AICore feature visibility class")
            return
        }
        val visibilityMethods = visibilityClass.declaredMethods.filter {
            it.parameterCount == 1 && it.returnType == Boolean::class.javaPrimitiveType
        }
        if (visibilityMethods.isEmpty()) {
            log("Unable to find the AICore feature visibility method")
            return
        }
        val clientGroupField = visibilityClass.declaredFields.firstOrNull {
            it.type == String::class.java
        }
        visibilityMethods.forEach { method ->
            XposedBridge.hookMethod(method, object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val clientGroup = runCatching {
                        clientGroupField?.get(param.thisObject) as? String
                    }.getOrNull()
                    val result = param.result as? Boolean ?: return
                    val feature = param.args[0].toString().replace("\n", " ")
                    val line = "$clientGroup|$result|$feature"
                    if (lastVisibility != line) {
                        lastVisibility = line
                        log("Feature visibility (group=$clientGroup, visible=$result): $feature")
                    }
                }
            })
        }
        log("Hooked AICore feature visibility via ${visibilityClass.name}")
    }

}

package com.kieronquinn.app.pcs.repositories

import android.content.SharedPreferences
import com.kieronquinn.app.pcs.utils.extensions.SystemProperties_get
import java.util.Locale

/** Import legacy Phone properties once. Explicit UI settings always win. */
internal object PhonePreferencesMigration {
    internal const val MARKER = "phone_properties_migrated_v1"

    fun migrate(preferences: SharedPreferences) {
        val values = pendingValues(preferences.all, ::SystemProperties_get)
        if (values.isEmpty()) return
        val editor = preferences.edit()
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                else -> error("Unsupported Phone preference: $key")
            }
        }
        // Values and marker are persisted together. A failed write is retried on the next open.
        check(editor.commit()) { "Unable to migrate legacy Phone properties" }
    }

    internal fun pendingValues(
        existing: Map<String, *>,
        readProperty: (String) -> String?
    ): Map<String, Any> {
        if (existing[MARKER] == true) return emptyMap()
        val values = mutableMapOf<String, Any>()
        fun import(key: String, value: () -> Any) {
            if (!existing.containsKey(key)) values[key] = value()
        }
        import("phone_call_screen_mode") {
            val value = readProperty("persist.pcs.call_screen_agentic")
            when {
                value.isNullOrBlank() || value == "0" || value.equals("false", true) -> "DUPLEX"
                value.equals("force", true) -> "FORCE_AGENTIC"
                else -> "DIALER_DEFAULT"
            }
        }
        // Country and policy form one option; do not mix a saved UI policy with a legacy country.
        if (!existing.containsKey("phone_sharpie_country_mode")) {
            val value = readProperty("persist.pcs.sharpie_country")?.trim()
            val mode = when {
                value.isNullOrEmpty() || value.equals("allow", true) || value.equals("all", true) -> "ALLOW_ALL"
                value == "0" || value.equals("off", true) || value.equals("false", true) -> "DIALER_DEFAULT"
                value.length == 2 -> "FORCE_COUNTRY"
                else -> "ALLOW_ALL"
            }
            values["phone_sharpie_country_mode"] = mode
            import("phone_sharpie_country") {
                if (mode == "FORCE_COUNTRY") value!!.uppercase(Locale.ROOT) else "US"
            }
        }
        import("phone_xatu_allow_metered") {
            !readProperty("persist.pcs.mdd_metered").equals("off", true)
        }
        import("phone_manifest_handling_enabled") {
            readProperty("persist.phone.pcs_enabled").legacyBoolean()
        }
        import("phone_debug_logging") {
            readProperty("persist.pcs.debug").legacyBoolean()
        }
        // Stored on both branches so switching to main cannot lose the pre-migration choice.
        import("phone_ai_replies_enabled") {
            readProperty("persist.pcs.ai_replies").legacyBoolean()
        }
        values[MARKER] = true
        return values
    }

    private fun String?.legacyBoolean() = this in setOf("1", "y", "yes", "on", "true")
}

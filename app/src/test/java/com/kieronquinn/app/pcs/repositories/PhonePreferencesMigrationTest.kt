package com.kieronquinn.app.pcs.repositories

import org.junit.Assert.*
import org.junit.Test

class PhonePreferencesMigrationTest {
    private fun migrate(properties: Map<String, String?> = emptyMap(), existing: Map<String, Any> = emptyMap()) =
        PhonePreferencesMigration.pendingValues(existing, properties::get)

    @Test fun unsetPropertiesKeepLegacyDefaults() {
        val values = migrate()
        assertEquals("DUPLEX", values["phone_call_screen_mode"])
        assertEquals("ALLOW_ALL", values["phone_sharpie_country_mode"])
        assertEquals("US", values["phone_sharpie_country"])
        assertEquals(true, values["phone_xatu_allow_metered"])
        assertEquals(false, values["phone_manifest_handling_enabled"])
        assertEquals(false, values["phone_debug_logging"])
        assertEquals(false, values["phone_ai_replies_enabled"])
    }

    @Test fun existingPreferencesWinWithoutBeingRewritten() {
        val saved = mapOf<String, Any>(
            "phone_call_screen_mode" to "DIALER_DEFAULT",
            "phone_sharpie_country_mode" to "FORCE_COUNTRY",
            "phone_sharpie_country" to "GB",
            "phone_xatu_allow_metered" to false,
            "phone_manifest_handling_enabled" to false,
            "phone_debug_logging" to false,
            "phone_ai_replies_enabled" to false,
            "phone_dobby_region" to "CN"
        )
        val pending = PhonePreferencesMigration.pendingValues(saved) { error("Unexpected legacy read: $it") }
        assertEquals(mapOf(PhonePreferencesMigration.MARKER to true), pending)
        assertEquals("CN", (saved + pending)["phone_dobby_region"])
    }

    @Test fun changedPropertiesAreNeverReadAfterMigration() {
        val saved = migrate(mapOf("persist.pcs.call_screen_agentic" to "force"))
        assertTrue(PhonePreferencesMigration.pendingValues(saved) { error("Read after migration") }.isEmpty())
        assertEquals("FORCE_AGENTIC", saved["phone_call_screen_mode"])
    }

    @Test fun modelParsingPreservesAllLegacyModes() {
        mapOf(null to "DUPLEX", "" to "DUPLEX", " " to "DUPLEX", "0" to "DUPLEX",
            "FALSE" to "DUPLEX", "FORCE" to "FORCE_AGENTIC", "1" to "DIALER_DEFAULT",
            "true" to "DIALER_DEFAULT", " force " to "DIALER_DEFAULT").forEach { (value, expected) ->
            assertEquals(value, expected, migrate(mapOf("persist.pcs.call_screen_agentic" to value))["phone_call_screen_mode"])
        }
    }

    @Test fun countryAndMeteredParsingKeepLegacySemantics() {
        val values = migrate(mapOf("persist.pcs.sharpie_country" to " gb ", "persist.pcs.mdd_metered" to "OFF"))
        assertEquals("FORCE_COUNTRY", values["phone_sharpie_country_mode"])
        assertEquals("GB", values["phone_sharpie_country"])
        assertEquals(false, values["phone_xatu_allow_metered"])
        listOf("off", "0", "false").forEach {
            assertEquals("DIALER_DEFAULT", migrate(mapOf("persist.pcs.sharpie_country" to it))["phone_sharpie_country_mode"])
        }
        assertEquals(true, migrate(mapOf("persist.pcs.mdd_metered" to "false"))["phone_xatu_allow_metered"])
    }

    @Test fun importsManifestDebugAndAiRepliesChoices() {
        val values = migrate(mapOf("persist.phone.pcs_enabled" to "1", "persist.pcs.debug" to "true", "persist.pcs.ai_replies" to "yes"))
        assertEquals(true, values["phone_manifest_handling_enabled"])
        assertEquals(true, values["phone_debug_logging"])
        assertEquals(true, values["phone_ai_replies_enabled"])
    }
}

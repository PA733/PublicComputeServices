package com.kieronquinn.app.pcs.repositories

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class EnumPreferenceCompatibilityTest {
    private enum class OldRegion { US, GB }
    private enum class NewRegion { US, GB, CN }

    @Test fun switchingBranchesKeepsUnsupportedStoredRegionWithoutFailingProviderReads() {
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getString" -> "CN"
                else -> error("Unexpected preferences operation: ${method.name}")
            }
        } as SharedPreferences
        val repository = object : BaseSettingsRepositoryImpl() {
            override val sharedPreferences = preferences
        }
        assertEquals(OldRegion.US, repository.enum("region", OldRegion.US).getSync())
        assertEquals(NewRegion.CN, repository.enum("region", NewRegion.US).getSync())
    }
}

package com.kieronquinn.app.pcs.callscreen

import com.kieronquinn.app.pcs.model.gacs.GacsLocalization.SodaLanguagePack
import com.kieronquinn.app.pcs.model.gacs.GacsLocalization.SodaLanguagePackList
import com.kieronquinn.app.pcs.model.gacs.GacsLocalization.SodaLanguagePackInfo
import com.kieronquinn.app.pcs.model.gacs.GacsLocalization.SodaLanguagePackCache
import org.junit.Assert.*
import org.junit.Test

class SodaLanguageAliasesTest {
    @Test
    fun `cold start replaces stale unsupported alias using actual Mandarin cache state`() {
        val original = SodaLanguagePackCache.newBuilder()
            .putPacks("cmn-Hans-CN", SodaLanguagePackInfo.newBuilder().setLocale("cmn-Hans-CN")
                .setAvailability(1).setVersion(3056).build())
            .putPacks("zh-CN", SodaLanguagePackInfo.newBuilder().setLocale("zh-CN")
                .setAvailability(3).build()).build()
        val bytes = SodaLanguageAliases.withChineseCachedAlias(original.toByteArray())
        val updated = SodaLanguagePackCache.parseFrom(bytes)
        assertEquals(original.packsMap["cmn-Hans-CN"], updated.packsMap["cmn-Hans-CN"])
        assertEquals(1, updated.packsMap["zh-CN"]!!.availability)
        assertEquals(3056, updated.packsMap["zh-CN"]!!.version)
        assertArrayEquals(bytes, SodaLanguageAliases.withChineseCachedAlias(bytes))
    }

    @Test
    fun `cold start never upgrades an unavailable Mandarin pack`() {
        val unavailable = SodaLanguagePackCache.newBuilder()
            .putPacks("cmn-Hans-CN", SodaLanguagePackInfo.newBuilder().setLocale("cmn-Hans-CN")
                .setAvailability(2).setVersion(3056).build()).build()
        val result = SodaLanguagePackCache.parseFrom(SodaLanguageAliases.withChineseCachedAlias(unavailable.toByteArray()))
        assertEquals(2, result.packsMap["zh-CN"]!!.availability)
        val missing = SodaLanguagePackCache.getDefaultInstance().toByteArray()
        assertArrayEquals(missing, SodaLanguageAliases.withChineseCachedAlias(missing))
    }

    @Test
    fun `cached alias keeps every availability state and version`() {
        for (state in 0..4) {
            val original = SodaLanguagePackInfo.parseFrom(SodaLanguagePackInfo.newBuilder()
                .setLocale("cmn-Hans-CN").setAvailability(state).setVersion(3056).build()
                .toByteArray() + byteArrayOf(0xa8.toByte(), 0x01, 123))
            val alias = SodaLanguagePackInfo.parseFrom(SodaLanguageAliases.withChineseInfoLocale(original.toByteArray()))
            assertEquals(original.toBuilder().setLocale("zh-CN").build(), alias)
        }
    }

    @Test
    fun `alias preserves version metadata and other language packs`() {
        val metadata = byteArrayOf(0xa8.toByte(), 0x01, 123) // Unknown field 21, varint 123.
        val mandarin = SodaLanguagePack.parseFrom(SodaLanguagePack.newBuilder()
            .setLocale("cmn-Hans-CN").setVersion(3056).build().toByteArray() + metadata)
        val english = SodaLanguagePack.newBuilder().setLocale("en-US").setVersion(12081).build()
        val original = SodaLanguagePackList.newBuilder().addPacks(english).addPacks(mandarin).build()
        val result = SodaLanguagePackList.parseFrom(SodaLanguageAliases.withChineseAlias(original.toByteArray()))
        assertEquals(listOf(english, mandarin), result.packsList.take(2))
        assertEquals(mandarin.toBuilder().setLocale("zh-CN").build(), result.packsList.last())
    }

    @Test
    fun `missing Mandarin is never added to downloaded list`() {
        val supported = SodaLanguagePackList.newBuilder().addPacks(
            SodaLanguagePack.newBuilder().setLocale("cmn-Hans-CN").setVersion(3056)).build()
        val downloaded = SodaLanguagePackList.newBuilder().addPacks(
            SodaLanguagePack.newBuilder().setLocale("en-US").setVersion(12081)).build()
        assertEquals(2, SodaLanguagePackList.parseFrom(
            SodaLanguageAliases.withChineseAlias(supported.toByteArray())).packsCount)
        assertArrayEquals(downloaded.toByteArray(), SodaLanguageAliases.withChineseAlias(downloaded.toByteArray()))
    }

    @Test
    fun `native Chinese entry is preserved without duplicate aliases`() {
        val original = SodaLanguagePackList.newBuilder()
            .addPacks(SodaLanguagePack.newBuilder().setLocale("cmn-Hans-CN").setVersion(3056))
            .addPacks(SodaLanguagePack.newBuilder().setLocale("zh-CN").setVersion(4000))
            .build().toByteArray()
        assertArrayEquals(original, SodaLanguageAliases.withChineseAlias(original))
    }
}

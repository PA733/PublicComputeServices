package com.kieronquinn.app.pcs.callscreen

import com.kieronquinn.app.pcs.model.gacs.GacsLocalization.SodaLanguagePackCache
import com.kieronquinn.app.pcs.model.gacs.GacsLocalization.SodaLanguagePackInfo
import com.kieronquinn.app.pcs.model.gacs.GacsLocalization.SodaLanguagePackList

object SodaLanguageAliases {
    const val MANDARIN = "cmn-Hans-CN"
    const val SIMPLIFIED_CHINESE = "zh-CN"

    fun withChineseInfoLocale(bytes: ByteArray): ByteArray = SodaLanguagePackInfo.parseFrom(bytes)
        .toBuilder().setLocale(SIMPLIFIED_CHINESE).build().toByteArray()

    /** Migrate the existing cache before Dialer's DataStore opens it on a cold start. */
    fun withChineseCachedAlias(bytes: ByteArray): ByteArray {
        val cache = SodaLanguagePackCache.parseFrom(bytes)
        val mandarin = cache.packsMap[MANDARIN] ?: return bytes
        val alias = mandarin.toBuilder().setLocale(SIMPLIFIED_CHINESE).build()
        if (cache.packsMap[SIMPLIFIED_CHINESE] == alias) return bytes
        return cache.toBuilder().putPacks(SIMPLIFIED_CHINESE, alias).build().toByteArray()
    }

    /** Add a Dialer alias within the same supported/downloaded/pending list. */
    fun withChineseAlias(bytes: ByteArray): ByteArray {
        val list = SodaLanguagePackList.parseFrom(bytes)
        if (list.packsList.any { it.locale == SIMPLIFIED_CHINESE }) return bytes
        val mandarin = list.packsList.firstOrNull { it.locale == MANDARIN } ?: return bytes
        return list.toBuilder().addPacks(mandarin.toBuilder().setLocale(SIMPLIFIED_CHINESE))
            .build().toByteArray()
    }
}

package com.kieronquinn.app.pcs.utils.extensions

import android.content.Context
import android.util.Log
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.kieronquinn.app.pcs.utils.OfficialSekret

fun Context.getManifestKey(): KeysetHandle {
    val key = OfficialSekret.manifestKey()?.hexToByteArray()
    if (SystemProperties_get("persist.pcs.dump_manifest") != null) {
        Log.d("PcsManifestDump", "manifest key: ${key?.toHexString()}")
    }
    return key?.toKeysetHandle() ?: throw IllegalStateException("Unable to load manifest key")
}

fun ByteArray.toKeysetHandle(): KeysetHandle {
    return CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(this))
}

/**
 *  Decrypt a manifest with a given keyset handle. If a [Context] is passed, the signature of the
 *  **module** will be used as the context info, if `null` then an empty array will be used.
 */
fun ByteArray.decryptManifest(context: Context?, key: KeysetHandle): ByteArray {
    val contextInfo = context?.getSignatureHash() ?: byteArrayOf()
    return key.getPrimitive(
        RegistryConfiguration.get(),
        HybridDecrypt::class.java
    ).decrypt(this, contextInfo)
}

/**
 *  The signing certificate of the official PCS release, used for both the sekret key lookup and
 *  as the manifest decryption context info. This module is not signed with it, so it is provided
 *  directly instead of being read from the installed package.
 */
fun Context.getSignatureHash(): ByteArray {
    return OfficialSekret.signatureHash
}

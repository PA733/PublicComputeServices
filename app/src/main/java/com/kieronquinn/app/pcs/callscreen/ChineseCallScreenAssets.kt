package com.kieronquinn.app.pcs.callscreen

import com.google.gson.Gson
import com.kieronquinn.app.pcs.model.gacs.GacsLocalization.RobotIntentSpecs
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

/** Builds a private overlay. Google's downloaded files are never edited. */
class ChineseCallScreenAssets(
    private val cacheDirectory: File,
    private val openAsset: (String) -> InputStream
) {
    data class Phrase(val action: String, val chinese: String)
    data class LocalizedSpecs(val bytes: ByteArray, val recordings: Map<String, String>)

    companion object {
        const val SPEC_FILE = "agenticcallscreen_robot_intent_specs.pb"
        const val ASSET_ROOT = "callscreen/zh-CN"
        // Bump when regenerating recordings, even when the text stays the same.
        private const val VERSION = "2"
        private val chipLabels = mapOf(
            "RequestCallBackAndEndCall" to "稍后回拨",
            "ReportSpamAndEndCallSpam" to "标记为骚扰",
            "SuggestLeavingMessage" to "留言"
        )
    }

    private val phrases: Map<String, String> by lazy {
        openAsset("$ASSET_ROOT/phrases.json").bufferedReader().use {
            Gson().fromJson(it, Array<Phrase>::class.java).associate { phrase ->
                phrase.action to phrase.chinese
            }
        }
    }

    fun localize(bytes: ByteArray): LocalizedSpecs {
        val original = RobotIntentSpecs.parseFrom(bytes)
        require(original.intentsCount > 0) { "GACS has no robot intents" }
        val result = original.toBuilder()
        val recordings = linkedMapOf<String, String>()
        original.intentsList.forEachIndexed { index, intent ->
            val translated = phrases[intent.name]
                ?: error("No Chinese recording for GACS intent ${intent.name}")
            val updated = intent.toBuilder()
            require(intent.speechCount > 0) { "No speech for ${intent.name}" }
            intent.speechList.forEachIndexed { speechIndex, speech ->
                require(speech.hasRecording() && speech.recording.path.isNotBlank()) {
                    "Unsupported GACS speech for ${intent.name}"
                }
                val relativePath = speech.recording.path + ".wav"
                requireSafeRelativePath(relativePath)
                val previous = recordings.put(relativePath, intent.name)
                require(previous == null || previous == intent.name) { "Conflicting GACS recording" }
                updated.setSpeech(speechIndex, speech.toBuilder()
                    .setLocale(SodaLanguageAliases.SIMPLIFIED_CHINESE)
                    .setRecording(speech.recording.toBuilder().setText(translated)))
            }
            intent.voiceList.forEachIndexed { voiceIndex, voice ->
                updated.setVoice(voiceIndex, voice.toBuilder().setLocale(SodaLanguageAliases.SIMPLIFIED_CHINESE))
            }
            chipLabels[intent.name]?.let { label ->
                if (intent.hasChipLabel()) updated.chipLabel = label
                if (intent.hasAccessibleLabel()) updated.accessibleLabel = label
            }
            result.setIntents(index, updated)
        }
        return LocalizedSpecs(result.build().toByteArray(), recordings)
    }

    @Synchronized
    fun overlay(originalDirectory: File, originalBytes: ByteArray): File {
        val source = originalDirectory.canonicalFile
        require(source.isDirectory) { "Missing GACS resource directory" }
        val localized = localize(originalBytes)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update((VERSION + source.path).toByteArray())
        val key = digest.digest(localized.bytes).joinToString("") { "%02x".format(it) }.take(24)
        val destination = File(cacheDirectory, key)
        if (File(destination, ".ready").isFile &&
            File(destination, SPEC_FILE).isFile &&
            localized.recordings.keys.all { File(destination, it).length() > 44 }) return destination
        if (destination.exists()) deleteOverlay(destination)
        check(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) { "Cannot create GACS cache" }
        val temporary = Files.createTempDirectory(cacheDirectory.toPath(), "$key-").toFile()
        try {
            val replacements = localized.recordings.keys + SPEC_FILE
            mirror(source, temporary, "", replacements)
            File(temporary, SPEC_FILE).writeBytes(localized.bytes)
            localized.recordings.forEach { (path, action) ->
                val target = File(temporary, path)
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                openAsset("$ASSET_ROOT/$action.wav").use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                check(target.length() > 44) { "Empty Chinese recording: $action" }
            }
            File(temporary, ".ready").writeText(VERSION)
            check(temporary.renameTo(destination)) { "Cannot publish GACS overlay" }
        } finally {
            if (temporary.exists()) deleteOverlay(temporary)
        }
        return destination
    }

    private fun mirror(source: File, target: File, relative: String, replacements: Set<String>) {
        source.listFiles()?.forEach { child ->
            val path = if (relative.isEmpty()) child.name else "$relative/${child.name}"
            val copy = File(target, child.name)
            when {
                path in replacements -> Unit
                replacements.any { it.startsWith("$path/") } -> {
                    check(child.isDirectory) { "GACS asset path is not a directory: $path" }
                    check(copy.mkdir())
                    mirror(child, copy, path, replacements)
                }
                else -> Files.createSymbolicLink(copy.toPath(), child.toPath())
            }
        } ?: error("Cannot list GACS resource directory: $source")
    }

    private fun requireSafeRelativePath(path: String) {
        require(!File(path).isAbsolute && path.split('/').none { it == ".." || it.isEmpty() }) {
            "Invalid GACS recording path"
        }
    }

    private fun deleteOverlay(directory: File) {
        // Do not traverse links into Google's model store when removing a failed overlay.
        if (!Files.isSymbolicLink(directory.toPath()) && directory.isDirectory) {
            directory.listFiles()?.forEach(::deleteOverlay)
        }
        directory.delete()
    }
}

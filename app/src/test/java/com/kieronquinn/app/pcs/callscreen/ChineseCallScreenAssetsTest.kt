package com.kieronquinn.app.pcs.callscreen

import com.kieronquinn.app.pcs.model.gacs.GacsLocalization.RobotIntentSpecs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class ChineseCallScreenAssetsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture get() = javaClass.getResourceAsStream("/gacs/agentic_specs.pb")!!.readBytes()
    private val packaged = File("src/main/assets")
    private val wave = "RIFF".toByteArray() + ByteArray(124)

    private fun assets(failAudio: Boolean = false) = ChineseCallScreenAssets(temporary.newFolder()) { path ->
        when {
            path.endsWith("phrases.json") -> File(packaged, path).inputStream()
            failAudio -> error("Simulated interrupted asset extraction")
            else -> ByteArrayInputStream(wave)
        }
    }

    @Test fun translatesEveryCurrentActionAndPreservesTheProtocol() {
        val original = RobotIntentSpecs.parseFrom(fixture)
        val localized = assets().localize(fixture)
        val result = RobotIntentSpecs.parseFrom(localized.bytes)
        assertEquals(19, result.intentsCount)
        assertEquals(original.intentsList.map { it.name }, result.intentsList.map { it.name })
        assertEquals(19, localized.recordings.size)
        result.intentsList.zip(original.intentsList).forEach { (translated, source) ->
            assertEquals(source.speechList.map { it.recording.path }, translated.speechList.map { it.recording.path })
            translated.speechList.forEach {
                assertEquals("zh-CN", it.locale)
                assertTrue(it.recording.text.any { character -> character in '\u4e00'..'\u9fff' })
            }
            translated.voiceList.forEach { assertEquals("zh-CN", it.locale) }
        }
        // Neither field is declared in our partial schema; losing them breaks Dialer's config.
        assertTrue(String(localized.bytes).contains("agentic_call_screen"))
        assertTrue(String(localized.bytes).contains("Opening statement"))
        assertEquals("标记为骚扰", result.intentsList.first { it.name == "ReportSpamAndEndCallSpam" }.chipLabel)
    }

    @Test fun buildsAnOverlayWithoutChangingDownloadedResources() {
        val source = temporary.newFolder("source")
        File(source, ChineseCallScreenAssets.SPEC_FILE).writeBytes(fixture)
        File(source, "other-config.pb").writeText("original config")
        val original = RobotIntentSpecs.parseFrom(fixture)
        for (speech in original.intentsList.flatMap { it.speechList }) {
            File(source, speech.recording.path + ".wav").apply { parentFile!!.mkdirs(); writeText("original audio") }
        }
        val manager = assets()
        val overlay = manager.overlay(source, fixture)
        assertNotEquals(source.canonicalPath, overlay.canonicalPath)
        assertArrayEquals(fixture, File(source, ChineseCallScreenAssets.SPEC_FILE).readBytes())
        assertEquals("original audio", File(source, "askifsales/agentic_1.wav").readText())
        assertArrayEquals(wave, File(overlay, "askifsales/agentic_1.wav").readBytes())
        assertTrue(Files.isSymbolicLink(File(overlay, "other-config.pb").toPath()))
        assertEquals("original config", File(overlay, "other-config.pb").readText())
        assertEquals(overlay, manager.overlay(source, fixture))
    }

    @Test fun interruptedExtractionDoesNotDeleteLinkedSourceFiles() {
        val source = temporary.newFolder("source")
        File(source, "keep").mkdir()
        File(source, "keep/original.bin").writeText("keep me")
        val manager = assets(failAudio = true)
        assertThrows(IllegalStateException::class.java) { manager.overlay(source, fixture) }
        assertEquals("keep me", File(source, "keep/original.bin").readText())
    }

    @Test fun rejectsPathsThatCouldEscapeTheOverlay() {
        val original = RobotIntentSpecs.parseFrom(fixture).toBuilder()
        val intent = original.getIntents(0).toBuilder()
        val speech = intent.getSpeech(0).toBuilder()
        speech.recording = speech.recording.toBuilder().setPath("../../outside").build()
        intent.setSpeech(0, speech)
        original.setIntents(0, intent)
        assertThrows(IllegalArgumentException::class.java) { assets().localize(original.build().toByteArray()) }
    }

    @Test fun rejectsUnsupportedNewActionsInsteadOfMixingLanguages() {
        val original = RobotIntentSpecs.parseFrom(fixture).toBuilder()
        original.setIntents(0, original.getIntents(0).toBuilder().setName("UnknownNewAction"))
        assertThrows(IllegalStateException::class.java) { assets().localize(original.build().toByteArray()) }
    }
    @Test fun repairsAMissingRecordingWithoutChangingTheSource() {
        val source = temporary.newFolder("source")
        File(source, "keep.pb").writeText("original")
        val manager = assets()
        val overlay = manager.overlay(source, fixture)
        File(overlay, "askifsales/agentic_1.wav").delete()
        val repaired = manager.overlay(source, fixture)
        assertArrayEquals(wave, File(repaired, "askifsales/agentic_1.wav").readBytes())
        assertEquals("original", File(source, "keep.pb").readText())
    }

    @Test fun bundlesCompleteMonoPcmRecordingsForEveryAction() {
        val actions = assets().localize(fixture).recordings.values.toSet()
        assertEquals(19, actions.size)
        actions.forEach { action ->
            val bytes = File(packaged, "callscreen/zh-CN/$action.wav").readBytes()
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals("WAVE", String(bytes, 8, 4))
            var offset = 12
            var audioBytes = 0
            while (offset + 8 <= bytes.size) {
                val tag = String(bytes, offset, 4)
                val size = buffer.getInt(offset + 4)
                assertTrue(size >= 0 && offset + 8L + size <= bytes.size)
                if (tag == "fmt ") {
                    assertEquals(1, buffer.getShort(offset + 8).toInt())
                    assertEquals(1, buffer.getShort(offset + 10).toInt())
                    assertEquals(16000, buffer.getInt(offset + 12))
                    assertEquals(16, buffer.getShort(offset + 22).toInt())
                }
                if (tag == "data") audioBytes += size
                offset += 8 + size + size % 2
            }
            assertTrue("$action is empty", audioBytes > 3200)
        }
    }

}

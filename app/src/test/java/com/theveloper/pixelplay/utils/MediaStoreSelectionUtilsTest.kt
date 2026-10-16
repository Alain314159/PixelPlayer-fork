package com.theveloper.pixelplay.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MediaStoreSelectionUtils].
 *
 * Verifies that the MediaStore selection query correctly filters audio files
 * and includes files with unreliable duration metadata (MIDI, ALAC/M4A, WMA).
 */
class MediaStoreSelectionUtilsTest {

    @Test
    fun `selection includes duration filter`() {
        val (selection, _) = buildLocalAudioSelection(minDurationMs = 10000)
        assertTrue(
            "Selection should include DURATION filter",
            selection.contains("DURATION >= ?")
        )
    }

    @Test
    fun `selection includes MIDI MIME bypass`() {
        val (selection, _) = buildLocalAudioSelection(minDurationMs = 10000)
        assertTrue(
            "Selection should include MIDI MIME bypass",
            selection.contains("audio/midi")
        )
    }

    @Test
    fun `selection includes ALAC M4A bypass (#2779)`() {
        val (selection, _) = buildLocalAudioSelection(minDurationMs = 10000)
        assertTrue(
            "Selection should include M4A MIME bypass for ALAC (#2779)",
            selection.contains("audio/m4a") || selection.contains("audio/x-m4a")
        )
    }

    @Test
    fun `selection includes WMA bypass (#2893)`() {
        val (selection, _) = buildLocalAudioSelection(minDurationMs = 10000)
        assertTrue(
            "Selection should include WMA MIME bypass (#2893)",
            selection.contains("audio/x-ms-wma") || selection.contains("audio/wma")
        )
    }

    @Test
    fun `selection includes M4A extension bypass`() {
        val (selection, _) = buildLocalAudioSelection(minDurationMs = 10000)
        assertTrue(
            "Selection should include .m4a extension bypass",
            selection.contains("%.m4a")
        )
    }

    @Test
    fun `selection includes WMA extension bypass`() {
        val (selection, _) = buildLocalAudioSelection(minDurationMs = 10000)
        assertTrue(
            "Selection should include .wma extension bypass",
            selection.contains("%.wma")
        )
    }

    @Test
    fun `selection excludes files without title`() {
        val (selection, _) = buildLocalAudioSelection(minDurationMs = 10000)
        assertTrue(
            "Selection should require non-empty TITLE",
            selection.contains("TITLE") && selection.contains("!=''")
        )
    }

    @Test
    fun `selection requires DATA not null`() {
        val (selection, _) = buildLocalAudioSelection(minDurationMs = 10000)
        assertTrue(
            "Selection should require DATA IS NOT NULL",
            selection.contains("DATA IS NOT NULL")
        )
    }

    @Test
    fun `selection args include min duration as first arg`() {
        val (_, args) = buildLocalAudioSelection(minDurationMs = 30000)
        assertEquals("30000", args[0])
    }

    @Test
    fun `min duration of 0 is clamped to 0`() {
        val (_, args) = buildLocalAudioSelection(minDurationMs = -1)
        assertEquals("0", args[0])
    }

    @Test
    fun `selection args include all MIME types`() {
        val (_, args) = buildLocalAudioSelection(minDurationMs = 10000)
        // First arg is duration, then MIME types
        assertTrue("Should have audio/midi in args", args.contains("audio/midi"))
        assertTrue("Should have audio/m4a in args", args.contains("audio/m4a"))
        assertTrue("Should have audio/x-ms-wma in args", args.contains("audio/x-ms-wma"))
    }

    @Test
    fun `selection args include all extensions`() {
        val (_, args) = buildLocalAudioSelection(minDurationMs = 10000)
        assertTrue("Should have %.mid in args", args.contains("%.mid"))
        assertTrue("Should have %.m4a in args", args.contains("%.m4a"))
        assertTrue("Should have %.wma in args", args.contains("%.wma"))
    }
}

package com.theveloper.pixelplay.data.service

import com.theveloper.pixelplay.data.service.player.PlaybackConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PlaybackConstants].
 *
 * Verifies that the shared constants are sane and consistent — these values
 * are used across DualPlayerEngine, ReplayGainProcessor, and MusicService, so
 * a wrong value here would affect multiple components.
 */
class PlaybackConstantsTest {

    @Test
    fun `REPLAYGAIN_DEFAULT_VOLUME is between 0 and 1`() {
        val v = PlaybackConstants.REPLAYGAIN_DEFAULT_VOLUME
        assertTrue("Default RG volume should be > 0, got $v", v > 0f)
        assertTrue("Default RG volume should be <= 1, got $v", v <= 1f)
    }

    @Test
    fun `REPLAYGAIN_MAX_VOLUME is greater than 1 to allow positive gain`() {
        assertTrue(
            "Max RG volume should be > 1 (to amplify quiet tracks), got ${PlaybackConstants.REPLAYGAIN_MAX_VOLUME}",
            PlaybackConstants.REPLAYGAIN_MAX_VOLUME > 1f
        )
    }

    @Test
    fun `REPLAYGAIN_MIN_VOLUME is positive but small`() {
        assertTrue(
            "Min RG volume should be > 0, got ${PlaybackConstants.REPLAYGAIN_MIN_VOLUME}",
            PlaybackConstants.REPLAYGAIN_MIN_VOLUME > 0f
        )
        assertTrue(
            "Min RG volume should be < 0.5 (for very negative gain tracks), got ${PlaybackConstants.REPLAYGAIN_MIN_VOLUME}",
            PlaybackConstants.REPLAYGAIN_MIN_VOLUME < 0.5f
        )
    }

    @Test
    fun `REPLAYGAIN_DEFAULT_VOLUME is conservative (less than full volume)`() {
        // Bug #2857 fix: the default should be < 1.0 to prevent a full-volume spike
        // before RG tags are read. 0.85 is roughly -1.4 dB.
        assertTrue(
            "Default RG volume should be < 1.0 to prevent full-volume spike, got ${PlaybackConstants.REPLAYGAIN_DEFAULT_VOLUME}",
            PlaybackConstants.REPLAYGAIN_DEFAULT_VOLUME < 1.0f
        )
    }

    @Test
    fun `crossfade constants are ordered correctly`() {
        assertTrue(PlaybackConstants.MIN_CROSSFADE_DURATION_MS <= PlaybackConstants.DEFAULT_CROSSFADE_DURATION_MS)
        assertTrue(PlaybackConstants.DEFAULT_CROSSFADE_DURATION_MS <= PlaybackConstants.MAX_CROSSFADE_DURATION_MS)
    }

    @Test
    fun `buffer constants are ordered correctly`() {
        assertTrue(PlaybackConstants.LOW_RAM_MIN_BUFFER_MS < PlaybackConstants.LOW_RAM_MAX_BUFFER_MS)
        assertTrue(PlaybackConstants.STANDARD_MIN_BUFFER_MS < PlaybackConstants.STANDARD_MAX_BUFFER_MS)
        assertTrue(PlaybackConstants.LOW_RAM_MAX_BUFFER_MS <= PlaybackConstants.STANDARD_MAX_BUFFER_MS)
    }

    @Test
    fun `snapshot resume window is 5 minutes`() {
        assertEquals(5L * 60 * 1000, PlaybackConstants.SNAPSHOT_RESUME_WINDOW_MS)
    }

    @Test
    fun `shuffle all limit is at least 10000`() {
        // Bug fix: was 500, raised to 10000 in a previous commit
        assertTrue(
            "Shuffle all limit should be >= 10000, got ${PlaybackConstants.SHUFFLE_ALL_LIMIT}",
            PlaybackConstants.SHUFFLE_ALL_LIMIT >= 10_000
        )
    }

    @Test
    fun `notification artwork size is 768 (reduced from 1024)`() {
        // Bug fix: was 1024, reduced to 768 to halve peak heap allocation
        assertEquals(768, PlaybackConstants.MAX_NOTIFICATION_ARTWORK_SIZE_PX)
    }
}

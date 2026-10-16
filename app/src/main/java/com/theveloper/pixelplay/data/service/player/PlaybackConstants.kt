/*
 * Copyright (C) 2026 PixelPlayer
 *
 * Shared constants for playback-related components.
 *
 * Extracted to avoid magic numbers scattered across DualPlayerEngine,
 * MusicService, ReplayGainProcessor, and PlaybackStateHolder.
 */
package com.theveloper.pixelplay.data.service.player

/**
 * Playback-related constants used across multiple components.
 *
 * Centralized here to avoid magic numbers in multiple files and to make
 * tuning easier (all performance/behavior knobs in one place).
 */
object PlaybackConstants {

    // ─── Crossfade ──────────────────────────────────────────────────────────

    /** Default crossfade duration in milliseconds if user hasn't configured one. */
    const val DEFAULT_CROSSFADE_DURATION_MS = 5_000L

    /** Minimum crossfade duration (effectively gapless). */
    const val MIN_CROSSFADE_DURATION_MS = 0L

    /** Maximum crossfade duration. */
    const val MAX_CROSSFADE_DURATION_MS = 12_000L

    /** Step size for crossfade duration slider in settings. */
    const val CROSSFADE_STEP_MS = 500L

    /** When the remaining time is below this threshold, the crossfade starts. */
    const val CROSSFADE_TRIGGER_THRESHOLD_MS = 8_000L

    // ─── ReplayGain ──────────────────────────────────────────────────────────

    /**
     * Default volume multiplier when ReplayGain is enabled but no tag has been
     * read yet. This is a conservative value that prevents the full-volume
     * spike reported in bugs #2857 and #2849.
     *
     * The value 0.85 is chosen because:
     * - Most ReplayGain adjustments are -6 to +3 dB, which maps to 0.5x–1.4x
     * - A typical track with no RG tag plays at 1.0x; with RG enabled, we start
     *   at 0.85x (roughly -1.4 dB) to be slightly conservative
     * - Once the RG tag is read (1-2s later), the volume is corrected to the
     *   exact value. The brief 0.85x is far less jarring than a 1.0x spike
     *   followed by a sudden drop to 0.6x.
     */
    const val REPLAYGAIN_DEFAULT_VOLUME = 0.85f

    /** Maximum volume multiplier for ReplayGain (prevents clipping on +gain tracks). */
    const val REPLAYGAIN_MAX_VOLUME = 1.5f

    /** Minimum volume multiplier for ReplayGain (prevents near-silence on very negative gain). */
    const val REPLAYGAIN_MIN_VOLUME = 0.1f

    // ─── Queue ───────────────────────────────────────────────────────────────

    /** Maximum number of songs to shuffle when "Shuffle All" is tapped. */
    const val SHUFFLE_ALL_LIMIT = 10_000

    /** Number of songs to load around the current position for Cast remote queue. */
    const val CAST_REMOTE_QUEUE_WINDOW = 500

    /** Pause-restore queue limit: only prepare() the full queue if it's this size or smaller. */
    const val PAUSED_RESTORE_PREPARE_QUEUE_LIMIT = 50

    // ─── Buffer ─────────────────────────────────────────────────────────────

    /** ExoPlayer min buffer duration for low-RAM devices (15s). */
    const val LOW_RAM_MIN_BUFFER_MS = 15_000L

    /** ExoPlayer max buffer duration for low-RAM devices (30s). */
    const val LOW_RAM_MAX_BUFFER_MS = 30_000L

    /** ExoPlayer min buffer duration for standard devices (30s). */
    const val STANDARD_MIN_BUFFER_MS = 30_000L

    /** ExoPlayer max buffer duration for standard devices (60s). */
    const val STANDARD_MAX_BUFFER_MS = 60_000L

    /** ExoPlayer for-playback buffer (how much must be buffered before playback starts). */
    const val FOR_PLAYBACK_BUFFER_MS = 2_500L

    /** ExoPlayer for-rebuffer buffer (how much must be buffered after a rebuffer). */
    const val FOR_REBUFFER_BUFFER_MS = 5_000L

    // ─── Snapshot persistence ────────────────────────────────────────────────

    /** Debounce for playback snapshot persistence (avoids writing on every position tick). */
    const val PLAYBACK_SNAPSHOT_DEBOUNCE_MS = 1_500L

    /** Only auto-resume playback if the snapshot was saved within this window. */
    const val SNAPSHOT_RESUME_WINDOW_MS = 5L * 60 * 1000 // 5 minutes

    // ─── Lyrics ─────────────────────────────────────────────────────────────

    /** Maximum number of lyric lines to send to Wear OS (to avoid exceeding data layer limits). */
    const val MAX_WEAR_LYRIC_LINES = 180

    /** Interval at which the lyrics position is updated (ms). */
    const val LYRICS_UPDATE_INTERVAL_MS = 250L

    // ─── Album art ──────────────────────────────────────────────────────────

    /** Maximum dimension for cached album art (pixels). */
    const val MAX_CACHED_ART_DIMENSION_PX = 1536

    /** Maximum size for cached album art (bytes) before it's considered oversized. */
    const val OVERSIZED_CACHED_ART_BYTES = 6 * 1024 * 1024 // 6 MB

    /** JPEG quality for cached album art. */
    const val CACHED_ART_JPEG_QUALITY = 85

    /** Maximum dimension for notification / MediaSession artwork (pixels). */
    const val MAX_NOTIFICATION_ARTWORK_SIZE_PX = 768

    /** Maximum artwork bytes to transfer to Wear OS. */
    const val TRANSFER_ARTWORK_MAX_BYTES = 1_500_000
}

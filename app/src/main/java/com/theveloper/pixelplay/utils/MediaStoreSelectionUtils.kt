package com.theveloper.pixelplay.utils

import android.provider.MediaStore

private val MIDI_MIME_SELECTION_ARGS = arrayOf(
    "audio/midi",
    "audio/x-midi",
    "audio/sp-midi",
    "audio/x-mid"
)
private val MIDI_EXTENSION_SELECTION_ARGS = arrayOf(
    "%.mid",
    "%.midi"
)

/**
 * MIME types that should bypass the minimum-duration filter because some
 * devices/scanners report incorrect or zero durations for these formats.
 *
 * BUG FIX (#2779): ALAC (.m4a with ALAC codec) files on Samsung devices
 * are sometimes indexed with DURATION=0 because the MediaStore scanner
 * doesn't fully parse the ALAC atom. Without this bypass, songs shorter
 * than the min-duration threshold (default 10s) or with DURATION=0 are
 * silently filtered out of the library.
 *
 * BUG FIX (#2893): WMA files similarly have unreliable duration metadata
 * on some devices. Adding them to the bypass list ensures WMA files are
 * always included in the library regardless of their reported duration.
 */
private val UNRELIABLE_DURATION_MIME_ARGS = arrayOf(
    "audio/midi",
    "audio/x-midi",
    "audio/sp-midi",
    "audio/x-mid",
    "audio/mp4",           // ALAC in M4A container (#2779)
    "audio/m4a",           // ALAC/M4A alternate MIME
    "audio/x-m4a",          // ALAC/M4A alternate MIME
    "audio/x-ms-wma",       // WMA (#2893)
    "audio/wma"             // WMA alternate MIME
)

private val UNRELIABLE_DURATION_EXTENSION_ARGS = arrayOf(
    "%.mid",
    "%.midi",
    "%.m4a",     // ALAC in M4A container (#2779)
    "%.alac",    // raw ALAC
    "%.wma"      // WMA (#2893)
)

/**
 * Builds the baseline MediaStore selection for user-facing local audio.
 *
 * We intentionally do not rely on [MediaStore.Audio.Media.IS_MUSIC] here because some devices
 * and scanners leave valid songs flagged as non-music, which makes library sync and folder
 * browsing appear to "cap out" below the real file count for specific users.
 *
 * MIDI files may be indexed with incomplete duration metadata, so explicit MIDI MIME/path
 * matches bypass the duration floor and are left to playback capability checks.
 *
 * Additionally, ALAC (.m4a) and WMA (.wma) files bypass the duration filter because
 * some device scanners report incorrect durations for these formats (see #2779, #2893).
 */
fun buildLocalAudioSelection(minDurationMs: Int): Pair<String, Array<String>> {
    val clampedMinDurationMs = minDurationMs.coerceAtLeast(0)
    val mimePlaceholders = UNRELIABLE_DURATION_MIME_ARGS.joinToString(",") { "?" }
    val extensionSelection = UNRELIABLE_DURATION_EXTENSION_ARGS.joinToString(" OR ") {
        "LOWER(${MediaStore.Audio.Media.DATA}) LIKE ?"
    }
    val selection = buildString {
        append("(")
        append("${MediaStore.Audio.Media.DURATION} >= ?")
        append(" OR LOWER(COALESCE(${MediaStore.Audio.Media.MIME_TYPE}, '')) IN ($mimePlaceholders)")
        append(" OR $extensionSelection")
        append(")")
        append(" AND COALESCE(${MediaStore.Audio.Media.TITLE}, '') != ''")
        append(" AND ${MediaStore.Audio.Media.DATA} IS NOT NULL")
    }
    return selection to arrayOf(clampedMinDurationMs.toString()) +
        UNRELIABLE_DURATION_MIME_ARGS +
        UNRELIABLE_DURATION_EXTENSION_ARGS
}

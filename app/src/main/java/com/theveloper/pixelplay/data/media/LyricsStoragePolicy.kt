package com.theveloper.pixelplay.data.media

object LyricsStoragePolicy {
    const val MAX_LYRICS_LENGTH = 50_000

    fun normalize(rawLyrics: String?): String? {
        val trimmed = rawLyrics?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return trimmed.takeIf(::canStore)
    }

    fun canStore(rawLyrics: String?): Boolean {
        val normalizedLength = rawLyrics?.trim()?.length ?: return false
        return normalizedLength in 1..MAX_LYRICS_LENGTH
    }
}

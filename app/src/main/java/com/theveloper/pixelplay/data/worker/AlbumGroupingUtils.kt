package com.theveloper.pixelplay.data.worker

import com.theveloper.pixelplay.data.database.AlbumEntity
import com.theveloper.pixelplay.data.database.SongEntity
import com.theveloper.pixelplay.utils.LocalArtworkUri
import com.theveloper.pixelplay.utils.normalizeMetadataText
import com.theveloper.pixelplay.utils.normalizeMetadataTextOrEmpty
import com.theveloper.pixelplay.utils.splitArtistsByDelimiters

internal data class AlbumGroupingKey(
    val normalizedTitle: String,
    val identity: String
)

internal fun resolveAlbumArtist(
    rawAlbumArtist: String?,
    metadataAlbumArtist: String?
): String? {
    return sequenceOf(metadataAlbumArtist, rawAlbumArtist)
        .mapNotNull { candidate ->
            candidate.normalizeMetadataText()
                ?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
        }
        .firstOrNull()
}

internal fun buildAlbumGroupingKey(song: SongEntity): AlbumGroupingKey {
    return buildAlbumGroupingKey(
        albumName = song.albumName,
        albumArtist = song.albumArtist,
        albumArtUriString = song.albumArtUriString,
        parentDirectoryPath = song.parentDirectoryPath,
        fallbackAlbumId = song.albumId,
        preferStableLocalIdentity = LocalArtworkUri.isLikelyLocalMedia(song.contentUriString)
    )
}

/**
 * Generate multiple grouping keys for a song to maximize matching.
 *
 * BUG FIX (#2804, #2745): 'Same Named Albums show as separate albums.'
 * Songs with the same album name but different albumArtist tags were being
 * grouped into separate albums because the primary grouping key uses
 * albumArtist as the identity. This generates an additional key that
 * uses only the directory + album name (ignoring albumArtist), so songs
 * in the same folder get grouped together regardless of albumArtist.
 */
internal fun buildAlbumGroupingKeysForSong(song: SongEntity): List<AlbumGroupingKey> {
    val keys = mutableListOf<AlbumGroupingKey>()

    // Primary key: uses albumArtist as identity (original behavior)
    keys += buildAlbumGroupingKey(song)

    // Fallback key: uses directory only (ignores albumArtist)
    // This groups songs in the same folder with the same album name together
    // even if they have different albumArtist tags (#2804, #2745)
    if (!song.parentDirectoryPath.isNullOrBlank()) {
        keys += AlbumGroupingKey(
            normalizedTitle = song.albumName.normalizeMetadataTextOrEmpty()
                .ifBlank { "Unknown Album" }
                .lowercase(),
            identity = "dir:${song.parentDirectoryPath.trim().lowercase()}"
        )
    }

    // Fallback key: uses album name only (weakest match — only if no other key matches)
    keys += AlbumGroupingKey(
        normalizedTitle = song.albumName.normalizeMetadataTextOrEmpty()
            .ifBlank { "Unknown Album" }
            .lowercase(),
        identity = "name-only"
    )

    return keys.distinct()
}

internal fun buildAlbumGroupingKeys(album: AlbumEntity): List<AlbumGroupingKey> {
    val albumKeys = mutableListOf<AlbumGroupingKey>()

    albumKeys += buildAlbumGroupingKey(
        albumName = album.title,
        albumArtist = album.artistName,
        albumArtUriString = album.albumArtUriString,
        parentDirectoryPath = null,
        fallbackAlbumId = album.id
    )

    albumKeys += AlbumGroupingKey(
        normalizedTitle = album.title.normalizeMetadataTextOrEmpty()
            .ifBlank { "Unknown Album" }
            .lowercase(),
        identity = "media:${album.id}"
    )

    if (!album.albumArtUriString.isNullOrBlank()) {
        albumKeys += AlbumGroupingKey(
            normalizedTitle = album.title.normalizeMetadataTextOrEmpty()
                .ifBlank { "Unknown Album" }
                .lowercase(),
            identity = "art:${album.albumArtUriString.trim()}"
        )
    }

    return albumKeys.distinct()
}

internal fun chooseAlbumDisplayArtist(
    songs: List<SongEntity>,
    preferAlbumArtist: Boolean,
    artistDelimiters: List<String> = emptyList(),
    wordDelimiters: List<String> = emptyList()
): String {
    if (songs.isEmpty()) return "Unknown Artist"

    val albumArtist = mostCommonValue(
        songs.mapNotNull { song ->
            song.albumArtist.normalizeMetadataText()?.takeIf { it.isNotBlank() }
        }
    )
    val trackArtist = mostCommonValue(
        songs.map { song ->
            collectArtistNames(
                rawArtistName = song.artistName,
                title = song.title,
                artistDelimiters = artistDelimiters,
                wordDelimiters = wordDelimiters,
                extractFromTitle = true
            ).firstOrNull().normalizeMetadataTextOrEmpty()
        }
    )

    return when {
        preferAlbumArtist && albumArtist != null -> albumArtist
        trackArtist != null -> trackArtist
        albumArtist != null -> albumArtist
        else -> "Unknown Artist"
    }
}

internal fun resolveAlbumDisplayArtistId(
    displayArtist: String,
    songs: List<SongEntity>,
    artistNameToId: Map<String, Long>,
    artistDelimiters: List<String>,
    wordDelimiters: List<String> = emptyList()
): Long {
    artistNameToId[displayArtist.trim()]?.let { return it }

    val primaryArtistName = displayArtist
        .splitArtistsByDelimiters(artistDelimiters, wordDelimiters)
        .firstOrNull()
        ?.trim()
    if (!primaryArtistName.isNullOrEmpty()) {
        artistNameToId[primaryArtistName]?.let { return it }
    }

    return songs.firstOrNull()?.artistId ?: 0L
}

private fun mostCommonValue(values: List<String>): String? {
    return values
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .groupingBy { it }
        .eachCount()
        .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
        ?.key
}

private fun buildAlbumGroupingKey(
    albumName: String,
    albumArtist: String?,
    albumArtUriString: String?,
    parentDirectoryPath: String?,
    fallbackAlbumId: Long,
    preferStableLocalIdentity: Boolean = false
): AlbumGroupingKey {
    val normalizedTitle = albumName.normalizeMetadataTextOrEmpty()
        .ifBlank { "Unknown Album" }
        .lowercase()
    val stableLocalIdentity = when {
        !parentDirectoryPath.isNullOrBlank() -> {
            "dir:${parentDirectoryPath.trim().lowercase()}"
        }
        else -> {
            "media:$fallbackAlbumId"
        }
    }
    val identity = when {
        !albumArtist.isNullOrBlank() -> {
            "artist:${albumArtist.normalizeMetadataTextOrEmpty().lowercase()}"
        }
        preferStableLocalIdentity -> {
            stableLocalIdentity
        }
        !albumArtUriString.isNullOrBlank() -> {
            "art:${albumArtUriString.trim()}"
        }
        !parentDirectoryPath.isNullOrBlank() -> {
            "dir:${parentDirectoryPath.trim().lowercase()}"
        }
        else -> {
            "media:$fallbackAlbumId"
        }
    }

    return AlbumGroupingKey(
        normalizedTitle = normalizedTitle,
        identity = identity
    )
}

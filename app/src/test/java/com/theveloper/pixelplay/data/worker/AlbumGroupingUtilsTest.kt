package com.theveloper.pixelplay.data.worker

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.database.SongEntity
import org.junit.Test

class AlbumGroupingUtilsTest {

    @Test
    fun `resolveAlbumArtist prefers embedded album artist`() {
        val resolved = resolveAlbumArtist(
            rawAlbumArtist = null,
            metadataAlbumArtist = "The Weeknd"
        )

        assertThat(resolved).isEqualTo("The Weeknd")
    }

    @Test
    fun `buildAlbumGroupingKey ignores per-track artist when title and art match`() {
        val soloTrack = testSong(
            artistName = "The Weeknd",
            albumArtist = null,
            albumArtUriString = "content://art/hurry-up",
            parentDirectoryPath = "/music/The Weeknd & Justice - Hurry Up Tomorrow"
        )
        val collabTrack = testSong(
            artistName = "The Weeknd, Justice",
            albumArtist = null,
            albumArtUriString = "content://art/hurry-up",
            parentDirectoryPath = "/music/The Weeknd & Justice - Hurry Up Tomorrow"
        )

        assertThat(buildAlbumGroupingKey(soloTrack)).isEqualTo(buildAlbumGroupingKey(collabTrack))
    }

    @Test
    fun `buildAlbumGroupingKey keeps same-titled albums apart when directories differ`() {
        val firstAlbum = testSong(
            artistName = "Artist A",
            albumArtist = null,
            albumArtUriString = null,
            parentDirectoryPath = "/music/Artist A/Greatest Hits"
        )
        val secondAlbum = testSong(
            artistName = "Artist B",
            albumArtist = null,
            albumArtUriString = null,
            parentDirectoryPath = "/music/Artist B/Greatest Hits"
        )

        assertThat(buildAlbumGroupingKey(firstAlbum)).isNotEqualTo(buildAlbumGroupingKey(secondAlbum))
    }

    @Test
    fun `buildAlbumGroupingKey ignores reused artwork for local same-titled albums`() {
        val firstAlbum = testSong(
            artistName = "Artist A",
            albumArtist = null,
            albumArtUriString = "pixelplay_local_art://song/10",
            parentDirectoryPath = "/music/Artist A/Feels",
            albumName = "Unknown Album",
            albumId = 10L
        )
        val secondAlbum = testSong(
            artistName = "Artist B",
            albumArtist = null,
            albumArtUriString = "pixelplay_local_art://song/10",
            parentDirectoryPath = "/music/Artist B/Feels",
            albumName = "Unknown Album",
            albumId = 11L
        )

        assertThat(buildAlbumGroupingKey(firstAlbum)).isNotEqualTo(buildAlbumGroupingKey(secondAlbum))
    }

    @Test
    fun `buildAlbumGroupingKeys keeps media fallback even when artwork exists`() {
        val album = com.theveloper.pixelplay.data.database.AlbumEntity(
            id = 77L,
            title = "Unknown Album",
            artistName = "",
            artistId = 0L,
            albumArtUriString = "pixelplay_local_art://song/10",
            songCount = 1,
            dateAdded = 0L,
            year = 0
        )

        val keys = buildAlbumGroupingKeys(album)

        assertThat(keys).contains(
            AlbumGroupingKey(
                normalizedTitle = "unknown album",
                identity = "media:77"
            )
        )
    }

    @Test
    fun `chooseAlbumDisplayArtist prefers dominant track artist when grouping is off`() {
        val songs = listOf(
            testSong(artistName = "The Weeknd", albumArtist = "The Weeknd & Justice"),
            testSong(artistName = "The Weeknd", albumArtist = "The Weeknd & Justice"),
            testSong(artistName = "The Weeknd, Justice", albumArtist = "The Weeknd & Justice")
        )

        val displayArtist = chooseAlbumDisplayArtist(
            songs = songs,
            preferAlbumArtist = false
        )

        assertThat(displayArtist).isEqualTo("The Weeknd")
    }

    @Test
    fun `chooseAlbumDisplayArtist uses primary parsed artist for feature-heavy albums`() {
        val songs = listOf(
            testSong(artistName = "Gorillaz feat. Stevie Nicks", albumArtist = null),
            testSong(artistName = "Gorillaz feat. Thundercat", albumArtist = null),
            testSong(artistName = "Gorillaz feat. Tame Impala", albumArtist = null)
        )

        val displayArtist = chooseAlbumDisplayArtist(
            songs = songs,
            preferAlbumArtist = false,
            artistDelimiters = listOf(";"),
            wordDelimiters = listOf("feat.")
        )

        assertThat(displayArtist).isEqualTo("Gorillaz")
    }

    @Test
    fun `chooseAlbumDisplayArtist prefers album artist when grouping is on`() {
        val songs = listOf(
            testSong(artistName = "The Weeknd", albumArtist = "The Weeknd & Justice"),
            testSong(artistName = "The Weeknd, Justice", albumArtist = "The Weeknd & Justice")
        )

        val displayArtist = chooseAlbumDisplayArtist(
            songs = songs,
            preferAlbumArtist = true
        )

        assertThat(displayArtist).isEqualTo("The Weeknd & Justice")
    }

    @Test
    fun `songs with same albumId but different album names produce different grouping keys`() {
        // Simulates the scenario where MediaStore labels all songs under "Music"
        // (same albumId) but the user renames them to proper album titles.
        val songA = testSong(
            artistName = "Michael Jackson",
            albumArtist = null,
            albumName = "Thriller",
            albumId = 42L,
            parentDirectoryPath = "/storage/emulated/0/Music"
        )
        val songB = testSong(
            artistName = "The Beatles",
            albumArtist = null,
            albumName = "Abbey Road",
            albumId = 42L,
            parentDirectoryPath = "/storage/emulated/0/Music"
        )

        val keyA = buildAlbumGroupingKey(songA)
        val keyB = buildAlbumGroupingKey(songB)

        // Different album names must produce different keys even with the same albumId
        assertThat(keyA).isNotEqualTo(keyB)
        assertThat(keyA.normalizedTitle).isEqualTo("thriller")
        assertThat(keyB.normalizedTitle).isEqualTo("abbey road")
    }

    @Test
    fun `songs with same album name and same directory produce same grouping key`() {
        val songA = testSong(
            artistName = "Michael Jackson",
            albumArtist = null,
            albumName = "Thriller",
            albumId = 42L,
            parentDirectoryPath = "/storage/emulated/0/Music/Michael Jackson"
        )
        val songB = testSong(
            artistName = "Michael Jackson",
            albumArtist = null,
            albumName = "Thriller",
            albumId = 42L,
            parentDirectoryPath = "/storage/emulated/0/Music/Michael Jackson",
            songId = 2L
        )

        assertThat(buildAlbumGroupingKey(songA)).isEqualTo(buildAlbumGroupingKey(songB))
    }

    private fun testSong(
        artistName: String,
        albumArtist: String?,
        albumArtUriString: String? = null,
        parentDirectoryPath: String = "/music/default",
        albumName: String = "Hurry Up Tomorrow",
        albumId: Long = 42L,
        songId: Long = albumId
    ): SongEntity {
        return SongEntity(
            id = songId,
            title = "Track",
            artistName = artistName,
            artistId = 1L,
            albumArtist = albumArtist,
            albumName = albumName,
            albumId = albumId,
            contentUriString = "content://media/$songId",
            albumArtUriString = albumArtUriString,
            duration = 180_000L,
            genre = null,
            filePath = "$parentDirectoryPath/track.flac",
            parentDirectoryPath = parentDirectoryPath
        )
    }
}

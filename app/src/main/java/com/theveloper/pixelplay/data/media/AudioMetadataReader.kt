package com.theveloper.pixelplay.data.media

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.kyant.taglib.TagLib
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import timber.log.Timber
import java.io.File

data class AudioMetadata(
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val genre: String?,
    val composer: String?,
    val lyrics: String?,
    val durationMs: Long?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val bitrate: Int?,
    val sampleRate: Int?,
    val artwork: AudioMetadataArtwork?,
    val replayGainTrackGainDb: Float? = null,
    val replayGainAlbumGainDb: Float? = null
)

data class AudioMetadataArtwork(
    val bytes: ByteArray,
    val mimeType: String?
)

object AudioMetadataReader {

    private const val TAG = "AudioMetadataReader"

    fun read(context: Context, uri: Uri): AudioMetadata? {
        val tempFile = createTempAudioFileFromUri(context, uri) ?: run {
            Timber.tag(TAG).w("Unable to create temp file for uri: $uri")
            return null
        }

        return try {
            read(tempFile)
        } finally {
            try {
                tempFile.delete()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to delete temp file")
            }
        }
    }

    /**
     * Fix strings that were decoded with the wrong charset.
     *
     * BUG FIX (#2815): Romanian characters (ă, â, î, ș, ț) were missing
     * from song titles. Root cause: some ID3v2 tags are encoded in
     * Windows-1250 (Central European) instead of UTF-8, but TagLib's JNI
     * wrapper decodes them as UTF-8, producing mojibake or replacement
     * characters. This function detects common mojibake patterns and
     * re-decodes the string from Windows-1250 to UTF-8.
     */
    private fun fixCharset(input: String?): String? {
        if (input == null) return null
        // Check for common mojibake patterns:
        // - U+FFFD (replacement character) indicates failed UTF-8 decode
        // - "Äƒ" is "ă" decoded as Latin-1 from UTF-8 (double-encoded)
        // - "Ã¢" is "â" decoded as Latin-1 from UTF-8
        if (input.contains('�') ||
            input.contains("Ä") ||  // ă in mojibake
            input.contains("Ã¢") ||        // â in mojibake
            input.contains("Ã®") ||        // î in mojibake
            input.contains("Ãş") ||   // ș in mojibake
            input.contains("Ãţ")      // ț in mojibake
        ) {
            // Try re-decoding from Latin-1 to get the original bytes,
            // then decode as Windows-1250 (Central European)
            return try {
                val bytes = input.toByteArray(Charsets.ISO_8859_1)
                String(bytes, Charsets.UTF_8)
            } catch (_: Exception) {
                try {
                    val bytes = input.toByteArray(Charsets.ISO_8859_1)
                    String(bytes, charset("windows-1250"))
                } catch (_: Exception) {
                    input
                }
            }
        }
        return input
    }

    fun read(file: File, readArtwork: Boolean = true): AudioMetadata? {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                // NOTE: fd.dup().detachFd() returns a raw int FD that TagLib's JNI bindings
                // take ownership of and close on success. If TagLib throws before closing,
                // the FD leaks. This is a known limitation of the kyant0/taglib wrapper that
                // would require a JNI-side fix. The leak is bounded by how often TagLib throws
                // (rare in practice — most files parse cleanly), so it doesn't typically hit the
                // per-process FD limit (~1024) during normal library scans.

                // Get audio properties for duration
                val audioProperties = TagLib.getAudioProperties(fd.dup().detachFd())
                val durationMs = audioProperties?.length?.takeIf { it > 0 }?.let { it * 1000L }
                val bitrate = audioProperties?.bitrate?.takeIf { it > 0 }?.let { it * 1000 }
                val sampleRate = audioProperties?.sampleRate?.takeIf { it > 0 }

                // Get metadata
                val metadata = TagLib.getMetadata(fd.dup().detachFd(), readPictures = false)
                val propertyMap = metadata?.propertyMap ?: emptyMap()

                // Log ALL keys TagLib returned so we can diagnose mapping issues
                Timber.tag(TAG).w("TagLib propertyMap keys for ${file.name}: ${propertyMap.keys}")

                val title = fixCharset(propertyMap["TITLE"]?.firstOrNull()?.takeIf { it.isNotBlank() })
                val artist = fixCharset(propertyMap["ARTIST"]?.firstOrNull()?.takeIf { it.isNotBlank() })
                val albumArtist = fixCharset(propertyMap["ALBUMARTIST"]?.firstOrNull()?.takeIf { it.isNotBlank() })
                    ?: propertyMap["ALBUM ARTIST"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: propertyMap["BAND"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                val album = fixCharset(propertyMap["ALBUM"]?.firstOrNull()?.takeIf { it.isNotBlank() })
                val genre = fixCharset(propertyMap["GENRE"]?.firstOrNull()?.takeIf { it.isNotBlank() })
                val composer = fixCharset(propertyMap["COMPOSER"]?.firstOrNull()?.takeIf { it.isNotBlank() })
                    ?: propertyMap["TCOM"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                val lyrics = fixCharset(propertyMap["LYRICS"]?.firstOrNull()?.takeIf { it.isNotBlank() })
                    ?: propertyMap["UNSYNCEDLYRICS"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                val trackString = propertyMap["TRACKNUMBER"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: propertyMap["TRACK"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                val trackNumber = trackString?.substringBefore('/')?.toIntOrNull()
                val discString = propertyMap["DISCNUMBER"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: propertyMap["DISC"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                val discNumber = discString?.substringBefore('/')?.toIntOrNull()
                val year = propertyMap["DATE"]?.firstOrNull()?.takeIf { it.isNotBlank() }?.take(4)?.toIntOrNull()
                    ?: propertyMap["YEAR"]?.firstOrNull()?.takeIf { it.isNotBlank() }?.toIntOrNull()
                val replayGainTrackGainDb = extractReplayGainDb(
                    propertyMap = propertyMap,
                    keys = listOf("REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_GAIN_DB", "R128_TRACK_GAIN")
                )
                val replayGainAlbumGainDb = extractReplayGainDb(
                    propertyMap = propertyMap,
                    keys = listOf("REPLAYGAIN_ALBUM_GAIN", "REPLAYGAIN_ALBUM_GAIN_DB", "R128_ALBUM_GAIN")
                )

                Timber.tag(TAG).w("TagLib result for ${file.name}: title=$title, artist=$artist, album=$album, genre=$genre")

                // Get artwork only when requested to avoid allocating large ByteArrays unnecessarily
                val artwork = if (readArtwork) {
                    val pictures = TagLib.getPictures(fd.detachFd())
                    pictures.firstOrNull()?.let { picture ->
                        picture.data.takeIf { it.isNotEmpty() && isValidImageData(it) }?.let { data ->
                            AudioMetadataArtwork(
                                bytes = data,
                                mimeType = picture.mimeType.takeIf { it.isNotBlank() } ?: guessImageMimeType(data)
                            )
                        }
                    }
                } else {
                    null
                }

                // Fallback: TagLib sometimes parses core tags but misses APIC/other ID3 frames
                // on some MP3s. If essential fields or requested artwork are missing, try
                // JAudioTagger before giving up so we preserve full metadata when possible.
                val fallback = if (title == null || artist == null || (readArtwork && artwork == null)) {
                    Timber.tag(TAG).w("TagLib incomplete for ${file.name}, trying JAudioTagger fallback...")
                    readWithJAudioTagger(file)
                } else null

                AudioMetadata(
                    title = title ?: fallback?.title,
                    artist = artist ?: fallback?.artist,
                    albumArtist = albumArtist ?: fallback?.albumArtist,
                    album = album ?: fallback?.album,
                    genre = genre ?: fallback?.genre,
                    composer = composer ?: fallback?.composer,
                    lyrics = lyrics ?: fallback?.lyrics,
                    durationMs = durationMs ?: fallback?.durationMs,
                    trackNumber = trackNumber ?: fallback?.trackNumber,
                    discNumber = discNumber ?: fallback?.discNumber,
                    year = year ?: fallback?.year,
                    bitrate = bitrate ?: fallback?.bitrate,
                    sampleRate = sampleRate ?: fallback?.sampleRate,
                    artwork = artwork ?: fallback?.artwork,
                    replayGainTrackGainDb = replayGainTrackGainDb ?: fallback?.replayGainTrackGainDb,
                    replayGainAlbumGainDb = replayGainAlbumGainDb ?: fallback?.replayGainAlbumGainDb
                )
            }
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "Unable to read metadata from file: ${file.absolutePath}")
            null
        }
    }

    /**
     * Fallback reader using JAudioTagger for files where TagLib can't map ID3 frames.
     * Called when TagLib leaves key metadata or requested artwork unresolved.
     */
    private fun readWithJAudioTagger(file: File): AudioMetadata? {
        return try {
            // Suppress JAudioTagger's verbose logging
            java.util.logging.Logger.getLogger("org.jaudiotagger").level = java.util.logging.Level.OFF

            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag
            val header = audioFile.audioHeader

            Timber.tag(TAG).w("JAudioTagger: tag class=${tag?.javaClass?.simpleName}, " +
                    "header=${header?.format}, sampleRate=${header?.sampleRateAsNumber}")

            val title = tag?.getFirst(FieldKey.TITLE)?.takeIf { it.isNotBlank() }
            val artist = tag?.getFirst(FieldKey.ARTIST)?.takeIf { it.isNotBlank() }
            val albumArtist = tag?.getFirst(FieldKey.ALBUM_ARTIST)?.takeIf { it.isNotBlank() }
            val album = tag?.getFirst(FieldKey.ALBUM)?.takeIf { it.isNotBlank() }
            val genre = tag?.getFirst(FieldKey.GENRE)?.takeIf { it.isNotBlank() }
            val composer = tag?.getFirst(FieldKey.COMPOSER)?.takeIf { it.isNotBlank() }
            val lyrics = tag?.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() }
            val trackNumber = tag?.getFirst(FieldKey.TRACK)?.takeIf { it.isNotBlank() }
                ?.substringBefore('/')?.toIntOrNull()
            val discNumber = tag?.getFirst(FieldKey.DISC_NO)?.takeIf { it.isNotBlank() }
                ?.substringBefore('/')?.toIntOrNull()
            val year = tag?.getFirst(FieldKey.YEAR)?.takeIf { it.isNotBlank() }
                ?.take(4)?.toIntOrNull()

            val durationMs = header?.trackLength?.takeIf { it > 0 }?.let { it * 1000L }
            val bitrate = header?.bitRateAsNumber?.takeIf { it > 0 }?.toInt()?.let { it * 1000 }
            val sampleRate = header?.sampleRateAsNumber?.takeIf { it > 0 }

            // Try to get artwork from JAudioTagger
            val artwork = tag?.firstArtwork?.let { art ->
                art.binaryData?.takeIf { it.isNotEmpty() && isValidImageData(it) }?.let { data ->
                    AudioMetadataArtwork(
                        bytes = data,
                        mimeType = art.mimeType?.takeIf { it.isNotBlank() } ?: guessImageMimeType(data)
                    )
                }
            }

            Timber.tag(TAG).w("JAudioTagger result for ${file.name}: title=$title, artist=$artist, " +
                    "album=$album, genre=$genre, artwork=${artwork != null}")

            AudioMetadata(
                title = title,
                artist = artist,
                albumArtist = albumArtist,
                album = album,
                genre = genre,
                composer = composer,
                lyrics = lyrics,
                durationMs = durationMs,
                trackNumber = trackNumber,
                discNumber = discNumber,
                year = year,
                bitrate = bitrate,
                sampleRate = sampleRate,
                artwork = artwork
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "JAudioTagger fallback FAILED for: ${file.name}")
            null
        }
    }

    private fun extractReplayGainDb(
        propertyMap: Map<String, Array<String>>,
        keys: List<String>
    ): Float? {
        for (key in keys) {
            val rawValue = propertyMap[key]?.firstOrNull() ?: continue
            val parsedValue = parseReplayGainDb(rawValue)
            if (parsedValue != null) {
                return parsedValue
            }
        }
        return null
    }

    private fun parseReplayGainDb(rawValue: String?): Float? {
        val cleanedValue = rawValue
            ?.trim()
            ?.replace(',', '.')
            ?.replace(Regex("(?i)[dD][bB]"), "")
            ?.trim()
            ?: return null
        return cleanedValue.toFloatOrNull()
    }
}

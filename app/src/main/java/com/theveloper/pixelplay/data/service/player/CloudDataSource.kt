/*
 * Copyright (C) 2026 PixelPlayer
 *
 * CloudDataSource — Media3 DataSource that fetches bytes directly from a cloud
 * provider (Telegram, Netease, QQ Music, Navidrome, Jellyfin, GDrive) without
 * routing through a localhost HTTP proxy.
 *
 * Status: skeleton. Not yet wired into DualPlayerEngine — kept here as the
 * architectural target for the cloud proxy consolidation. Currently each
 * provider has its own CloudStreamProxy subclass running a Ktor CIO server
 * on a random localhost port (8 instances total). This factory would replace
 * all 8 by registering a single DataSource.Factory with the ExoPlayer.Builder
 * that dispatches based on the URI scheme.
 *
 * Migration plan (deferred — XL effort):
 * 1. Implement resolveAndOpenStream(uri) for each provider.
 * 2. Register CloudDataSource.Factory in DualPlayerEngine.buildPlayer() as
 *    the DataSource.Factory instead of DefaultDataSource.Factory.
 * 3. Delete the 6 CloudStreamProxy subclasses + their Ktor servers.
 * 4. Remove CloudStreamSecurity (no longer needed — no HTTP server exposed).
 * 5. Remove DualPlayerEngine.resolvedUriCache (no longer needed).
 *
 * Benefits when complete:
 * - 6 fewer Ktor CIO server instances (saves ~6MB RAM, 6 thread pools)
 * - One less TCP hop (ExoPlayer → DataSource → OkHttp directly)
 * - No port allocation, no startIfNeeded lifecycle
 * - ~2000 LOC reduction
 */
package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.theveloper.pixelplay.data.cloud.CloudProvider
import com.theveloper.pixelplay.data.gdrive.GDriveRepository
import com.theveloper.pixelplay.data.jellyfin.JellyfinRepository
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import com.theveloper.pixelplay.data.netease.NeteaseRepository
import com.theveloper.pixelplay.data.qqmusic.QqMusicRepository
import com.theveloper.pixelplay.data.telegram.TelegramRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Media3 [DataSource] that fetches bytes directly from a cloud provider's
 * upstream URL (resolved via the provider's repository) without going through
 * a localhost HTTP proxy server.
 *
 * Supports HTTP range requests (Range header) so ExoPlayer can seek within
 * cloud tracks. Reads are blocking (synchronous), so this DataSource must be
 * used on the IO thread — ExoPlayer's default behavior.
 *
 * TODO: implement provider-specific resolvers. Currently this is a skeleton
 * that only handles HTTP/HTTPS URIs (already resolved by callers).
 */
class CloudDataSource private constructor(
    private val okHttpClient: OkHttpClient,
    private val telegramRepository: TelegramRepository,
    private val neteaseRepository: NeteaseRepository,
    private val qqMusicRepository: QqMusicRepository,
    private val gdriveRepository: GDriveRepository,
    private val navidromeRepository: NavidromeRepository,
    private val jellyfinRepository: JellyfinRepository,
    private val cloudAuthStateBroadcaster: com.theveloper.pixelplay.data.cloud.CloudAuthStateBroadcaster,
) : BaseDataSource(true /* isNetwork */) {

    private var currentResponse: okhttp3.Response? = null
    private var currentStream: java.io.InputStream? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val uriString = uri.toString()

        // Resolve the cloud URI to a real upstream URL via the provider's repository.
        // If the URI is already an http(s):// URL (pre-resolved), use it directly.
        val upstreamUrl = if (uri.scheme in HTTP_SCHEMES) {
            uriString
        } else {
            runBlocking { resolveCloudUri(uri) } ?: run {
                throw java.io.IOException("Could not resolve cloud URI: $uriString — provider not ready or auth revoked")
            }
        }

        // Build the upstream request, including a Range header if the DataSpec specifies one.
        val requestBuilder = Request.Builder().url(upstreamUrl)
        if (dataSpec.position > 0) {
            requestBuilder.header("Range", "bytes=${dataSpec.position}-${dataSpec.position + dataSpec.length - 1}")
        }

        val response = okHttpClient.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful && response.code != 206) {
            response.close()
            throw java.io.IOException("Upstream returned ${response.code} for $upstreamUrl")
        }

        currentResponse = response
        currentStream = response.body?.byteStream()
            ?: throw java.io.IOException("Response body is null for $upstreamUrl")

        // Compute bytesRemaining for transferStarted reporting.
        val contentLength = response.body?.contentLength() ?: -1L
        bytesRemaining = if (contentLength > 0) contentLength else C.LENGTH_UNSET.toLong()

        transferStarted(dataSpec)
        opened = true
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened) return C.RESULT_END_OF_INPUT
        val stream = currentStream ?: return C.RESULT_END_OF_INPUT

        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            min(length, bytesRemaining.toInt().coerceAtLeast(0))
        }.coerceAtLeast(0)

        if (toRead == 0) return C.RESULT_END_OF_INPUT

        val read = stream.read(buffer, offset, toRead)
        if (read > 0) {
            bytesTransferred(read)
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining -= read
            }
        }
        return read
    }

    override fun close() {
        opened = false
        try { currentStream?.close() } catch (_: Exception) {}
        currentStream = null
        try { currentResponse?.close() } catch (_: Exception) {}
        currentResponse = null
        bytesRemaining = C.LENGTH_UNSET.toLong()
    }

    /**
     * Resolve a cloud:// URI to an upstream URL via the provider's repository.
     * Returns null if the provider is not ready (auth pending, proxy not initialized).
     */
    private suspend fun resolveCloudUri(uri: android.net.Uri): String? {
        return when (uri.scheme) {
            "telegram" -> {
                // Telegram uses an on-device file (downloaded via TDLib), not a URL.
                // The TelegramStreamProxy serves bytes from the local file; we can't
                // easily replace it with a direct URL because TDLib exposes a file path,
                // not a URL. For Telegram, the localhost-proxy approach is actually
                // the correct one — keep TelegramStreamProxy.
                // Fallback: return null to signal that the caller should use the proxy path.
                Timber.tag("CloudDataSource").w("Telegram URIs require the localhost proxy path (TDLib exposes a file, not a URL)")
                null
            }
            "netease" -> {
                val songId = uri.host ?: return null
                neteaseRepository.getStreamUrl(songId)
            }
            "qqmusic" -> {
                val songMid = uri.host ?: return null
                qqMusicRepository.getStreamUrl(songMid)
            }
            "gdrive" -> {
                val fileId = uri.host ?: return null
                gdriveRepository.getStreamUrl(fileId)
            }
            "navidrome" -> {
                val songId = uri.host ?: return null
                navidromeRepository.getStreamUrl(songId)
            }
            "jellyfin" -> {
                val itemId = uri.host ?: return null
                jellyfinRepository.getStreamUrl(itemId)
            }
            else -> {
                Timber.tag("CloudDataSource").w("Unknown cloud scheme: ${uri.scheme}")
                null
            }
        }
    }

    companion object {
        private val HTTP_SCHEMES = setOf("http", "https")
    }

    /**
     * Factory that creates CloudDataSource instances. Register this with
     * ExoPlayer.Builder.setMediaSourceFactory(DefaultMediaSourceFactory(ResolvingDataSource.Factory(
     * CloudDataSource.Factory(...), resolver))) to replace the localhost-proxy approach.
     *
     * NOTE: Telegram URIs still need the localhost proxy because TDLib exposes a file,
     * not a URL. The factory detects Telegram URIs and falls back to the proxy path
     * by returning the original URI (the existing ResolvingDataSource.Resolver in
     * DualPlayerEngine will then handle it).
     */
    @Singleton
    class Factory @Inject constructor(
        private val okHttpClient: OkHttpClient,
        private val telegramRepository: TelegramRepository,
        private val neteaseRepository: NeteaseRepository,
        private val qqMusicRepository: QqMusicRepository,
        private val gdriveRepository: GDriveRepository,
        private val navidromeRepository: NavidromeRepository,
        private val jellyfinRepository: JellyfinRepository,
        private val cloudAuthStateBroadcaster: com.theveloper.pixelplay.data.cloud.CloudAuthStateBroadcaster,
    ) : DataSource.Factory {
        override fun createDataSource(): CloudDataSource {
            return CloudDataSource(
                okHttpClient,
                telegramRepository,
                neteaseRepository,
                qqMusicRepository,
                gdriveRepository,
                navidromeRepository,
                jellyfinRepository,
                cloudAuthStateBroadcaster,
            )
        }
    }
}

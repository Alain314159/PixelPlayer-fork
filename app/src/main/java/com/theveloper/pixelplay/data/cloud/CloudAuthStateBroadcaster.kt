/*
 * Copyright (C) 2026 PixelPlayer
 *
 * Broadcasts cloud auth state changes (login, logout, token refresh failure) so
 * consumers like [com.theveloper.pixelplay.data.service.player.DualPlayerEngine]
 * can invalidate any caches that depend on a stable proxy URL or auth token.
 *
 * Why this exists:
 * DualPlayerEngine.resolvedUriCache maps cloud:// URIs to localhost proxy URLs
 * (e.g. http://127.0.0.1:PORT/navidrome/...). When a user signs out of Navidrome
 * or revokes OAuth for GDrive, the proxy is stopped and (potentially) restarted
 * on a different port next time. Stale entries in resolvedUriCache would point
 * to a port that's now closed — playback would fail with Connection refused
 * until the LRU evicted up to 100 entries.
 *
 * Usage:
 *   // In a cloud repository's logout():
 *   cloudAuthStateBroadcaster.notifyLoggedOut(CloudProvider.NAVIDROME)
 *
 *   // In DualPlayerEngine init:
 *   cloudAuthStateBroadcaster.observeLoggedOut { provider ->
 *       resolvedUriCache.evictAll()
 *   }
 *
 * This avoids a direct dependency from cloud repositories to DualPlayerEngine
 * (which would create a cycle: engine -> repository -> engine).
 */
package com.theveloper.pixelplay.data.cloud

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class CloudProvider {
    TELEGRAM,
    NETEASE,
    QQ_MUSIC,
    GDRIVE,
    NAVIDROME,
    JELLYFIN,
    ALL
}

sealed class CloudAuthEvent {
    abstract val provider: CloudProvider

    data class LoggedOut(override val provider: CloudProvider) : CloudAuthEvent()
    data class TokenRefreshFailed(override val provider: CloudProvider) : CloudAuthEvent()
    data class LoggedIn(override val provider: CloudProvider) : CloudAuthEvent()
    data class ProxyRestarted(override val provider: CloudProvider) : CloudAuthEvent()
}

@Singleton
class CloudAuthStateBroadcaster @Inject constructor() {

    private val _events = MutableSharedFlow<CloudAuthEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<CloudAuthEvent> = _events.asSharedFlow()

    fun notifyLoggedOut(provider: CloudProvider) {
        _events.tryEmit(CloudAuthEvent.LoggedOut(provider))
    }

    fun notifyTokenRefreshFailed(provider: CloudProvider) {
        _events.tryEmit(CloudAuthEvent.TokenRefreshFailed(provider))
    }

    fun notifyLoggedIn(provider: CloudProvider) {
        _events.tryEmit(CloudAuthEvent.LoggedIn(provider))
    }

    fun notifyProxyRestarted(provider: CloudProvider) {
        _events.tryEmit(CloudAuthEvent.ProxyRestarted(provider))
    }
}

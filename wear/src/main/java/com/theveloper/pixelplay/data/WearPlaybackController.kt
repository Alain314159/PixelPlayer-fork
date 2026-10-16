package com.theveloper.pixelplay.data

import android.app.Application
import com.google.android.gms.wearable.Wearable
import com.theveloper.pixelplay.shared.WearDataPaths
import com.theveloper.pixelplay.shared.WearPlaybackCommand
import com.theveloper.pixelplay.shared.WearVolumeCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends playback and volume commands to the phone app via the Wear Data Layer MessageClient.
 * Resolves the connected phone node and sends serialized command messages.
 *
 * On Wear OS 6 (Android 16) the phone is resolved via [WearConnectivityRepository] (capability
 * discovery) rather than [com.google.android.gms.wearable.NodeClient.getConnectedNodes], which
 * no longer reliably reflects reachability on that platform.
 */
@Singleton
class WearPlaybackController @Inject constructor(
    private val application: Application,
    private val stateRepository: WearStateRepository,
    private val connectivityRepository: WearConnectivityRepository,
) {
    private val messageClient by lazy { Wearable.getMessageClient(application) }
    private val nodeClient by lazy { Wearable.getNodeClient(application) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "WearPlaybackCtrl"
    }

    fun sendCommand(command: WearPlaybackCommand) {
        scope.launch {
            sendMessageToPhone(
                path = WearDataPaths.PLAYBACK_COMMAND,
                data = json.encodeToString(command).toByteArray(Charsets.UTF_8)
            )
        }
    }

    fun sendVolumeCommand(command: WearVolumeCommand) {
        scope.launch {
            sendMessageToPhone(
                path = WearDataPaths.VOLUME_COMMAND,
                data = json.encodeToString(command).toByteArray(Charsets.UTF_8)
            )
        }
    }

    // Convenience methods for common actions
    fun play() = sendCommand(WearPlaybackCommand(WearPlaybackCommand.PLAY))
    fun pause() = sendCommand(WearPlaybackCommand(WearPlaybackCommand.PAUSE))
    fun togglePlayPause() = sendCommand(WearPlaybackCommand(WearPlaybackCommand.TOGGLE_PLAY_PAUSE))
    fun next() = sendCommand(WearPlaybackCommand(WearPlaybackCommand.NEXT))
    fun previous() = sendCommand(WearPlaybackCommand(WearPlaybackCommand.PREVIOUS))
    fun toggleFavorite(
        songId: String? = null,
        targetEnabled: Boolean? = null,
        requestId: String? = null,
    ) = sendCommand(
        WearPlaybackCommand(
            action = WearPlaybackCommand.TOGGLE_FAVORITE,
            songId = songId,
            requestId = requestId,
            targetEnabled = targetEnabled
        )
    )
    fun toggleShuffle(targetEnabled: Boolean? = null) = sendCommand(
        WearPlaybackCommand(
            action = WearPlaybackCommand.TOGGLE_SHUFFLE,
            targetEnabled = targetEnabled
        )
    )
    fun cycleRepeat() = sendCommand(WearPlaybackCommand(WearPlaybackCommand.CYCLE_REPEAT))
    fun volumeUp() = sendVolumeCommand(WearVolumeCommand(WearVolumeCommand.UP))
    fun volumeDown() = sendVolumeCommand(WearVolumeCommand(WearVolumeCommand.DOWN))
    fun setPhoneVolume(percent: Int) = sendVolumeCommand(
        WearVolumeCommand(
            direction = WearVolumeCommand.SET,
            value = percent.coerceIn(0, 100),
        )
    )
    fun requestPhoneVolumeState() = sendVolumeCommand(WearVolumeCommand(WearVolumeCommand.QUERY))

    /**
     * Ask the phone to republish its current player state (DataItem) and current
     * volume state (message). Used at watch app startup and whenever the watch
     * detects the phone has just become reachable again — the Wear Data Layer on
     * Wear OS 6 does not reliably re-deliver cached DataItems on its own.
     */
    fun requestStateRefresh() = sendCommand(
        WearPlaybackCommand(WearPlaybackCommand.REQUEST_STATE_REFRESH)
    )

    /** Play a song within its context queue (album, artist, playlist, etc.) */
    fun playFromContext(songId: String, contextType: String, contextId: String?) = sendCommand(
        WearPlaybackCommand(
            action = WearPlaybackCommand.PLAY_FROM_CONTEXT,
            songId = songId,
            contextType = contextType,
            contextId = contextId,
        )
    )

    suspend fun playItemAwaitDispatch(songId: String, requestId: String): Boolean {
        return sendMessageToPhone(
            path = WearDataPaths.PLAYBACK_COMMAND,
            data = json.encodeToString(
                WearPlaybackCommand(
                    action = WearPlaybackCommand.PLAY_ITEM,
                    songId = songId,
                    requestId = requestId,
                )
            ).toByteArray(Charsets.UTF_8)
        )
    }

    fun playNextFromContext(songId: String, contextType: String, contextId: String?) = sendCommand(
        WearPlaybackCommand(
            action = WearPlaybackCommand.PLAY_NEXT_FROM_CONTEXT,
            songId = songId,
            contextType = contextType,
            contextId = contextId,
        )
    )

    fun addToQueueFromContext(songId: String, contextType: String, contextId: String?) = sendCommand(
        WearPlaybackCommand(
            action = WearPlaybackCommand.ADD_TO_QUEUE_FROM_CONTEXT,
            songId = songId,
            contextType = contextType,
            contextId = contextId,
        )
    )

    fun playQueueIndex(index: Int) = sendCommand(
        WearPlaybackCommand(
            action = WearPlaybackCommand.PLAY_QUEUE_INDEX,
            queueIndex = index,
        )
    )

    fun setSleepTimerDuration(durationMinutes: Int) = sendCommand(
        WearPlaybackCommand(
            action = WearPlaybackCommand.SET_SLEEP_TIMER_DURATION,
            durationMinutes = durationMinutes,
        )
    )

    fun setSleepTimerEndOfTrack(enabled: Boolean = true) = sendCommand(
        WearPlaybackCommand(
            action = WearPlaybackCommand.SET_SLEEP_TIMER_END_OF_TRACK,
            targetEnabled = enabled,
        )
    )

    fun cancelSleepTimer() = sendCommand(
        WearPlaybackCommand(
            action = WearPlaybackCommand.CANCEL_SLEEP_TIMER,
        )
    )

    private suspend fun sendMessageToPhone(path: String, data: ByteArray): Boolean {
        val nodes = resolvePhoneNodes()
        if (nodes.isEmpty()) {
            stateRepository.setPhoneConnected(false)
            Timber.tag(TAG).w("No reachable phone node found — phone not reachable (path=%s)", path)
            return false
        }
        stateRepository.setPhoneConnected(true)
        stateRepository.setPhoneDeviceName(nodes.firstOrNull()?.displayName.orEmpty())

        // Send to all reachable phone nodes (typically just one phone)
        var delivered = false
        nodes.forEach { node ->
            try {
                Timber.tag(TAG).d(
                    "Sending message path=%s bytes=%d nodeId=%s nodeName=%s",
                    path,
                    data.size,
                    node.id,
                    node.displayName
                )
                messageClient.sendMessage(node.id, path, data).await()
                delivered = true
                Timber.tag(TAG).d(
                    "Sent message path=%s nodeId=%s nodeName=%s",
                    path,
                    node.id,
                    node.displayName
                )
            } catch (e: Exception) {
                Timber.tag(TAG).e(
                    e,
                    "Failed to send message path=%s nodeId=%s nodeName=%s",
                    path,
                    node.id,
                    node.displayName
                )
            }
        }
        if (!delivered) {
            stateRepository.setPhoneConnected(false)
        }
        return delivered
    }

    /**
     * Resolve the set of reachable phone nodes. Preference order:
     *   1. The capability-based snapshot maintained by [WearConnectivityRepository].
     *      This is the only path that works reliably on Wear OS 6.
     *   2. A fresh capability lookup (the listener may not have fired yet on cold
     *      start, especially right after watch app launch).
     *   3. Legacy fallback to [NodeClient.getConnectedNodes] for older platforms or
     *      paired devices that don't advertise the capability for some reason.
     */
    private suspend fun resolvePhoneNodes(): List<com.google.android.gms.wearable.Node> {
        connectivityRepository.reachablePhoneNodes.value.takeIf { it.isNotEmpty() }
            ?.let { return it }
        connectivityRepository.refreshCapabilityNow()
        connectivityRepository.reachablePhoneNodes.value.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return try {
            nodeClient.connectedNodes.await()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Legacy connectedNodes lookup failed")
            emptyList()
        }
    }
}

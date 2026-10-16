package com.theveloper.pixelplay.data

import android.app.Application
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.theveloper.pixelplay.shared.WearCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether the companion phone running PixelPlay is reachable, using the
 * recommended capability-discovery API.
 *
 * Why this exists: `NodeClient.getConnectedNodes()` became unreliable on Wear OS 6
 * (Android 16) — it can return paired-but-unreachable nodes and cause
 * `MessageClient.sendMessage` to fail with no useful error. The fix is to discover
 * the phone via [CapabilityClient] against the phone-advertised capability
 * [WearCapabilities.PIXELPLAY_PHONE_APP], and to subscribe to capability changes so
 * the connection state in the UI updates in real time.
 *
 * The repository keeps a single "best" reachable phone node id; consumers should call
 * [bestReachablePhoneNode] when they need to address the phone explicitly
 * (e.g., to send a directed message).
 */
@Singleton
class WearConnectivityRepository @Inject constructor(
    private val application: Application,
    private val stateRepository: WearStateRepository,
) {
    private val capabilityClient by lazy { Wearable.getCapabilityClient(application) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _reachablePhoneNodes = MutableStateFlow<List<Node>>(emptyList())
    val reachablePhoneNodes: StateFlow<List<Node>> = _reachablePhoneNodes.asStateFlow()

    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { info ->
        if (info.name == WearCapabilities.PIXELPLAY_PHONE_APP) {
            onCapabilityChanged(info)
        }
    }

    @Volatile
    private var started = false

    companion object {
        private const val TAG = "WearConnectivity"
    }

    /**
     * Begin observing the phone capability. Idempotent — safe to call from multiple
     * entry points (Application.onCreate, WearMainActivity.onCreate, the
     * WearableListenerService) so the first one to fire wins.
     */
    fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        capabilityClient.addListener(
            capabilityListener,
            WearCapabilities.PIXELPLAY_PHONE_APP,
        )
        scope.launch { refreshCapabilityNow() }
        Timber.tag(TAG).d("Capability listener registered for %s", WearCapabilities.PIXELPLAY_PHONE_APP)
    }

    /**
     * Manually poll the current capability state. The capability listener already
     * pushes updates reactively; this is for the initial value at startup and as a
     * recovery hook after long sleep periods on watches with aggressive power
     * management.
     */
    suspend fun refreshCapabilityNow() {
        try {
            val info = capabilityClient
                .getCapability(WearCapabilities.PIXELPLAY_PHONE_APP, CapabilityClient.FILTER_REACHABLE)
                .await()
            onCapabilityChanged(info)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to query capability — assuming phone unreachable")
            updateReachableNodes(emptyList())
        }
    }

    /** Pick the best reachable phone node, preferring the nearby one. */
    fun bestReachablePhoneNode(): Node? {
        val nodes = _reachablePhoneNodes.value
        return nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
    }

    private fun onCapabilityChanged(info: CapabilityInfo) {
        Timber.tag(TAG).d(
            "Capability changed: %s -> %d reachable node(s)",
            info.name,
            info.nodes.size,
        )
        updateReachableNodes(info.nodes.toList())
    }

    private fun updateReachableNodes(nodes: List<Node>) {
        _reachablePhoneNodes.value = nodes
        val isConnected = nodes.isNotEmpty()
        stateRepository.setPhoneConnected(isConnected)
        if (isConnected) {
            val preferred = nodes.firstOrNull { it.isNearby } ?: nodes.first()
            stateRepository.setPhoneDeviceName(preferred.displayName)
        }
    }
}

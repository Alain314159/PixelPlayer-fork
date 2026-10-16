package com.theveloper.pixelplay.shared

/**
 * Shared Wear capability names used to discover reachable devices
 * with specific PixelPlay features.
 *
 * Capabilities are the recommended way (since Wear OS 4 / API 33 and required-in-practice
 * on Wear OS 6) to determine whether a peer device is actually reachable. The legacy
 * approach of using [com.google.android.gms.wearable.NodeClient.getConnectedNodes] became
 * unreliable on Wear OS 6 / Android 16: it may return cached/paired-but-unreachable nodes,
 * which then causes [com.google.android.gms.wearable.MessageClient.sendMessage] to fail
 * silently or to time out.
 */
object WearCapabilities {
    /** Advertised by the Wear OS app; used by the phone to discover watches. */
    const val PIXELPLAY_WEAR_APP = "pixelplay_wear_app"

    /** Advertised by the phone app; used by the watch to discover the companion phone. */
    const val PIXELPLAY_PHONE_APP = "pixelplay_phone_app"
}

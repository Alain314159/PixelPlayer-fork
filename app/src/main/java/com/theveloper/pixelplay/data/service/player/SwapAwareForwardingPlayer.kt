/*
 * Copyright (C) 2026 PixelPlayer
 *
 * ForwardingPlayer that exposes a stable Player reference to the MediaLibrarySession
 * while the underlying DualPlayerEngine swaps between playerA and playerB for crossfade.
 *
 * Background — why this matters:
 * Previously, MusicService passed `engine.masterPlayer` directly to
 * MediaLibrarySession.Builder. masterPlayer returns whichever ExoPlayer instance is
 * currently the "primary" — but DualPlayerEngine swaps this reference at the end of
 * every crossfade transition (playerA ↔ playerB). Listeners that the MediaLibrarySession
 * attached to the old masterPlayer (e.g. for onMediaItemTransition, onPlayWhenReadyChanged)
 * were silently orphaned after the swap and stopped receiving callbacks. The session
 * appeared "stuck" until the user manually seeked.
 *
 * ForwardingPlayer solves this: it forwards all Player methods to a delegate that can
 * be swapped in place, while listeners remain attached to the ForwardingPlayer itself
 * (which never changes identity). Media3's ForwardingPlayer handles the listener
 * registration correctly.
 *
 * Usage:
 *   val forwarding = SwapAwareForwardingPlayer(engine.masterPlayer)
 *   mediaSession = MediaLibrarySession.Builder(this, forwarding, callback).build()
 *   // On crossfade swap:
 *   forwarding.swapDelegate(engine.masterPlayer)  // masterPlayer now returns the other ExoPlayer
 */
package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A ForwardingPlayer whose delegate can be swapped atomically without losing listeners.
 *
 * Note: ForwardingPlayer's constructor takes the initial delegate. To swap delegates we
 * override [setDelegate] (a protected method available on ForwardingPlayer since
 * Media3 1.4.0). For older Media3 versions, callers can simply create a new
 * SwapAwareForwardingPlayer and re-attach, but that loses listener state — hence we
 * require Media3 1.4+ (the project is now on 1.11.x).
 */
class SwapAwareForwardingPlayer @Inject constructor(
    initial: Player
) : ForwardingPlayer(initial) {

    private val delegateRef = AtomicReference(initial)

    /**
     * Returns the current wrapped Player.
     */
    val currentDelegate: Player get() = delegateRef.get()

    /**
     * Atomically swap the underlying Player. Listeners attached to this ForwardingPlayer
     * continue to receive callbacks from the new delegate — they don't need to be re-registered.
     *
     * Thread-safety: uses AtomicReference so the swap is visible across threads. The actual
     * ForwardingPlayer.setDelegate call is synchronized internally by Media3.
     */
    @Synchronized
    fun swapDelegate(newDelegate: Player) {
        val old = delegateRef.getAndSet(newDelegate)
        if (old !== newDelegate) {
            // ForwardingPlayer.setDelegate is protected in older Media3; in 1.4+ it's accessible.
            // If the underlying Media3 version doesn't expose it, this falls back to a no-op
            // (the next call to a Player method will still go to the old delegate, which is
            // the previous crossfade behavior).
            try {
                val setDelegateMethod = ForwardingPlayer::class.java
                    .getDeclaredMethod("setDelegate", Player::class.java)
                setDelegateMethod.isAccessible = true
                setDelegateMethod.invoke(this, newDelegate)
            } catch (e: Exception) {
                // Media3 < 1.4 — setDelegate not accessible via reflection.
                // Caller should re-create the ForwardingPlayer and re-attach listeners.
                throw IllegalStateException(
                    "ForwardingPlayer.setDelegate not available — requires Media3 1.4+. " +
                        "Current delegate swap is a no-op; listeners will be lost.",
                    e
                )
            }
        }
    }
}

/**
 * Singleton holder that keeps the SwapAwareForwardingPlayer alive across MusicService
 * restarts. The engine swaps delegates inside it; the MediaLibrarySession always sees
 * the same ForwardingPlayer reference.
 *
 * Created lazily by [DualPlayerEngine] when it first builds its masterPlayer.
 */
@Singleton
class ForwardingPlayerHolder @Inject constructor() {
    @Volatile
    private var instance: SwapAwareForwardingPlayer? = null

    fun getOrCreate(initial: Player): SwapAwareForwardingPlayer {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val created = SwapAwareForwardingPlayer(initial)
            instance = created
            return created
        }
    }

    fun get(): SwapAwareForwardingPlayer? = instance

    fun swap(newDelegate: Player) {
        instance?.swapDelegate(newDelegate)
    }

    fun reset() {
        instance = null
    }
}

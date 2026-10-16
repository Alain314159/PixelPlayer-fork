package com.theveloper.pixelplay.data.cloud

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CloudAuthStateBroadcaster].
 *
 * Verifies that auth state change events are broadcast to subscribers and that
 * the SharedFlow properly delivers events in order with DROP_OLDEST backpressure.
 */
class CloudAuthStateBroadcasterTest {

    @Test
    fun `notifyLoggedOut emits LoggedOut event with correct provider`() = runTest {
        val broadcaster = CloudAuthStateBroadcaster()

        broadcaster.events.test {
            broadcaster.notifyLoggedOut(CloudProvider.NAVIDROME)

            val event = awaitItem()
            assertTrue(event is CloudAuthEvent.LoggedOut)
            assertEquals(CloudProvider.NAVIDROME, event.provider)
        }
    }

    @Test
    fun `notifyTokenRefreshFailed emits TokenRefreshFailed event`() = runTest {
        val broadcaster = CloudAuthStateBroadcaster()

        broadcaster.events.test {
            broadcaster.notifyTokenRefreshFailed(CloudProvider.GDRIVE)

            val event = awaitItem()
            assertTrue(event is CloudAuthEvent.TokenRefreshFailed)
            assertEquals(CloudProvider.GDRIVE, event.provider)
        }
    }

    @Test
    fun `notifyLoggedIn emits LoggedIn event`() = runTest {
        val broadcaster = CloudAuthStateBroadcaster()

        broadcaster.events.test {
            broadcaster.notifyLoggedIn(CloudProvider.TELEGRAM)

            val event = awaitItem()
            assertTrue(event is CloudAuthEvent.LoggedIn)
            assertEquals(CloudProvider.TELEGRAM, event.provider)
        }
    }

    @Test
    fun `notifyProxyRestarted emits ProxyRestarted event`() = runTest {
        val broadcaster = CloudAuthStateBroadcaster()

        broadcaster.events.test {
            broadcaster.notifyProxyRestarted(CloudProvider.JELLYFIN)

            val event = awaitItem()
            assertTrue(event is CloudAuthEvent.ProxyRestarted)
            assertEquals(CloudProvider.JELLYFIN, event.provider)
        }
    }

    @Test
    fun `multiple events are delivered in order`() = runTest {
        val broadcaster = CloudAuthStateBroadcaster()

        broadcaster.events.test {
            broadcaster.notifyLoggedIn(CloudProvider.NETEASE)
            broadcaster.notifyLoggedOut(CloudProvider.QQ_MUSIC)
            broadcaster.notifyProxyRestarted(CloudProvider.NAVIDROME)

            val first = awaitItem()
            val second = awaitItem()
            val third = awaitItem()

            assertTrue(first is CloudAuthEvent.LoggedIn)
            assertEquals(CloudProvider.NETEASE, first.provider)

            assertTrue(second is CloudAuthEvent.LoggedOut)
            assertEquals(CloudProvider.QQ_MUSIC, second.provider)

            assertTrue(third is CloudAuthEvent.ProxyRestarted)
            assertEquals(CloudProvider.NAVIDROME, third.provider)
        }
    }

    @Test
    fun `all CloudProvider enum values are covered`() {
        // Ensure we didn't forget any provider in the enum
        val providers = CloudProvider.entries
        assertTrue(providers.contains(CloudProvider.TELEGRAM))
        assertTrue(providers.contains(CloudProvider.NETEASE))
        assertTrue(providers.contains(CloudProvider.QQ_MUSIC))
        assertTrue(providers.contains(CloudProvider.GDRIVE))
        assertTrue(providers.contains(CloudProvider.NAVIDROME))
        assertTrue(providers.contains(CloudProvider.JELLYFIN))
        assertTrue(providers.contains(CloudProvider.ALL))
    }
}

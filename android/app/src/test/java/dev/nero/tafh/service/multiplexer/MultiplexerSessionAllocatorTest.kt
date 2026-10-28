package dev.nero.tafh.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiplexerSessionAllocatorTest {
    @Test
    fun reusesHighestDetachedTafhSessionOutsideLocalTabs() {
        val reusable =
            MultiplexerSessionAllocator.reusableDetachedTafhSessionName(
                remoteSessions =
                    listOf(
                        RemoteMultiplexerSession("tafh-1", attached = false),
                        RemoteMultiplexerSession("tafh-2", attached = false),
                        RemoteMultiplexerSession("tafh-3", attached = false),
                        RemoteMultiplexerSession("tafh-5", attached = true),
                        RemoteMultiplexerSession("tafh-7-extra", attached = false),
                    ),
                localSessionNames = listOf("tafh-3"),
            )

        assertEquals("tafh-2", reusable)
    }

    @Test
    fun doesNotReuseAttachedOrLocalTafhSessions() {
        val reusable =
            MultiplexerSessionAllocator.reusableDetachedTafhSessionName(
                remoteSessions =
                    listOf(
                        RemoteMultiplexerSession("tafh-1", attached = true),
                        RemoteMultiplexerSession("tafh-2", attached = false),
                        RemoteMultiplexerSession("work", attached = false),
                    ),
                localSessionNames = listOf("tafh-2"),
            )

        assertEquals(null, reusable)
    }

    @Test
    fun skipsUsedRemoteAndLocalNames() {
        val next =
            MultiplexerSessionAllocator.nextTafhSessionName(
                remoteSessions =
                    listOf(
                        RemoteMultiplexerSession("tafh-1", attached = false),
                        RemoteMultiplexerSession("main", attached = true),
                        RemoteMultiplexerSession("tafh-3", attached = false),
                    ),
                localSessionNames = listOf("tafh-2"),
            )

        assertEquals("tafh-4", next)
    }

    @Test
    fun ignoresPrefixCollisions() {
        val next =
            MultiplexerSessionAllocator.nextTafhSessionName(
                remoteSessions = listOf(RemoteMultiplexerSession("tafh-2", attached = false)),
                localSessionNames = listOf("tafh-1-extra"),
            )

        assertEquals("tafh-1", next)
    }
}

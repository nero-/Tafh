package com.jossephus.chuchu.ui.screens.Terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A remote server controls the names returned by `readdir`, and the download/open path turns them
 * into files inside the app's cache directory. These cases pin the sanitisation that keeps a
 * hostile name from escaping it.
 */
class RemoteFileNameSanitizerTest {
    @Test
    fun `plain names pass through`() {
        assertEquals("notes.txt", sanitizeRemoteFileName("notes.txt"))
    }

    @Test
    fun `parent traversal is reduced to a basename`() {
        assertEquals("chuchu.db", sanitizeRemoteFileName("../../databases/chuchu.db"))
        assertEquals("passwd", sanitizeRemoteFileName("/etc/passwd"))
    }

    @Test
    fun `backslash separators are treated as path separators`() {
        assertEquals("win.txt", sanitizeRemoteFileName("..\\..\\win.txt"))
    }

    @Test
    fun `dot names are replaced`() {
        assertEquals("remote_file", sanitizeRemoteFileName("."))
        assertEquals("remote_file", sanitizeRemoteFileName(".."))
        assertEquals("remote_file", sanitizeRemoteFileName("   "))
        assertEquals("remote_file", sanitizeRemoteFileName(""))
    }

    @Test
    fun `control characters are stripped`() {
        assertEquals("name.txt", sanitizeRemoteFileName("na\u0000me\u007f.txt"))
    }

    @Test
    fun `no result can contain a path separator`() {
        val hostile =
            listOf(
                "../../databases/chuchu.db",
                "..\\..\\files\\x",
                "/data/data/com.jossephus.chuchu/files/.ssh/id_ed25519",
                "a/../../b",
                "..",
            )
        for (name in hostile) {
            val sanitized = sanitizeRemoteFileName(name)
            assertFalse("'$name' leaked a separator", sanitized.contains('/'))
            assertFalse("'$name' leaked a separator", sanitized.contains('\\'))
            assertFalse("'$name' is still a traversal", sanitized == ".." || sanitized == ".")
        }
    }
}

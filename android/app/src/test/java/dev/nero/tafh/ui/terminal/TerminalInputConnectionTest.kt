package dev.nero.tafh.ui.terminal

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for the IME translation state machine in [TerminalInputView].
 *
 * Every assertion compares the exact character stream handed to the terminal, so a stale mirror
 * cannot silently over-emit backspaces or swallow a delete, and composing keyboards cannot hide
 * typing until the keyboard is closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalInputConnectionTest {

    private lateinit var view: TerminalInputView
    private lateinit var emitted: StringBuilder
    private val emittedKeys = mutableListOf<String>()

    @Before
    fun setUp() {
        view = TerminalInputView(ApplicationProvider.getApplicationContext<Context>())
        emitted = StringBuilder()
        emittedKeys.clear()
        view.onTerminalText = { text -> emitted.append(text) }
        view.onTerminalKey = { key, codepoint, mods, action, charCode ->
            emittedKeys.add("$key:$codepoint:$mods:$action:$charCode")
        }
    }

    private fun connection(): InputConnection = view.onCreateInputConnection(EditorInfo())

    private fun mirror(): String = view.editableText.toString()

    @Test
    fun `commit emits the text and delete emits one backspace`() {
        val conn = connection()

        conn.commitText("a", 1)
        assertEquals("a", emitted.toString())
        assertEquals("", mirror())

        conn.deleteSurroundingText(1, 0)
        assertEquals("a\u007f", emitted.toString())
    }

    @Test
    fun `commit text does not emit hardware key events`() {
        val conn = connection()

        conn.commitText("a", 1)
        conn.deleteSurroundingText(1, 0)

        assertEquals("a\u007f", emitted.toString())
        assertTrue(emittedKeys.isEmpty())
    }

    @Test
    fun `delete after a multi character commit emits one backspace per character`() {
        val conn = connection()

        conn.commitText("abc", 1)
        assertEquals("abc", emitted.toString())

        conn.deleteSurroundingText(2, 0)
        assertEquals("abc\u007f\u007f", emitted.toString())
    }

    @Test
    fun `consecutive commits never leave a stale mirror behind`() {
        val conn = connection()

        conn.commitText("ab", 1)
        conn.commitText("c", 1)
        assertEquals("abc", emitted.toString())
        assertEquals("", mirror())

        // Regression: a retained mirror used to turn this into a mass delete.
        conn.deleteSurroundingText(1, 0)
        assertEquals("abc\u007f", emitted.toString())
    }

    @Test
    fun `arming input suppression does not swallow a later delete`() {
        val conn = connection()
        conn.commitText("a", 1)
        assertEquals("a", emitted.toString())

        view.armInputSuppression("test")

        // Regression: a suppression window used to consume this delete.
        conn.deleteSurroundingText(1, 0)
        assertEquals("a\u007f", emitted.toString())
        assertEquals("", mirror())
    }

    @Test
    fun `composition is sent live and a matching commit adds nothing`() {
        val conn = connection()

        conn.setComposingText("か", 1)
        assertEquals("か", emitted.toString())
        assertEquals("か", mirror())

        conn.setComposingText("かん", 1)
        assertEquals("かん", emitted.toString())
        assertEquals("かん", mirror())

        conn.commitText("かん", 1)
        assertEquals("かん", emitted.toString())
        assertEquals("", mirror())
    }

    @Test
    fun `typing a word on a composing keyboard shows every letter immediately`() {
        val conn = connection()

        // Gboard/Samsung compose each word; the user must see every keystroke.
        conn.setComposingText("l", 1)
        assertEquals("l", emitted.toString())
        conn.setComposingText("ls", 1)
        assertEquals("ls", emitted.toString())

        // Space commits the word plus the space: only the space is new.
        conn.commitText("ls ", 1)
        assertEquals("ls ", emitted.toString())
    }

    @Test
    fun `backspace while composing erases the last letter on the terminal`() {
        val conn = connection()

        conn.setComposingText("lsx", 1)
        conn.setComposingText("ls", 1)

        assertEquals("lsx\u007f", emitted.toString())
        assertEquals("ls", mirror())
    }

    @Test
    fun `cancelling a composition erases it from the terminal`() {
        val conn = connection()

        conn.setComposingText("ab", 1)
        conn.setComposingText("", 1)

        assertEquals("ab\u007f\u007f", emitted.toString())
        assertEquals("", mirror())
    }

    @Test
    fun `autocorrect rewrites only the part of the word that changed`() {
        val conn = connection()

        conn.setComposingText("teh", 1)
        conn.commitText("the ", 1)

        assertEquals("teh\u007f\u007fhe ", emitted.toString())
    }

    @Test
    fun `conversion replaces the composed reading`() {
        val conn = connection()

        conn.setComposingText("かん", 1)
        conn.commitText("感", 1)

        assertEquals("かん\u007f\u007f感", emitted.toString())
    }

    @Test
    fun `rewriting an emoji in a composition erases it with one backspace`() {
        val conn = connection()
        val grin = "\uD83D\uDE00"
        val wink = "\uD83D\uDE09"

        conn.setComposingText(grin, 1)
        conn.commitText(wink, 1)

        assertEquals("$grin\u007f$wink", emitted.toString())
    }

    @Test
    fun `deleting during a composition removes the text before it`() {
        val conn = connection()

        conn.commitText("x", 1)
        conn.setComposingText("ab", 1)
        conn.deleteSurroundingText(1, 0)

        // The composition is retyped after the deleted character, and stays composing.
        assertEquals("xab\u007f\u007f\u007fab", emitted.toString())
        assertEquals("ab", mirror())

        conn.commitText("abc", 1)
        assertEquals("xab\u007f\u007f\u007fabc", emitted.toString())
    }

    @Test
    fun `a key sent during a composition keeps the composition and is not replayed`() {
        val conn = connection()

        conn.setComposingText("cd Doc", 1)
        view.armInputSuppression("tab")

        // The text stays on the terminal for the key (e.g. Tab completion) to act on, and a
        // later commit does not resend it.
        assertEquals("cd Doc", emitted.toString())
        assertEquals("", mirror())
        conn.commitText("x", 1)
        assertEquals("cd Docx", emitted.toString())
    }

    @Test
    fun `newlines are translated to carriage returns`() {
        val conn = connection()

        conn.commitText("a\nb", 1)
        assertEquals("a\rb", emitted.toString())
    }

    @Test
    fun `forward delete emits the delete escape sequence`() {
        val conn = connection()

        conn.deleteSurroundingText(0, 1)
        assertEquals("\u001b[3~", emitted.toString())
    }

    @Test
    fun `deleting a surrogate pair emits a single backspace`() {
        val conn = connection()
        val emoji = "\uD83D\uDE00"

        conn.commitText(emoji, 1)
        assertEquals(emoji, emitted.toString())

        conn.deleteSurroundingText(1, 0)
        assertEquals("$emoji\u007f", emitted.toString())
    }

    @Test
    fun `code point delete emits one backspace per code point`() {
        val conn = connection()

        conn.commitText("ab", 1)
        assertEquals("ab", emitted.toString())

        conn.deleteSurroundingTextInCodePoints(1, 0)
        assertEquals("ab\u007f", emitted.toString())
    }

    @Test
    fun `code point delete on a surrogate pair emits exactly one backspace`() {
        val conn = connection()
        val emoji = "\uD83D\uDE00"

        conn.commitText(emoji, 1)
        assertEquals(emoji, emitted.toString())

        conn.deleteSurroundingTextInCodePoints(1, 0)
        assertEquals("$emoji\u007f", emitted.toString())
        assertEquals("", mirror())
    }

    @Test
    fun `finish composing text emits nothing because the composition is already shown`() {
        val conn = connection()

        conn.setComposingText("かん", 1)
        assertEquals("かん", emitted.toString())

        conn.finishComposingText()

        assertEquals("かん", emitted.toString())
        assertEquals("", mirror())

        // The composition is final, so a later backspace is a plain delete.
        conn.deleteSurroundingText(1, 0)
        assertEquals("かん\u007f", emitted.toString())
    }

    @Test
    fun `finish composing text after commit does not emit twice`() {
        val conn = connection()

        conn.setComposingText("かん", 1)
        conn.commitText("かん", 1)
        assertEquals("かん", emitted.toString())

        conn.finishComposingText()

        assertEquals("かん", emitted.toString())
        assertEquals("", mirror())
    }

    @Test
    fun `suppression never gates committed text`() {
        val conn = connection()

        view.armInputSuppression("test")
        conn.commitText("a", 1)

        assertEquals("a", emitted.toString())
        assertEquals("", mirror())
    }

    @Test
    fun `forward delete during a composition reaches the terminal`() {
        val conn = connection()

        conn.setComposingText("\u304b\u3093", 2)
        assertEquals("\u304b\u3093", emitted.toString())

        conn.deleteSurroundingText(0, 1)
        assertEquals("\u304b\u3093\u007f\u007f\u001b[3~\u304b\u3093", emitted.toString())
    }

    @Test
    fun `shared prefix never splits a surrogate pair`() {
        assertEquals(0, sharedPrefixLength("\uD83D\uDE00", "\uD83D\uDE09"))
        assertEquals(1, sharedPrefixLength("a\uD83D\uDE00", "a\uD83D\uDE09"))
        assertEquals(3, sharedPrefixLength("a\uD83D\uDE00", "a\uD83D\uDE00b"))
        assertEquals(2, sharedPrefixLength("abc", "abd"))
    }
}

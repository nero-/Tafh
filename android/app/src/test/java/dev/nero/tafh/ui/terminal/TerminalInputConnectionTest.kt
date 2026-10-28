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
 * cannot silently over-emit backspaces or swallow a delete.
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
    fun `composition is not emitted until it is committed`() {
        val conn = connection()

        conn.setComposingText("か", 1)
        assertEquals("", emitted.toString())
        assertEquals("か", mirror())

        conn.setComposingText("かん", 1)
        assertEquals("", emitted.toString())
        assertEquals("かん", mirror())

        conn.commitText("かん", 1)
        assertEquals("かん", emitted.toString())
        assertEquals("", mirror())
    }

    @Test
    fun `deleting inside a composition emits nothing`() {
        val conn = connection()

        conn.setComposingText("abc", 1)
        conn.deleteSurroundingText(1, 0)
        assertEquals("", emitted.toString())
        assertEquals("", mirror())

        // Nothing was committed, so the cancelled text never reached the terminal.
        conn.commitText("abc", 1)
        assertEquals("abc", emitted.toString())
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
    fun `code point delete inside a composition emits nothing`() {
        val conn = connection()

        conn.setComposingText("\uD83D\uDE00", 1)
        conn.deleteSurroundingTextInCodePoints(1, 0)

        assertEquals("", emitted.toString())
        assertEquals("", mirror())
    }

    @Test
    fun `finish composing text commits the pending composition once`() {
        val conn = connection()

        conn.setComposingText("かん", 1)
        assertEquals("", emitted.toString())

        conn.finishComposingText()

        assertEquals("かん", emitted.toString())
        assertEquals("", mirror())

        // The composition is gone, so a later backspace must not replay it.
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
    fun `forward delete inside a composition is not swallowed`() {
        val conn = connection()

        // Composition with the caret after all three characters: nothing is composed after it,
        // so a forward delete must still reach the terminal.
        conn.setComposingText("\u304b\u3093", 2)
        assertEquals("", emitted.toString())

        conn.deleteSurroundingText(0, 1)
        assertEquals("\u001b[3~", emitted.toString())
    }

    @Test
    fun `backspace inside a composition is cancelled but outside text is not`() {
        val conn = connection()

        conn.setComposingText("\u304b\u3093", 2)
        conn.deleteSurroundingText(1, 0)
        assertEquals("", emitted.toString())

        conn.commitText("ab", 2)
        conn.deleteSurroundingText(1, 0)
        assertEquals("ab\u007f", emitted.toString())
    }
}

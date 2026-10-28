package com.jossephus.chuchu.ui.terminal

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

fun terminalImeInputType(disableAutocorrect: Boolean): Int {
    // TYPE_NULL matches Termux's default and prevents keyboards from treating terminal input as
    // prose.
    if (disableAutocorrect) return InputType.TYPE_NULL

    return InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
}

class TerminalInputView(context: Context) : EditText(context) {

    companion object {
        private const val LOG_TAG = "TerminalInput"
        private const val DEBUG_INPUT_LOGS = false
    }

    var onTerminalText: ((String) -> Unit)? = null
    var onTerminalKey: ((Int, Int, Int, Int, Int) -> Unit)? = null

    /** Active input connection, used for IME mirror resets. */
    private var activeInputConnection: TerminalInputConnection? = null

    /** Cached InputMethodManager for IME restarts. */
    private var inputMethodManager: InputMethodManager? = null

    var disableAutocorrect = false
        set(value) {
            if (field == value) return
            field = value
            inputType = terminalImeInputType(value)
            inputMethodManager?.restartInput(this)
        }

    private fun logInput(message: String) {
        if (!DEBUG_INPUT_LOGS) return
        Log.d(LOG_TAG, message)
    }

    private fun describeText(text: String): String = buildString {
        text.forEach { char ->
            when (char) {
                '\u001b' -> append("<ESC>")
                '\t' -> append("<TAB>")
                '\r' -> append("<CR>")
                '\n' -> append("<LF>")
                '\u007f' -> append("<BS>")
                else -> append(char)
            }
        }
    }

    /**
     * Keys that should drop the IME mirror buffer when delivered as hardware/Ghostty key events.
     * The terminal handles cursor motion and line editing itself, so the mirror would only get out
     * of sync.
     */
    private fun shouldInvalidateImeMirrorForKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DEL ||
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_TAB ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_MOVE_HOME ||
            keyCode == KeyEvent.KEYCODE_MOVE_END ||
            keyCode == KeyEvent.KEYCODE_PAGE_UP ||
            keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
            keyCode == KeyEvent.KEYCODE_INSERT ||
            keyCode == KeyEvent.KEYCODE_ESCAPE

    private fun emitTerminalText(source: String, text: String) {
        if (DEBUG_INPUT_LOGS) logInput("emit source=$source text=${describeText(text)}")
        onTerminalText?.invoke(text)
    }

    private fun emitBackspaceText(source: String) {
        if (DEBUG_INPUT_LOGS) logInput("emit source=$source text=<BS>")
        onTerminalText?.invoke("\u007f")
    }

    /**
     * Drops stale IME mirror state before a virtual key is delivered straight to the terminal, so
     * the IME cannot replay text the terminal already received.
     *
     * Only the mirror (composing spans, text, selection) is touched: nothing is remembered, no time
     * window is armed and the IME is never restarted, so every later IME edit stays authoritative.
     * Accessory keys repeat rapidly, and a restart there would both hammer the IME and discard its
     * composing state.
     */
    fun armInputSuppression(reason: String) {
        if (DEBUG_INPUT_LOGS) logInput("armSuppression reason=$reason")
        val connection = activeInputConnection
        if (connection != null) {
            connection.dropMirror("armInputSuppression")
        } else {
            clearMirrorBuffer("armInputSuppression")
        }
    }

    /** Empties the IME mirror without emitting anything to the terminal. */
    private fun clearMirrorBuffer(source: String) {
        val editable = editableText
        BaseInputConnection.removeComposingSpans(editable)
        if (editable.isNotEmpty()) editable.clear()
        Selection.setSelection(editable, 0)
        if (DEBUG_INPUT_LOGS) logInput("clearMirrorBuffer source=$source")
    }

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setTextColor(android.graphics.Color.TRANSPARENT)
        isCursorVisible = false
        isFocusableInTouchMode = true
        isFocusable = true
        setSingleLine(false)
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        inputType = terminalImeInputType(disableAutocorrect)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (DEBUG_INPUT_LOGS) {
            logInput(
                "onKeyDown keyCode=$keyCode unicode=${event.unicodeChar} meta=${event.metaState} flags=${event.flags}"
            )
        }
        val ghosttyAction = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount)
        val mapped = KeyMapper.map(keyCode, event.unicodeChar, event.metaState)
        if (mapped != null && ghosttyAction != null) {
            if (
                ghosttyAction == GhosttyKeyAction.Press && shouldInvalidateImeMirrorForKey(keyCode)
            ) {
                activeInputConnection?.invalidateImeMirror()
            }
            onTerminalKey?.invoke(
                mapped.key,
                mapped.codepoint,
                mapped.mods,
                ghosttyAction,
                mapped.charCode,
            )
            return true
        }
        val unicodeChar = event.unicodeChar
        if (unicodeChar != 0) {
            emitTerminalText("onKeyDown.unicode", unicodeChar.toChar().toString())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (DEBUG_INPUT_LOGS) logInput("onKeyUp keyCode=$keyCode flags=${event.flags}")
        val ghosttyAction = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount)
        val mapped = KeyMapper.map(keyCode, event.unicodeChar, event.metaState)
        if (mapped != null && ghosttyAction != null) {
            onTerminalKey?.invoke(
                mapped.key,
                mapped.codepoint,
                mapped.mods,
                ghosttyAction,
                mapped.charCode,
            )
            return true
        }
        if (event.unicodeChar != 0) return true
        return super.onKeyUp(keyCode, event)
    }

    fun showKeyboard(imm: InputMethodManager?) {
        if (imm == null) return
        inputMethodManager = imm
        if (!hasFocus()) {
            requestFocus()
            requestFocusFromTouch()
        }
        post {
            if (DEBUG_INPUT_LOGS) logInput("showKeyboard.restartInput")
            imm.restartInput(this)
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.imeOptions =
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_ACTION_NONE
        outAttrs.inputType = terminalImeInputType(disableAutocorrect)
        outAttrs.initialSelStart = selectionStart
        outAttrs.initialSelEnd = selectionEnd

        val conn = TerminalInputConnection(this)
        activeInputConnection = conn
        if (DEBUG_INPUT_LOGS) logInput("onCreateInputConnection conn=${conn.connectionId}")
        return conn
    }

    /**
     * IME translation state machine.
     *
     * The mirror [Editable] is empty whenever no composition is active: it holds exactly the
     * uncommitted composing region and never retained committed text. The terminal is the only
     * owner of committed input, so every IME callback is interpreted from that invariant instead of
     * from a before/after diff:
     * - [commitText] emits the committed characters once and clears the mirror.
     * - [setComposingText] only updates the mirror and emits nothing.
     * - [deleteSurroundingText] is authoritative: deletes aimed at the composing region are
     *   cancelled locally, the rest become [0x7f] / `ESC [ 3 ~`.
     */
    private class TerminalInputConnection(private val view: TerminalInputView) :
        BaseInputConnection(view, true) {

        val connectionId: Int = System.identityHashCode(this)

        /**
         * True while the IME owns an uncommitted composing region in the mirror. The mirror is
         * empty whenever this is false.
         */
        private var composing = false

        override fun getEditable(): Editable = view.editableText

        private fun logConn(message: String) {
            if (!DEBUG_INPUT_LOGS) return
            view.logInput("conn=$connectionId $message")
        }

        /** Drops the mirror and forgets any active composition. Emits nothing. */
        fun dropMirror(source: String) {
            if (DEBUG_INPUT_LOGS) logConn("dropMirror source=$source composing=$composing")
            composing = false
            view.clearMirrorBuffer(source)
        }

        fun invalidateImeMirror() {
            if (DEBUG_INPUT_LOGS) logConn("invalidateImeMirror")
            dropMirror("invalidateImeMirror")
        }

        private fun emitCommittedText(source: String, text: String) {
            if (text.isEmpty()) return
            // Enter arrives as LF from the IME; terminals expect CR.
            view.emitTerminalText(source, text.replace('\n', '\r'))
        }

        /**
         * Length in code points of the active composing region.
         *
         * The mirror holds only uncommitted text, so this is exactly what the terminal has never
         * seen. Counting code points (not UTF-16 units) keeps a surrogate pair a single character.
         */
        private fun composingCodePointCount(): Int {
            val editable = getEditable()
            if (editable.isEmpty()) return 0
            val start = BaseInputConnection.getComposingSpanStart(editable)
            val end = BaseInputConnection.getComposingSpanEnd(editable)
            val from = minOf(start, end)
            val to = maxOf(start, end)
            if (from < 0 || to <= from)
                return Character.codePointCount(editable, 0, editable.length)
            return Character.codePointCount(editable, from, to.coerceAtMost(editable.length))
        }

        /**
         * Shared delete translation for both IME delete variants.
         *
         * Deletes covered by the composition are cancelled locally: the terminal never received
         * that text, so it must not see bytes for it. Anything beyond the composition is a real
         * delete, one backspace per code point before the cursor and one `ESC [ 3 ~` per code point
         * after.
         */
        private fun emitDelete(source: String, beforeLength: Int, afterLength: Int) {
            val composed = if (composing) composingCodePointCount() else 0
            val requestedBefore = beforeLength.coerceAtLeast(0)
            val requestedAfter = afterLength.coerceAtLeast(0)
            val terminalBefore = requestedBefore - minOf(requestedBefore, composed)
            val terminalAfter = requestedAfter - minOf(requestedAfter, composed)
            dropMirror(source)

            repeat(terminalBefore) { view.emitBackspaceText(source) }
            repeat(terminalAfter) { view.emitTerminalText(source, "\u001b[3~") }
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val committed = text?.toString().orEmpty()
            if (DEBUG_INPUT_LOGS) {
                logConn(
                    "commitText text=${view.describeText(committed)} cursor=$newCursorPosition composing=$composing"
                )
            }
            // A commit supersedes whatever was composing and the mirror must not
            // keep committed text, so reset it before the text is emitted.
            dropMirror("commitText")
            emitCommittedText("commitText", committed)
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val composingText = text?.toString().orEmpty()
            if (DEBUG_INPUT_LOGS) {
                logConn(
                    "setComposingText text=${view.describeText(composingText)} cursor=$newCursorPosition"
                )
            }
            // An empty composing text cancels the composition: the IME discarded
            // what it had shown, so nothing may be committed to the terminal.
            if (composingText.isEmpty()) {
                dropMirror("setComposingText.empty")
                return true
            }

            // Composition is not committed yet: it lives in the mirror only and
            // the terminal must not see it until the IME commits.
            dropMirror("setComposingText")
            val ok = super.setComposingText(composingText, newCursorPosition)
            composing = true
            return ok
        }

        override fun finishComposingText(): Boolean {
            if (DEBUG_INPUT_LOGS) logConn("finishComposingText composing=$composing")
            // Some IMEs commit a composition through finishComposingText instead
            // of commitText. The pending composition has then become real input
            // and must be emitted exactly once; after commitText the mirror is
            // already empty, so this stays silent in the normal
            // setComposingText -> commitText -> finishComposingText sequence.
            val pending = if (composing) getEditable().toString() else ""
            val ok = super.finishComposingText()
            dropMirror("finishComposingText")
            emitCommittedText("finishComposingText", pending)
            return ok
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            if (DEBUG_INPUT_LOGS) logConn("setComposingRegion start=$start end=$end")
            // An empty region leaves no composition behind, so the mirror must not
            // keep any text: drop it before the region is applied.
            if (start == end) {
                dropMirror("setComposingRegion.empty")
                return true
            }
            val ok = super.setComposingRegion(start, end)
            composing = ok && getEditable().isNotEmpty()
            return ok
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            if (DEBUG_INPUT_LOGS) logConn("setSelection start=$start end=$end")
            return super.setSelection(start, end)
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            val editable = getEditable()
            val extracted =
                ExtractedText().apply {
                    text = editable.toString()
                    startOffset = 0
                    partialStartOffset = -1
                    partialEndOffset = -1
                    selectionStart = Selection.getSelectionStart(editable).coerceAtLeast(0)
                    selectionEnd = Selection.getSelectionEnd(editable).coerceAtLeast(0)
                }
            if (DEBUG_INPUT_LOGS) {
                logConn(
                    "getExtractedText flags=$flags text=${view.describeText(extracted.text.toString())} sel=${extracted.selectionStart}..${extracted.selectionEnd}"
                )
            }
            return extracted
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (DEBUG_INPUT_LOGS) {
                logConn(
                    "deleteSurroundingText beforeLen=$beforeLength afterLen=$afterLength composing=$composing"
                )
            }
            emitDelete("deleteSurroundingText", beforeLength, afterLength)
            return true
        }

        /**
         * Samsung keyboards and other IMEs use the code-point variant for backspace; the base
         * implementation only edits the mirror, so it would send nothing.
         */
        override fun deleteSurroundingTextInCodePoints(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean {
            if (DEBUG_INPUT_LOGS) {
                logConn(
                    "deleteSurroundingTextInCodePoints beforeLen=$beforeLength afterLen=$afterLength composing=$composing"
                )
            }
            emitDelete("deleteSurroundingTextInCodePoints", beforeLength, afterLength)
            return true
        }

        override fun beginBatchEdit(): Boolean {
            if (DEBUG_INPUT_LOGS) logConn("beginBatchEdit")
            return super.beginBatchEdit()
        }

        override fun endBatchEdit(): Boolean {
            if (DEBUG_INPUT_LOGS) logConn("endBatchEdit")
            return super.endBatchEdit()
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (DEBUG_INPUT_LOGS) {
                logConn(
                    "sendKeyEvent action=${event.action} keyCode=${event.keyCode} unicode=${event.unicodeChar} meta=${event.metaState} flags=${event.flags}"
                )
            }
            val ghosttyAction = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount)
            if (ghosttyAction != null) {
                val mapped = KeyMapper.map(event.keyCode, event.unicodeChar, event.metaState)
                if (mapped != null) {
                    if (
                        ghosttyAction == GhosttyKeyAction.Press &&
                            view.shouldInvalidateImeMirrorForKey(event.keyCode)
                    ) {
                        invalidateImeMirror()
                    }
                    view.onTerminalKey?.invoke(
                        mapped.key,
                        mapped.codepoint,
                        mapped.mods,
                        ghosttyAction,
                        mapped.charCode,
                    )
                    return true
                }
            }
            return super.sendKeyEvent(event)
        }
    }
}

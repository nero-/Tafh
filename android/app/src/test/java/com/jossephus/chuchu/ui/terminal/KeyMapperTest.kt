package com.jossephus.chuchu.ui.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain JUnit: `KeyMapper` only reads `android.view.KeyEvent` constants, which are compile-time
 * inlined, so no Robolectric runtime is needed.
 */
class KeyMapperTest {

    @Test
    fun `volume up falls through to the system`() {
        assertNull(KeyMapper.map(KeyEvent.KEYCODE_VOLUME_UP, codepoint = 0, metaState = 0))
    }

    @Test
    fun `volume down falls through to the system`() {
        assertNull(KeyMapper.map(KeyEvent.KEYCODE_VOLUME_DOWN, codepoint = 0, metaState = 0))
    }

    @Test
    fun `volume keys stay null even with modifiers held`() {
        val metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
        assertNull(
            KeyMapper.map(KeyEvent.KEYCODE_VOLUME_UP, codepoint = 0, metaState = metaState)
        )
        assertNull(
            KeyMapper.map(KeyEvent.KEYCODE_VOLUME_DOWN, codepoint = 0, metaState = metaState)
        )
    }

    @Test
    fun `delete maps to backspace with no modifiers`() {
        val mapped = KeyMapper.map(KeyEvent.KEYCODE_DEL, codepoint = 0, metaState = 0)
        assertNotNull(mapped)
        assertEquals(GhosttyKey.backspace, mapped!!.key)
        assertEquals(0, mapped.mods)
    }

    @Test
    fun `shift sets mod bit 0`() {
        assertEquals(1 shl 0, modsFor(KeyEvent.META_SHIFT_ON))
    }

    @Test
    fun `ctrl sets mod bit 1`() {
        assertEquals(1 shl 1, modsFor(KeyEvent.META_CTRL_ON))
    }

    @Test
    fun `alt sets mod bit 2`() {
        assertEquals(1 shl 2, modsFor(KeyEvent.META_ALT_ON))
    }

    @Test
    fun `meta sets mod bit 3`() {
        assertEquals(1 shl 3, modsFor(KeyEvent.META_META_ON))
    }

    @Test
    fun `modifier bits combine`() {
        val metaState = KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
        assertEquals((1 shl 0) or (1 shl 1) or (1 shl 2), modsFor(metaState))
    }

    @Test
    fun `unmapped key with codepoint falls back to key zero plus codepoint`() {
        val codepoint = 'é'.code
        val mapped = KeyMapper.map(KeyEvent.KEYCODE_UNKNOWN, codepoint = codepoint, metaState = 0)
        assertNotNull(mapped)
        assertEquals(0, mapped!!.key)
        assertEquals(codepoint, mapped.codepoint)
    }

    @Test
    fun `unmapped key without codepoint is not consumed`() {
        assertNull(KeyMapper.map(KeyEvent.KEYCODE_UNKNOWN, codepoint = 0, metaState = 0))
    }

    private fun modsFor(metaState: Int): Int {
        val mapped = KeyMapper.map(KeyEvent.KEYCODE_DEL, codepoint = 0, metaState = metaState)
        assertNotNull(mapped)
        return mapped!!.mods
    }
}

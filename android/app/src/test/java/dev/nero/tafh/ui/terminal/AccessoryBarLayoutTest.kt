package dev.nero.tafh.ui.terminal

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessoryBarLayoutTest {

    @Test
    fun `two rows split the layout in half and keep its order`() {
        val rows = splitIntoRows((1..12).toList(), 2)
        assertEquals(listOf((1..6).toList(), (7..12).toList()), rows)
    }

    @Test
    fun `an odd layout puts the extra key on the top row`() {
        val rows = splitIntoRows((1..13).toList(), 2)
        assertEquals(7, rows[0].size)
        assertEquals(6, rows[1].size)
    }

    @Test
    fun `a single row keeps every key`() {
        assertEquals(listOf((1..5).toList()), splitIntoRows((1..5).toList(), 1))
    }

    @Test
    fun `an empty layout has no rows`() {
        assertTrue(splitIntoRows(emptyList<Int>(), 2).isEmpty())
    }

    @Test
    fun `rows do not depend on the screen width`() {
        // Regression: the FlowRow wrapped wherever the width ran out, so folding the phone moved
        // keys between rows. The split is now a function of the layout alone.
        val layout = TerminalAccessoryLayoutStore.defaultEntries().map { it.id }
        val rows = splitIntoRows(layout, 2)
        assertEquals(listOf("escape", "tab", "ctrl", "alt", "shift", "cmd"), rows[0])
        assertEquals(listOf("left", "up", "down", "right", "space", "enter"), rows[1])
    }

    @Test
    fun `keys share the width equally`() {
        // 8 columns and 34dp of gaps on a 330dp folded screen.
        assertEquals(37.dp, accessoryKeyWidth(330.dp, 8, 34.dp))
    }

    @Test
    fun `keys stop growing on wide screens`() {
        assertEquals(MAX_KEY_WIDTH, accessoryKeyWidth(900.dp, 8, 34.dp))
    }

    @Test
    fun `too many keys fall below the minimum so the bar scrolls`() {
        assertTrue(accessoryKeyWidth(330.dp, 15, 70.dp) < MIN_KEY_WIDTH)
    }

    @Test
    fun `labels keep their size when they fit`() {
        assertEquals(13f, fittedLabelSize(3, MAX_KEY_WIDTH, 1f, 13f).value, 0f)
    }

    @Test
    fun `labels shrink to fit a narrow key`() {
        val size = fittedLabelSize(5, 37.dp, 1f, 13f).value
        assertTrue("got $size", size in 9f..11f)
    }

    @Test
    fun `a larger font scale shrinks the label further`() {
        val normal = fittedLabelSize(5, 37.dp, 1f, 13f).value
        val scaled = fittedLabelSize(5, 37.dp, 1.3f, 13f).value
        assertTrue(scaled < normal)
    }

    @Test
    fun `labels never shrink below the readable minimum`() {
        assertEquals(9f, fittedLabelSize(12, MIN_KEY_WIDTH, 2f, 13f).value, 0f)
    }
}

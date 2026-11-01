package dev.nero.tafh.ui.screens.Terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactStatusPathTest {

    @Test
    fun `short paths are shown whole`() {
        assertEquals("/", compactStatusPath("/"))
        assertEquals("~/code/tafh", compactStatusPath("~/code/tafh"))
    }

    @Test
    fun `deep paths keep the last two folders`() {
        assertEquals("…/android/app", compactStatusPath("/home/nero/Code/tafh/android/app"))
        assertEquals("…/android/app", compactStatusPath("/home/nero/Code/tafh/android/app/"))
    }
}

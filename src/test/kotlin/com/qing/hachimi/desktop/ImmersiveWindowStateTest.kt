package com.qing.hachimi.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImmersiveWindowStateTest {
    @Test
    fun `restores the original placement and size after repeated fullscreen changes`() {
        val window = ImmersiveWindowState()
        val initialSize = DpSize(1280.dp, 820.dp)

        window.enter(WindowPlacement.Floating, initialSize)
        window.enter(WindowPlacement.Maximized, DpSize(1920.dp, 1080.dp))

        assertEquals(SavedWindowPlacement(WindowPlacement.Floating, initialSize), window.exit())
        assertNull(window.exit())
    }

    @Test
    fun `saves fullscreen as the prior placement when immersive mode starts there`() {
        val window = ImmersiveWindowState()
        val prior = SavedWindowPlacement(WindowPlacement.Fullscreen, DpSize(1920.dp, 1080.dp))

        window.enter(prior.placement, prior.size)

        assertEquals(prior, window.exit())
    }
}

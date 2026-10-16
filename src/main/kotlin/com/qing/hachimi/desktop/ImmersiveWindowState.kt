package com.qing.hachimi.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPlacement

data class SavedWindowPlacement(val placement: WindowPlacement, val size: DpSize)

/** Keeps the pre-immersive window placement stable across fullscreen toggles. */
class ImmersiveWindowState {
    private var saved: SavedWindowPlacement? = null

    fun enter(currentPlacement: WindowPlacement, currentSize: DpSize) {
        if (saved == null) saved = SavedWindowPlacement(currentPlacement, currentSize)
    }

    fun savedPlacement(): SavedWindowPlacement? = saved

    fun exit(): SavedWindowPlacement? = saved.also { saved = null }
}

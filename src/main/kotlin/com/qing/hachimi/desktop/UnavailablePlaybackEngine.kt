package com.qing.hachimi.desktop

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Explicit failed backend used when libmpv is unavailable; it never falls back to JavaFX. */
class UnavailablePlaybackEngine(private val userMessage: String) : PlaybackEngine {
    override val events: Flow<PlaybackEvent> = emptyFlow()

    override suspend fun load(track: LocalTrack, sessionId: PlaybackSessionId) {
        throw IllegalStateException(userMessage)
    }

    override suspend fun play(sessionId: PlaybackSessionId) = unavailable()
    override suspend fun pause(sessionId: PlaybackSessionId) = unavailable()
    override suspend fun stop(sessionId: PlaybackSessionId?) = Unit
    override suspend fun seek(sessionId: PlaybackSessionId, positionMillis: Long) = unavailable()
    override suspend fun setVolume(sessionId: PlaybackSessionId, volume: Float) = unavailable()
    override suspend fun close() = Unit

    private fun unavailable(): Nothing = throw IllegalStateException(userMessage)
}

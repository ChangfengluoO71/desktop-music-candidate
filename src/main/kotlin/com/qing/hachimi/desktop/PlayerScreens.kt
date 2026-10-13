package com.qing.hachimi.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.nio.file.Path
import java.util.Locale

@Composable
internal fun PlayerMiniBar(viewModel: PlayerViewModel) {
    val state by viewModel.state.collectAsState()
    PlayerMiniBarContent(
        state = state,
        onOpenNowPlaying = viewModel::openNowPlaying,
        onPrevious = viewModel::previous,
        onTogglePlayback = viewModel::togglePlayback,
        onNext = viewModel::next,
        onSeek = viewModel::seek,
    )
    PlayerNowPlayingDialog(viewModel)
}

@Composable
internal fun PlayerMiniBarContent(
    state: PlayerUiState,
    onOpenNowPlaying: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val playback = state.playback
    val track = playback.currentTrack
    var sliderValue by remember(track?.pathKey) { mutableFloatStateOf(progressOf(playback)) }
    var dragging by remember(track?.pathKey) { mutableStateOf(false) }
    LaunchedEffect(playback.positionMillis, playback.durationMillis) {
        if (!dragging) sliderValue = progressOf(playback)
    }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.09f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("player-mini-bar"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (track != null) PlayerArtwork(track, 48.dp)
            else Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Text("♫") }
            Column(Modifier.weight(1f).clickable(onClick = onOpenNowPlaying)) {
                Text(track?.title ?: "本地音乐播放器", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    track?.artists.orEmpty().joinToString().ifBlank { track?.album ?: "选择本地歌曲开始播放" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.body2,
                )
            }
            Button(modifier = Modifier.testTag("player-previous"), enabled = track != null, onClick = onPrevious) { Text("上一首") }
            Button(
                modifier = Modifier.testTag("player-toggle"),
                enabled = track != null || playback.queue.isNotEmpty(),
                colors = ButtonDefaults.buttonColorsPrimary(),
                onClick = onTogglePlayback,
            ) { Text(if (playback.isPlaying) "暂停" else "播放") }
            Button(modifier = Modifier.testTag("player-next"), enabled = track != null, onClick = onNext) { Text("下一首") }
            Button(modifier = Modifier.testTag("player-open-now-playing"), onClick = onOpenNowPlaying) { Text("歌词 / 队列") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatPlayerTime(playback.positionMillis), style = MiuixTheme.textStyles.body2)
            Slider(
                value = sliderValue,
                onValueChange = {
                    sliderValue = it
                    dragging = true
                },
                modifier = Modifier.weight(1f).height(24.dp).testTag("player-seek"),
                enabled = playback.durationMillis > 0,
                valueRange = 0f..1f,
                onValueChangeFinished = {
                    dragging = false
                    onSeek((sliderValue * playback.durationMillis).toLong())
                },
            )
            Text(formatPlayerTime(playback.durationMillis), style = MiuixTheme.textStyles.body2)
        }
        playback.error?.let { Text(it, color = MiuixTheme.colorScheme.primary, modifier = Modifier.testTag("player-error")) }
    }
}

@Composable
private fun PlayerNowPlayingDialog(viewModel: PlayerViewModel) {
    val state by viewModel.state.collectAsState()
    if (!state.isNowPlayingOpen) return
    val playback = state.playback
    val track = playback.currentTrack
    var showQueue by remember { mutableStateOf(false) }
    var sliderValue by remember(track?.pathKey) { mutableFloatStateOf(progressOf(playback)) }
    var dragging by remember(track?.pathKey) { mutableStateOf(false) }
    LaunchedEffect(playback.positionMillis, playback.durationMillis) {
        if (!dragging) sliderValue = progressOf(playback)
    }

    DialogWindow(
        onCloseRequest = viewModel::closeNowPlaying,
        title = "正在播放 · DMC",
        state = rememberDialogState(width = 1040.dp, height = 720.dp),
        resizable = true,
    ) {
        Row(
            Modifier.fillMaxSize()
                .background(MiuixTheme.colorScheme.background)
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(
                Modifier.weight(0.9f).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (track != null) PlayerArtwork(track, 340.dp)
                else Box(
                    Modifier.size(340.dp).clip(RoundedCornerShape(18.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) { Text("暂无正在播放的歌曲") }
                Text(track?.title ?: "本地音乐播放器", style = MiuixTheme.textStyles.headline1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(track?.artists?.joinToString()?.takeIf(String::isNotBlank), track?.album?.takeIf(String::isNotBlank)).joinToString(" · "),
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(formatPlayerTime(playback.positionMillis), style = MiuixTheme.textStyles.body2)
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it; dragging = true },
                        modifier = Modifier.weight(1f).height(28.dp).testTag("now-playing-seek"),
                        enabled = playback.durationMillis > 0,
                        valueRange = 0f..1f,
                        onValueChangeFinished = {
                            dragging = false
                            viewModel.seek((sliderValue * playback.durationMillis).toLong())
                        },
                    )
                    Text(formatPlayerTime(playback.durationMillis), style = MiuixTheme.textStyles.body2)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = track != null, onClick = viewModel::previous) { Text("上一首") }
                    Button(
                        enabled = track != null || playback.queue.isNotEmpty(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        onClick = viewModel::togglePlayback,
                    ) { Text(if (playback.isPlaying) "暂停" else "播放") }
                    Button(enabled = track != null, onClick = viewModel::next) { Text("下一首") }
                    Button(onClick = { viewModel.setShuffle(!playback.shuffle) }) { Text(if (playback.shuffle) "随机：开" else "随机：关") }
                    Button(onClick = {
                        viewModel.setRepeatMode(
                            when (playback.repeatMode) {
                                PlaybackRepeatMode.OFF -> PlaybackRepeatMode.ALL
                                PlaybackRepeatMode.ALL -> PlaybackRepeatMode.ONE
                                PlaybackRepeatMode.ONE -> PlaybackRepeatMode.OFF
                            },
                        )
                    }) { Text("循环：${when (playback.repeatMode) { PlaybackRepeatMode.OFF -> "关"; PlaybackRepeatMode.ALL -> "列表"; PlaybackRepeatMode.ONE -> "单曲" }}") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("音量")
                    Slider(
                        value = playback.volume,
                        onValueChange = viewModel::setVolume,
                        modifier = Modifier.weight(1f).height(28.dp).testTag("player-volume"),
                        valueRange = 0f..1f,
                    )
                }
                track?.let { Text(it.audioQuality.summary(), style = MiuixTheme.textStyles.body2) }
                playback.error?.let { Text(it, color = MiuixTheme.colorScheme.primary) }
            }

            Column(Modifier.weight(1.1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (showQueue) "播放队列 · ${playback.queue.size} 首" else "歌词", style = MiuixTheme.textStyles.headline2)
                    Spacer(Modifier.weight(1f))
                    Button(modifier = Modifier.testTag("now-playing-toggle-queue"), onClick = { showQueue = !showQueue }) {
                        Text(if (showQueue) "显示歌词" else "播放队列 (${playback.queue.size})")
                    }
                }
                if (showQueue) {
                    if (playback.queue.isEmpty()) Text("队列为空。")
                    LazyColumn(Modifier.fillMaxSize().testTag("now-playing-queue"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(playback.queue, key = { _, item -> item.pathKey }) { index, item ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (index == playback.queueIndex) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else MiuixTheme.colorScheme.background)
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(Modifier.weight(1f).clickable { viewModel.playQueueIndex(index) }) {
                                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(item.artists.joinToString().ifBlank { item.album }, style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Button(onClick = { viewModel.removeFromQueue(index) }) { Text("移除") }
                            }
                        }
                    }
                    Button(enabled = playback.queue.isNotEmpty(), onClick = viewModel::clearQueue) { Text("清空队列") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LyricVariant.entries.forEach { variant ->
                            val lines = state.lyrics.lines(variant)
                            if (lines.isNotEmpty()) {
                                Button(
                                    colors = if (variant == state.lyricVariant) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                                    modifier = Modifier.testTag("lyric-variant-${variant.name.lowercase(Locale.ROOT)}"),
                                    onClick = { viewModel.selectLyricVariant(variant) },
                                ) { Text(variant.label()) }
                            }
                        }
                    }
                    when {
                        state.isLoadingLyrics -> Text("正在读取本地歌词…")
                        state.lyricsUnavailable -> Text("未找到内嵌歌词或同名 .lrc / .tl.lrc / .roma.lrc 文件。")
                        else -> {
                            val activeIndex = state.activeLyricIndex
                            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                            LaunchedEffect(activeIndex, state.lyricVariant) {
                                if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
                            }
                            LazyColumn(
                                Modifier.fillMaxSize().testTag("now-playing-lyrics"),
                                state = listState,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                itemsIndexed(state.activeLyrics, key = { index, line -> "$index-${line.timeMillis}" }) { index, line ->
                                    Text(
                                        line.text,
                                        style = if (index == activeIndex) MiuixTheme.textStyles.headline2 else MiuixTheme.textStyles.body1,
                                        color = if (index == activeIndex) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground,
                                        modifier = Modifier.fillMaxWidth().testTag("lyric-line-$index"),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerArtwork(track: LocalTrack, size: androidx.compose.ui.unit.Dp) {
    val payload by produceState<ArtworkPayload?>(initialValue = null, track.filePath) {
        value = withContext(Dispatchers.IO) {
            runCatching { LocalArtworkResolver().resolve(Path.of(track.filePath)) }.getOrNull()
        }
    }
    val bitmap = remember(payload) { payload?.let { runCatching { it.bytes.decodeToImageBitmap() }.getOrNull() } }
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(bitmap),
            contentDescription = "${track.album} 封面",
            modifier = Modifier.size(size).clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Text("无封面") }
    }
}

private fun progressOf(playback: PlaybackState): Float =
    if (playback.durationMillis <= 0L) 0f else (playback.positionMillis.toFloat() / playback.durationMillis).coerceIn(0f, 1f)

private fun formatPlayerTime(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(Locale.ROOT, totalSeconds / 60L, totalSeconds % 60L)
}

private fun LyricVariant.label(): String = when (this) {
    LyricVariant.ORIGINAL -> "原文"
    LyricVariant.TRANSLATION -> "翻译"
    LyricVariant.ROMANIZATION -> "罗马音"
}

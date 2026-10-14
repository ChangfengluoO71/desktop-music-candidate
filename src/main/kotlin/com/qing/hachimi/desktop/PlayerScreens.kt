package com.qing.hachimi.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun PlayerMiniBar(
    viewModel: PlayerViewModel,
    onOpenNowPlaying: () -> Unit,
    artworkRepository: LocalArtworkRepository? = null,
) {
    val state by viewModel.state.collectAsState()
    PlayerMiniBarContent(
        state = state,
        artworkRepository = artworkRepository,
        onOpenNowPlaying = onOpenNowPlaying,
        onPrevious = viewModel::previous,
        onTogglePlayback = viewModel::togglePlayback,
        onNext = viewModel::next,
        onSeek = viewModel::seek,
    )
}

@Composable
internal fun PlayerMiniBarContent(
    state: PlayerUiState,
    artworkRepository: LocalArtworkRepository? = null,
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
            if (track != null) PlayerArtwork(track, 48.dp, artworkRepository)
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
internal fun NowPlayingScreen(viewModel: PlayerViewModel, artworkRepository: LocalArtworkRepository? = null) {
    val state by viewModel.state.collectAsState()
    val playback = state.playback
    val track = playback.currentTrack
    var showQueue by remember { mutableStateOf(false) }
    var sliderValue by remember(track?.pathKey) { mutableFloatStateOf(progressOf(playback)) }
    var dragging by remember(track?.pathKey) { mutableStateOf(false) }
    LaunchedEffect(playback.positionMillis, playback.durationMillis) {
        if (!dragging) sliderValue = progressOf(playback)
    }

    BoxWithConstraints(Modifier.fillMaxSize().testTag("now-playing-screen")) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        val compact = availableWidth < 720.dp || availableHeight < 540.dp
        val artworkSize = if (compact) minOf(160.dp, availableWidth * 0.28f, availableHeight * 0.32f)
        else minOf(340.dp, availableWidth * 0.34f, availableHeight * 0.52f)
        val contentModifier = Modifier.fillMaxSize()
            .then(rememberPlayerKeyboardShortcutModifier { it.perform(viewModel) })
            .background(MiuixTheme.colorScheme.background)
            .padding(if (compact) 12.dp else 24.dp)
        if (compact) {
            Column(contentModifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NowPlayingTransportPane(
                    modifier = Modifier.fillMaxWidth().heightIn(max = minOf(300.dp, availableHeight * 0.48f)).testTag("now-playing-transport"),
                    compact = true,
                    artworkSize = artworkSize,
                    artworkRepository = artworkRepository,
                    track = track,
                    playback = playback,
                    viewModel = viewModel,
                    sliderValue = sliderValue,
                    dragging = dragging,
                    onSliderValue = { sliderValue = it },
                    onDragging = { dragging = it },
                )
                HorizontalDivider()
                NowPlayingLyricsPane(
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("now-playing-lyrics-panel"),
                    state = state,
                    playback = playback,
                    viewModel = viewModel,
                    showQueue = showQueue,
                    onToggleQueue = { showQueue = !showQueue },
                )
            }
        } else {
            Row(contentModifier, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                NowPlayingTransportPane(
                    modifier = Modifier.weight(0.9f).fillMaxHeight().testTag("now-playing-transport"),
                    compact = false,
                    artworkSize = artworkSize,
                    artworkRepository = artworkRepository,
                    track = track,
                    playback = playback,
                    viewModel = viewModel,
                    sliderValue = sliderValue,
                    dragging = dragging,
                    onSliderValue = { sliderValue = it },
                    onDragging = { dragging = it },
                )
                NowPlayingLyricsPane(
                    modifier = Modifier.weight(1.1f).fillMaxHeight().testTag("now-playing-lyrics-panel"),
                    state = state,
                    playback = playback,
                    viewModel = viewModel,
                    showQueue = showQueue,
                    onToggleQueue = { showQueue = !showQueue },
                )
            }
        }
    }
}

@Composable
private fun NowPlayingTransportPane(
    modifier: Modifier,
    compact: Boolean,
    artworkSize: androidx.compose.ui.unit.Dp,
    artworkRepository: LocalArtworkRepository?,
    track: LocalTrack?,
    playback: PlaybackState,
    viewModel: PlayerViewModel,
    sliderValue: Float,
    dragging: Boolean,
    onSliderValue: (Float) -> Unit,
    onDragging: (Boolean) -> Unit,
) {
    if (compact) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (track != null) PlayerArtwork(track, artworkSize, artworkRepository)
            else Box(
                Modifier.size(artworkSize).clip(RoundedCornerShape(18.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Text("暂无正在播放的歌曲") }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(track?.title ?: "本地音乐播放器", style = MiuixTheme.textStyles.headline2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(track?.artists?.joinToString()?.takeIf(String::isNotBlank), track?.album?.takeIf(String::isNotBlank)).joinToString(" · "),
                    style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                PlayerSeekControls(playback, sliderValue, dragging, onSliderValue, onDragging, viewModel, "now-playing-seek")
                PlayerTransportButtons(playback, viewModel)
                PlayerModesAndVolume(playback, viewModel, compact = true)
            }
        }
    } else {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (track != null) PlayerArtwork(track, artworkSize, artworkRepository)
            else Box(
                Modifier.size(artworkSize).clip(RoundedCornerShape(18.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Text("暂无正在播放的歌曲") }
            Text(track?.title ?: "本地音乐播放器", style = MiuixTheme.textStyles.headline1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(track?.artists?.joinToString()?.takeIf(String::isNotBlank), track?.album?.takeIf(String::isNotBlank)).joinToString(" · "),
                style = MiuixTheme.textStyles.body1, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            PlayerSeekControls(playback, sliderValue, dragging, onSliderValue, onDragging, viewModel, "now-playing-seek")
            PlayerTransportButtons(playback, viewModel)
            PlayerModesAndVolume(playback, viewModel, compact = false)
            track?.let { Text(it.audioQuality.summary(), style = MiuixTheme.textStyles.body2) }
            playback.error?.let { Text(it, color = MiuixTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun PlayerSeekControls(
    playback: PlaybackState,
    sliderValue: Float,
    dragging: Boolean,
    onSliderValue: (Float) -> Unit,
    onDragging: (Boolean) -> Unit,
    viewModel: PlayerViewModel,
    tag: String,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(formatPlayerTime(playback.positionMillis), style = MiuixTheme.textStyles.body2)
        Slider(
            value = sliderValue,
            onValueChange = { onSliderValue(it); onDragging(true) },
            modifier = Modifier.weight(1f).height(28.dp).testTag(tag),
            enabled = playback.durationMillis > 0,
            valueRange = 0f..1f,
            onValueChangeFinished = {
                onDragging(false)
                viewModel.seek((sliderValue * playback.durationMillis).toLong())
            },
        )
        Text(formatPlayerTime(playback.durationMillis), style = MiuixTheme.textStyles.body2)
    }
}

@Composable
private fun PlayerTransportButtons(playback: PlaybackState, viewModel: PlayerViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(enabled = playback.currentTrack != null, onClick = viewModel::previous) { Text("上一首") }
        Button(
            enabled = playback.currentTrack != null || playback.queue.isNotEmpty(),
            colors = ButtonDefaults.buttonColorsPrimary(), onClick = viewModel::togglePlayback,
        ) { Text(if (playback.isPlaying) "暂停" else "播放") }
        Button(enabled = playback.currentTrack != null, onClick = viewModel::next) { Text("下一首") }
    }
}

@Composable
private fun PlayerModesAndVolume(playback: PlaybackState, viewModel: PlayerViewModel, compact: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
        if (!compact) Text("音量")
        Slider(value = playback.volume, onValueChange = viewModel::setVolume, modifier = Modifier.weight(1f).height(24.dp).testTag("player-volume"), valueRange = 0f..1f)
    }
}

@Composable
private fun NowPlayingLyricsPane(
    modifier: Modifier,
    state: PlayerUiState,
    playback: PlaybackState,
    viewModel: PlayerViewModel,
    showQueue: Boolean,
    onToggleQueue: () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (showQueue) "播放队列 · ${playback.queue.size} 首" else "歌词", style = MiuixTheme.textStyles.headline2)
            Spacer(Modifier.weight(1f))
            Button(modifier = Modifier.testTag("now-playing-toggle-queue"), onClick = onToggleQueue) {
                Text(if (showQueue) "显示歌词" else "播放队列 (${playback.queue.size})")
            }
        }
        if (showQueue) {
            PlayerQueueContent(
                playback = playback,
                onPlayIndex = viewModel::playQueueIndex,
                onMoveQueueItem = viewModel::moveQueueItem,
                onRemove = viewModel::removeFromQueue,
                onClear = viewModel::clearQueue,
            )
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
                else -> PlayerLyricsContent(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    lines = state.activeLyrics,
                    activeIndex = state.activeLyricIndex,
                    trackKey = playback.currentTrack?.pathKey,
                    variant = state.lyricVariant,
                    onSeek = viewModel::seek,
                )
            }
        }
    }
}
@Composable
internal fun PlayerLyricsContent(
    modifier: Modifier = Modifier,
    lines: List<TimedLyricLine>,
    activeIndex: Int,
    trackKey: String?,
    variant: LyricVariant,
    onSeek: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    var followLyrics by remember(trackKey, variant) { mutableStateOf(true) }
    var automaticScroll by remember { mutableStateOf(false) }

    LaunchedEffect(listState.isScrollInProgress, automaticScroll) {
        if (listState.isScrollInProgress && !automaticScroll) followLyrics = false
    }
    LaunchedEffect(activeIndex, followLyrics, trackKey, variant) {
        if (followLyrics && activeIndex >= 0) {
            automaticScroll = true
            try {
                listState.animateScrollToItem(activeIndex)
            } finally {
                automaticScroll = false
            }
        }
    }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!followLyrics) {
            Button(modifier = Modifier.testTag("lyric-follow-resume"), onClick = { followLyrics = true }) {
                Text("恢复跟随当前歌词")
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("now-playing-lyrics"),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(lines, key = { index, line -> "$index-${line.timeMillis}" }) { index, line ->
                Text(
                    line.text,
                    style = if (index == activeIndex) MiuixTheme.textStyles.headline2 else MiuixTheme.textStyles.body1,
                    color = if (index == activeIndex) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth().clickable { onSeek(line.timeMillis.coerceAtLeast(0L)) }.testTag("lyric-line-$index"),
                )
            }
        }
    }
}

@Composable
private fun PlayerArtwork(track: LocalTrack, size: androidx.compose.ui.unit.Dp, repository: LocalArtworkRepository?) {
    val density = LocalDensity.current
    val targetEdgePx = with(density) { size.toPx().roundToInt().coerceAtLeast(1) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, repository, track.pathKey, targetEdgePx) {
        value = repository?.let { artwork -> runCatching { artwork.thumbnail(track, targetEdgePx) }.getOrNull() }
    }
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(requireNotNull(bitmap)),
            contentDescription = "${track.album} cover",
            modifier = Modifier.size(size).clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Text("No artwork") }
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

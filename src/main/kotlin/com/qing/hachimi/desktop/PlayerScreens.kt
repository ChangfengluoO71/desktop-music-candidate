package com.qing.hachimi.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
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

internal enum class ImmersivePlayerLayout { COVER_AND_LYRICS, LYRICS_FIRST }

@Composable
internal fun PlayerMiniBar(
    viewModel: PlayerViewModel,
    onOpenNowPlaying: () -> Unit,
    artworkRepository: LocalArtworkRepository? = null,
    onOpenImmersive: () -> Unit = onOpenNowPlaying,
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
        onOpenImmersive = onOpenImmersive,
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
    onOpenImmersive: () -> Unit = onOpenNowPlaying,
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
        Button(modifier = Modifier.testTag("player-open-immersive"), onClick = onOpenImmersive) { Text("沉浸播放") }
        playback.error?.let { Text(it, color = MiuixTheme.colorScheme.primary, modifier = Modifier.testTag("player-error")) }
    }
}

@Composable
internal fun NowPlayingScreen(
    viewModel: PlayerViewModel,
    artworkRepository: LocalArtworkRepository? = null,
    onOpenImmersive: () -> Unit = {},
) {
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
                    onOpenImmersive = onOpenImmersive,
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
                    onOpenImmersive = onOpenImmersive,
                )
            }
        }
    }
}

@Composable
internal fun ImmersivePlayerScreen(
    viewModel: PlayerViewModel,
    artworkRepository: LocalArtworkRepository?,
    layout: ImmersivePlayerLayout,
    reducedMotion: Boolean,
    backdropSource: ImmersiveBackdropSource = ImmersiveBackdropSource.ALBUM_ART,
    backdropMaterial: ImmersiveBackdropMaterial = ImmersiveBackdropMaterial.SOFT_FOCUS,
    backdropStrength: Int = 50,
    backdropImagePath: String = "",
    backdropSolidColor: String = "#111820",
    onExit: () -> Unit,
    onToggleFullscreen: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    var selectedLayout by remember(layout) { mutableStateOf(layout) }
    ImmersivePlayerContent(
        state = state,
        artworkRepository = artworkRepository,
        layout = selectedLayout,
        reducedMotion = reducedMotion,
        backdropSource = backdropSource,
        backdropMaterial = backdropMaterial,
        backdropStrength = backdropStrength,
        backdropImagePath = backdropImagePath,
        backdropSolidColor = backdropSolidColor,
        onExit = onExit,
        onLayoutChange = { selectedLayout = it },
        onToggleFullscreen = onToggleFullscreen,
        onPrevious = viewModel::previous,
        onTogglePlayback = viewModel::togglePlayback,
        onNext = viewModel::next,
        onSeek = viewModel::seek,
        onSetShuffle = viewModel::setShuffle,
        onSetRepeatMode = viewModel::setRepeatMode,
        onSetVolume = viewModel::setVolume,
        onPlayQueueIndex = viewModel::playQueueIndex,
        onMoveQueueItem = viewModel::moveQueueItem,
        onRemoveQueueItem = viewModel::removeFromQueue,
        onClearQueue = viewModel::clearQueue,
        onSelectLyricVariant = viewModel::selectLyricVariant,
        onKeyboardAction = { it.perform(viewModel) },
    )
}

@Composable
internal fun ImmersivePlayerContent(
    state: PlayerUiState,
    artworkRepository: LocalArtworkRepository?,
    layout: ImmersivePlayerLayout,
    reducedMotion: Boolean,
    backdropSource: ImmersiveBackdropSource = ImmersiveBackdropSource.ALBUM_ART,
    backdropMaterial: ImmersiveBackdropMaterial = ImmersiveBackdropMaterial.SOFT_FOCUS,
    backdropStrength: Int = 50,
    backdropImagePath: String = "",
    backdropSolidColor: String = "#111820",
    onExit: () -> Unit,
    onLayoutChange: (ImmersivePlayerLayout) -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSetShuffle: (Boolean) -> Unit,
    onSetRepeatMode: (PlaybackRepeatMode) -> Unit,
    onSetVolume: (Float) -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onSelectLyricVariant: (LyricVariant) -> Unit,
    modifier: Modifier = Modifier,
    onToggleFullscreen: () -> Unit = {},
    onKeyboardAction: ((PlayerShortcutAction) -> Unit)? = null,
) {
    val playback = state.playback
    val track = playback.currentTrack
    var showQueue by remember { mutableStateOf(false) }
    var sliderValue by remember(track?.pathKey) { mutableFloatStateOf(progressOf(playback)) }
    var dragging by remember(track?.pathKey) { mutableStateOf(false) }
    LaunchedEffect(playback.positionMillis, playback.durationMillis) {
        if (!dragging) sliderValue = progressOf(playback)
    }

    BoxWithConstraints(modifier.fillMaxSize().testTag("immersive-player")) {
        val compact = maxWidth < 760.dp || maxHeight < 560.dp
        val compactHeader = maxWidth < 1_000.dp
        val coverSize = if (compact) minOf(132.dp, maxWidth * 0.28f, maxHeight * 0.24f)
        else minOf(320.dp, maxWidth * 0.33f, maxHeight * 0.48f)
        ImmersiveArtworkBackdrop(
            track = track,
            repository = artworkRepository,
            reducedMotion = reducedMotion,
            source = backdropSource,
            material = backdropMaterial,
            materialStrength = backdropStrength,
            customImagePath = backdropImagePath,
            solidColor = backdropSolidColor,
        )
        Column(
            Modifier.fillMaxSize()
                .then(onKeyboardAction?.let { action -> rememberImmersiveKeyboardShortcutModifier(action, onExit) } ?: Modifier)
                .padding(if (compact) 12.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
        ) {
            if (compactHeader) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("沉浸播放", color = Color.White, style = MiuixTheme.textStyles.headline2)
                    Spacer(Modifier.weight(1f))
                    Button(modifier = Modifier.testTag("immersive-exit"), onClick = onExit) { Text("退出", color = Color.White) }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ImmersiveHeaderActions(showQueue, layout, onLayoutChange, onToggleFullscreen) { showQueue = !showQueue }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("沉浸播放", color = Color.White, style = MiuixTheme.textStyles.headline2)
                    Spacer(Modifier.weight(1f))
                    ImmersiveHeaderActions(showQueue, layout, onLayoutChange, onToggleFullscreen) { showQueue = !showQueue }
                    Button(modifier = Modifier.testTag("immersive-exit"), onClick = onExit) { Text("退出", color = Color.White) }
                }
            }

            if (compact) {
                Row(
                    Modifier.fillMaxWidth().height(coverSize + 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ImmersiveArtworkAndMetadata(track, artworkRepository, coverSize, compact = true, modifier = Modifier.weight(1f))
                }
                ImmersiveLyricOrQueuePane(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = state,
                    showQueue = showQueue,
                    layout = layout,
                    reducedMotion = reducedMotion,
                    material = backdropMaterial,
                    materialStrength = backdropStrength,
                    onSeek = onSeek,
                    onSelectLyricVariant = onSelectLyricVariant,
                    onPlayQueueIndex = onPlayQueueIndex,
                    onMoveQueueItem = onMoveQueueItem,
                    onRemoveQueueItem = onRemoveQueueItem,
                    onClearQueue = onClearQueue,
                )
            } else {
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (layout == ImmersivePlayerLayout.COVER_AND_LYRICS) {
                        ImmersiveArtworkAndMetadata(track, artworkRepository, coverSize, compact = false, modifier = Modifier.weight(0.85f))
                        ImmersiveLyricOrQueuePane(
                            modifier = Modifier.weight(1.15f).fillMaxHeight(), state = state, showQueue = showQueue,
                            layout = layout, reducedMotion = reducedMotion, onSeek = onSeek,
                            material = backdropMaterial, materialStrength = backdropStrength,
                            onSelectLyricVariant = onSelectLyricVariant, onPlayQueueIndex = onPlayQueueIndex,
                            onMoveQueueItem = onMoveQueueItem, onRemoveQueueItem = onRemoveQueueItem, onClearQueue = onClearQueue,
                        )
                    } else {
                        ImmersiveLyricOrQueuePane(
                            modifier = Modifier.weight(1.2f).fillMaxHeight(), state = state, showQueue = showQueue,
                            layout = layout, reducedMotion = reducedMotion, onSeek = onSeek,
                            material = backdropMaterial, materialStrength = backdropStrength,
                            onSelectLyricVariant = onSelectLyricVariant, onPlayQueueIndex = onPlayQueueIndex,
                            onMoveQueueItem = onMoveQueueItem, onRemoveQueueItem = onRemoveQueueItem, onClearQueue = onClearQueue,
                        )
                        ImmersiveArtworkAndMetadata(track, artworkRepository, coverSize, compact = false, modifier = Modifier.weight(0.8f))
                    }
                }
            }

            ImmersivePlaybackDeck(
                playback = playback,
                material = backdropMaterial,
                materialStrength = backdropStrength,
                sliderValue = sliderValue,
                onSliderValue = { sliderValue = it },
                onDragging = { dragging = it },
                onPrevious = onPrevious,
                onTogglePlayback = onTogglePlayback,
                onNext = onNext,
                onSeek = onSeek,
                onSetShuffle = onSetShuffle,
                onSetRepeatMode = onSetRepeatMode,
                onSetVolume = onSetVolume,
            )
        }
    }
}

@Composable
private fun ImmersiveHeaderActions(
    showQueue: Boolean,
    layout: ImmersivePlayerLayout,
    onLayoutChange: (ImmersivePlayerLayout) -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleQueue: () -> Unit,
) {
    Button(
        modifier = Modifier.testTag("immersive-layout-cover-lyrics"),
        colors = if (layout == ImmersivePlayerLayout.COVER_AND_LYRICS) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
        onClick = { onLayoutChange(ImmersivePlayerLayout.COVER_AND_LYRICS) },
    ) { Text("封面 + 歌词", color = Color.White) }
    Button(
        modifier = Modifier.testTag("immersive-layout-lyrics-first"),
        colors = if (layout == ImmersivePlayerLayout.LYRICS_FIRST) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
        onClick = { onLayoutChange(ImmersivePlayerLayout.LYRICS_FIRST) },
    ) { Text("歌词优先", color = Color.White) }
    Button(modifier = Modifier.testTag("immersive-queue-toggle"), onClick = onToggleQueue) {
        Text(if (showQueue) "返回歌词" else "播放队列", color = Color.White)
    }
    Button(modifier = Modifier.testTag("immersive-fullscreen"), onClick = onToggleFullscreen) {
        Text("全屏", color = Color.White)
    }
}

private data class ImmersiveBackdropFrame(
    val image: ImageBitmap?,
    val palette: List<Color>,
    val usedAlbumFallback: Boolean,
)

@Composable
private fun ImmersiveArtworkBackdrop(
    track: LocalTrack?,
    repository: LocalArtworkRepository?,
    reducedMotion: Boolean,
    source: ImmersiveBackdropSource,
    material: ImmersiveBackdropMaterial,
    materialStrength: Int,
    customImagePath: String,
    solidColor: String,
) {
    val frame by produceState<ImmersiveBackdropFrame?>(null, repository, track?.pathKey, source, customImagePath) {
        val selected = if (source == ImmersiveBackdropSource.CUSTOM_IMAGE && customImagePath.isNotBlank()) {
            loadCustomBackdrop(customImagePath, 1_024)
        } else {
            null
        }
        val album = if (selected == null) {
            track?.let { current -> repository?.let { runCatching { it.backdrop(current, 1_024) }.getOrNull() } }
        } else {
            null
        }
        val image = selected ?: album
        value = ImmersiveBackdropFrame(
            image = image,
            palette = image?.let { extractBackdropPalette(it) } ?: DEFAULT_IMMERSIVE_PALETTE,
            usedAlbumFallback = source == ImmersiveBackdropSource.CUSTOM_IMAGE && selected == null,
        )
    }
    val fallbackColor = parseBackdropColor(solidColor) ?: Color(0xFF111820)
    val colors = frame?.palette ?: DEFAULT_IMMERSIVE_PALETTE
    Crossfade(
        targetState = frame,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 480),
        label = "immersive-artwork-backdrop",
    ) { current ->
        when {
            source == ImmersiveBackdropSource.SOLID_COLOR -> {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(fallbackColor, Color.Black))))
            }
            material == ImmersiveBackdropMaterial.ALBUM_GRADIENT -> {
                val gradient = if (source == ImmersiveBackdropSource.SOLID_COLOR) {
                    listOf(fallbackColor, Color.Black)
                } else {
                    colors
                }
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(gradient))) {
                    Box(
                        Modifier.fillMaxSize().background(
                            Color.Black.copy(alpha = 0.4f * materialStrength.coerceIn(0, 100) / 100f),
                        ),
                    )
                }
            }
            current?.image != null -> {
                Box(Modifier.fillMaxSize().background(Color(0xFF10151B))) {
                    Image(
                        painter = BitmapPainter(current.image),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().blur(
                            ((if (material == ImmersiveBackdropMaterial.SOFT_FOCUS) 42f else 22f) *
                                materialStrength.coerceIn(0, 100) / 100f).dp,
                        ),
                    )
                    val strength = materialStrength.coerceIn(0, 100) / 100f
                    val scrimAlpha = if (material == ImmersiveBackdropMaterial.SOFT_FOCUS) {
                        0.04f + 0.52f * strength
                    } else {
                        0.02f + 0.38f * strength
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = (scrimAlpha + 0.08f).coerceAtMost(0.92f)),
                                    Color.Black.copy(alpha = scrimAlpha),
                                    Color.Black.copy(alpha = (scrimAlpha + 0.12f).coerceAtMost(0.92f)),
                                ),
                            ),
                        ),
                    )
                    if (current.usedAlbumFallback) {
                        Text(
                            "自定义壁纸不可用，已回退到专辑封面",
                            color = Color.White.copy(alpha = 0.82f),
                            modifier = Modifier.align(Alignment.TopStart).padding(18.dp),
                        )
                    }
                }
            }
            else -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(colors)))
        }
    }
}

private val DEFAULT_IMMERSIVE_PALETTE = listOf(Color(0xFF183444), Color(0xFF352B59), Color(0xFF171A22))

@Composable
private fun ImmersiveArtworkAndMetadata(
    track: LocalTrack?,
    repository: LocalArtworkRepository?,
    coverSize: androidx.compose.ui.unit.Dp,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val edgePx = with(density) { coverSize.toPx().roundToInt().coerceAtLeast(1) }
    val artwork by produceState<ImageBitmap?>(null, repository, track?.pathKey, edgePx) {
        value = track?.let { current -> repository?.let { runCatching { it.thumbnail(current, edgePx) }.getOrNull() } }
    }
    val renderedCoverSize = artwork?.let { image ->
        with(density) { minOf(coverSize.toPx(), maxOf(image.width, image.height).toFloat()).toDp() }
    } ?: coverSize
    if (compact) {
        if (artwork != null) {
            Image(BitmapPainter(requireNotNull(artwork)), contentDescription = track?.album, contentScale = ContentScale.Fit,
                modifier = Modifier.size(renderedCoverSize).clip(RoundedCornerShape(18.dp)))
        } else {
            Box(Modifier.size(coverSize).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.12f))
                .testTag("immersive-artwork-fallback"), contentAlignment = Alignment.Center) {
                Text("DMC", color = Color.White, style = MiuixTheme.textStyles.headline2)
            }
        }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(track?.title ?: "尚未选择歌曲", color = Color.White, style = MiuixTheme.textStyles.headline2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track?.artists.orEmpty().joinToString().ifBlank { track?.album.orEmpty() }, color = Color.White.copy(alpha = 0.78f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Column(modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (artwork != null) {
                Image(BitmapPainter(requireNotNull(artwork)), contentDescription = track?.album, contentScale = ContentScale.Fit,
                    modifier = Modifier.size(renderedCoverSize).clip(RoundedCornerShape(22.dp)))
            } else {
                Box(Modifier.size(coverSize).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.12f))
                    .testTag("immersive-artwork-fallback"), contentAlignment = Alignment.Center) {
                    Text("DMC", color = Color.White, style = MiuixTheme.textStyles.headline1)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(track?.title ?: "尚未选择歌曲", color = Color.White, style = MiuixTheme.textStyles.headline1,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track?.artists.orEmpty().joinToString().ifBlank { track?.album.orEmpty() }, color = Color.White.copy(alpha = 0.78f),
                style = MiuixTheme.textStyles.body1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            track?.let { Text(it.audioQuality.summary(), color = Color.White.copy(alpha = 0.7f), style = MiuixTheme.textStyles.body2) }
        }
    }
}

@Composable
private fun ImmersiveLyricOrQueuePane(
    modifier: Modifier,
    state: PlayerUiState,
    showQueue: Boolean,
    layout: ImmersivePlayerLayout,
    reducedMotion: Boolean,
    onSeek: (Long) -> Unit,
    onSelectLyricVariant: (LyricVariant) -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onClearQueue: () -> Unit,
    material: ImmersiveBackdropMaterial = ImmersiveBackdropMaterial.SOFT_FOCUS,
    materialStrength: Int = 50,
) {
    if (showQueue) {
        PlayerQueueContent(
            playback = state.playback,
            onPlayIndex = onPlayQueueIndex,
            onMoveQueueItem = onMoveQueueItem,
            onRemove = onRemoveQueueItem,
            onClear = onClearQueue,
        )
        return
    }

    val trackKey = state.playback.currentTrack?.pathKey
    val listState = rememberLazyListState()
    var followLyrics by remember(trackKey, state.lyricVariant, layout) { mutableStateOf(true) }
    var automaticScroll by remember { mutableStateOf(false) }
    val lines = state.activeLyrics
    val activeIndex = state.activeLyricIndex
    val alignedLines = remember(state.lyrics) { ImmersiveLyrics.align(state.lyrics) }

    LaunchedEffect(listState.isScrollInProgress, automaticScroll) {
        if (listState.isScrollInProgress && !automaticScroll) followLyrics = false
    }
    LaunchedEffect(activeIndex, followLyrics, trackKey, state.lyricVariant, layout) {
        if (followLyrics && activeIndex >= 0) {
            automaticScroll = true
            try {
                val targetOffset = -(listState.layoutInfo.viewportSize.height / 3)
                listState.animateScrollToItem(activeIndex, scrollOffset = targetOffset)
            } finally {
                automaticScroll = false
            }
        }
    }

    Column(modifier.immersiveMaterialSurface(material, materialStrength).testTag("immersive-lyrics"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LyricVariant.entries.filter { state.lyrics.lines(it).isNotEmpty() }.forEach { variant ->
                Button(
                    modifier = Modifier.testTag("immersive-variant-${variant.name.lowercase(Locale.ROOT)}"),
                    colors = if (variant == state.lyricVariant) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                    onClick = { onSelectLyricVariant(variant) },
                ) { Text(variant.label(), color = Color.White) }
            }
            Spacer(Modifier.weight(1f))
            if (!followLyrics) {
                Button(modifier = Modifier.testTag("immersive-follow-resume"), onClick = { followLyrics = true }) {
                    Text("恢复跟随", color = Color.White)
                }
            }
        }
        when {
            state.isLoadingLyrics -> Text("正在读取本地歌词…", color = Color.White.copy(alpha = 0.8f))
            state.lyricsUnavailable -> Text("未找到可用的本地歌词。", color = Color.White.copy(alpha = 0.8f))
            else -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("immersive-lyrics-list"),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (state.lyricVariant == LyricVariant.ORIGINAL) {
                    itemsIndexed(alignedLines, key = { index, line -> "$index-${line.original.timeMillis}" }) { index, line ->
                        val active = index == activeIndex
                        Column(
                            Modifier.fillMaxWidth().clickable { onSeek(line.original.timeMillis.coerceAtLeast(0L)) }
                                .testTag("immersive-lyric-$index"),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(line.original.text, color = if (active) Color.White else Color.White.copy(alpha = 0.62f),
                                style = if (active) MiuixTheme.textStyles.headline1 else MiuixTheme.textStyles.headline2)
                            line.translation?.let { Text(it.text, color = Color.White.copy(alpha = 0.82f), style = MiuixTheme.textStyles.body1) }
                            line.romanization?.let { Text(it.text, color = Color.White.copy(alpha = 0.65f), style = MiuixTheme.textStyles.body2) }
                        }
                    }
                } else {
                    itemsIndexed(lines, key = { index, line -> "$index-${line.timeMillis}" }) { index, line ->
                        Text(
                            line.text,
                            color = if (index == activeIndex) Color.White else Color.White.copy(alpha = 0.64f),
                            style = if (index == activeIndex) MiuixTheme.textStyles.headline1 else MiuixTheme.textStyles.headline2,
                            modifier = Modifier.fillMaxWidth().clickable { onSeek(line.timeMillis.coerceAtLeast(0L)) }
                                .testTag("immersive-lyric-$index"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImmersivePlaybackDeck(
    playback: PlaybackState,
    material: ImmersiveBackdropMaterial,
    materialStrength: Int,
    sliderValue: Float,
    onSliderValue: (Float) -> Unit,
    onDragging: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSetShuffle: (Boolean) -> Unit,
    onSetRepeatMode: (PlaybackRepeatMode) -> Unit,
    onSetVolume: (Float) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 520.dp
        Column(
            Modifier.fillMaxWidth().immersiveMaterialSurface(material, materialStrength, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            playback.error?.let { error ->
                Text(
                    error,
                    color = Color(0xFFFFD7D7),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(Color(0x66A32020)).padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("immersive-playback-error"),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (playback.currentTrack != null && playback.durationMillis <= 0L && playback.error == null) {
                Text(
                    "播放时长尚未读取，进度定位暂不可用",
                    color = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.testTag("immersive-seek-unavailable"),
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(modifier = Modifier.testTag("immersive-previous"), enabled = playback.currentTrack != null, onClick = onPrevious) {
                    Text("上一首", color = Color.White)
                }
                Button(
                    modifier = Modifier.testTag("immersive-play-pause"),
                    enabled = playback.currentTrack != null || playback.queue.isNotEmpty(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    onClick = onTogglePlayback,
                ) { Text(if (playback.isPlaying) "暂停" else "播放", color = Color.White) }
                Button(modifier = Modifier.testTag("immersive-next"), enabled = playback.currentTrack != null, onClick = onNext) {
                    Text("下一首", color = Color.White)
                }
                Button(onClick = { onSetShuffle(!playback.shuffle) }) { Text(if (playback.shuffle) "随机：开" else "随机：关", color = Color.White) }
                Button(onClick = {
                    onSetRepeatMode(when (playback.repeatMode) {
                        PlaybackRepeatMode.OFF -> PlaybackRepeatMode.ALL
                        PlaybackRepeatMode.ALL -> PlaybackRepeatMode.ONE
                        PlaybackRepeatMode.ONE -> PlaybackRepeatMode.OFF
                    })
                }) { Text("循环：${when (playback.repeatMode) { PlaybackRepeatMode.OFF -> "关"; PlaybackRepeatMode.ALL -> "列表"; PlaybackRepeatMode.ONE -> "单曲" }}", color = Color.White) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatPlayerTime(playback.positionMillis), color = Color.White, style = MiuixTheme.textStyles.body2)
                Slider(
                    value = sliderValue,
                    onValueChange = { onSliderValue(it); onDragging(true) },
                    modifier = Modifier.weight(1f).height(28.dp).testTag("immersive-seek"),
                    enabled = playback.durationMillis > 0,
                    valueRange = 0f..1f,
                    onValueChangeFinished = {
                        onDragging(false)
                        onSeek((sliderValue * playback.durationMillis).toLong())
                    },
                )
                Text(formatPlayerTime(playback.durationMillis), color = Color.White, style = MiuixTheme.textStyles.body2)
                if (!narrow) {
                    Slider(value = playback.volume, onValueChange = onSetVolume, modifier = Modifier.width(110.dp).height(28.dp).testTag("immersive-volume"), valueRange = 0f..1f)
                }
            }
            if (narrow) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("音量", color = Color.White, style = MiuixTheme.textStyles.body2)
                    Slider(value = playback.volume, onValueChange = onSetVolume, modifier = Modifier.weight(1f).height(28.dp).testTag("immersive-volume"), valueRange = 0f..1f)
                }
            }
        }
    }
}

private fun Modifier.immersiveMaterialSurface(
    material: ImmersiveBackdropMaterial,
    strength: Int = 50,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
): Modifier {
    val intensity = strength.coerceIn(0, 100) / 100f
    val acrylic = material == ImmersiveBackdropMaterial.ACRYLIC
    val surfaceAlpha = if (acrylic) 0.03f + 0.16f * intensity else 0.08f + 0.4f * intensity
    val borderAlpha = if (acrylic) 0.08f + 0.32f * intensity else 0.04f + 0.08f * intensity
    return clip(shape)
        .background(if (acrylic) Color.White.copy(alpha = surfaceAlpha) else Color.Black.copy(alpha = surfaceAlpha), shape)
        .border(1.dp, Color.White.copy(alpha = borderAlpha), shape)
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
    onOpenImmersive: () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (showQueue) "播放队列 · ${playback.queue.size} 首" else "歌词", style = MiuixTheme.textStyles.headline2)
            Spacer(Modifier.weight(1f))
            Button(modifier = Modifier.testTag("now-playing-open-immersive"), onClick = onOpenImmersive) {
                Text("沉浸播放")
            }
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

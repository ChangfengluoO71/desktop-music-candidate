package com.qing.hachimi.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.nio.file.Path
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun LocalMusicScreen(
    viewModel: LocalMusicViewModel,
    playerViewModel: PlayerViewModel? = null,
    artworkRepository: LocalArtworkRepository? = null,
) {
    val state by viewModel.state.collectAsState()
    LocalMusicContent(
        state = state,
        artworkRepository = artworkRepository,
        onAddDirectories = viewModel::addRoots,
        onRemoveRoot = viewModel::removeRoot,
        onToggleRootAutoWatch = { root -> viewModel.setRootAutoWatch(root.path, !root.autoWatch) },
        onScan = viewModel::scan,
        onCancelScan = viewModel::cancelScan,
        onAnalyzeDuplicates = viewModel::analyzeDuplicates,
        onCancelAnalysis = viewModel::cancelDuplicateAnalysis,
        onPreviewUpgrades = viewModel::previewUpgrades,
        onFormatFilter = viewModel::setFormatFilter,
        onSearchFilter = viewModel::setSearchFilter,
        onPlayTrack = playerViewModel?.let { player -> { track -> player.play(track); Unit } },
        onQueueTrack = playerViewModel?.let { player -> { track -> player.enqueue(listOf(track)) } },
        onPlayNextTrack = playerViewModel?.let { player -> { track -> player.playNext(track) } },
        onPlayTracks = playerViewModel?.let { player -> { tracks -> player.enqueue(tracks) } },
        onPlayNextTracks = playerViewModel?.let { player -> { tracks -> player.playNextTracks(tracks) } },
        onStartTracks = playerViewModel?.let { player -> { tracks -> player.playTracks(tracks); Unit } },
        onArtistFilter = viewModel::setArtistFilter,
        onAlbumFilter = viewModel::setAlbumFilter,
        onAlbumSelection = viewModel::setAlbumTracks,
        onBrowseMode = viewModel::setBrowseMode,
        onSortMode = viewModel::setSortMode,
        onFolderFilter = viewModel::setFolderFilter,
    )
}

@Composable
internal fun LocalMusicContent(
    state: LocalMusicUiState,
    onAddDirectories: (List<Path>) -> Unit,
    onRemoveRoot: (String) -> Unit,
    onToggleRootAutoWatch: (LocalMusicRoot) -> Unit = {},
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onAnalyzeDuplicates: () -> Unit,
    onCancelAnalysis: () -> Unit,
    onPreviewUpgrades: () -> Unit,
    onFormatFilter: (String) -> Unit,
    onArtistFilter: (String) -> Unit,
    onAlbumFilter: (String) -> Unit,
    onAlbumSelection: (List<LocalTrack>) -> Unit = {},
    onSearchFilter: (String) -> Unit = {},
    onPlayTrack: ((LocalTrack) -> Unit)? = null,
    onQueueTrack: ((LocalTrack) -> Unit)? = null,
    onPlayNextTrack: ((LocalTrack) -> Unit)? = null,
    onPlayTracks: ((List<LocalTrack>) -> Unit)? = null,
    onPlayNextTracks: ((List<LocalTrack>) -> Unit)? = null,
    onStartTracks: ((List<LocalTrack>) -> Unit)? = null,
    artworkRepository: LocalArtworkRepository? = null,
    onBrowseMode: (LocalMusicBrowseMode) -> Unit = {},
    onSortMode: (LocalMusicSortMode) -> Unit = {},
    onFolderFilter: (String?) -> Unit = {},
) {
    val albums = remember(state.tracks) {
        state.tracks.filter { it.album.isNotBlank() }
            .groupBy { (it.albumArtist.ifBlank { it.artists.firstOrNull().orEmpty() } + "\u0000" + it.album).lowercase(Locale.ROOT) }
            .values.map { tracks -> LocalAlbumBucket(tracks.first().pathKey, tracks.first().album, tracks.first().albumArtist.ifBlank { tracks.first().artists.joinToString() }, tracks) }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
    }
    val artists = remember(state.tracks) {
        state.tracks.flatMap { track ->
            track.artists.ifEmpty { listOf(track.albumArtist.ifBlank { "未知歌手" }) }.distinct().map { it to track }
        }.groupBy({ it.first }, { it.second })
            .map { (name, tracks) -> LocalArtistBucket(name, tracks.distinctBy { it.pathKey }) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }
    val folders = remember(state.roots, state.tracks) { buildFolderEntries(state.roots, state.tracks) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(modifier = Modifier.testTag("local-music-add-root"), onClick = { onAddDirectories(chooseMusicDirectories()) }) { Text("添加目录") }
            Button(
                modifier = Modifier.testTag("local-music-scan"),
                enabled = state.roots.isNotEmpty() && !state.isScanning && !state.isAnalyzingDuplicates,
                colors = ButtonDefaults.buttonColorsPrimary(), onClick = onScan,
            ) { Text(if (state.isScanning) "扫描中…" else "扫描") }
            if (state.isScanning) Button(modifier = Modifier.testTag("local-music-cancel-scan"), onClick = onCancelScan) { Text("取消") }
            Button(modifier = Modifier.testTag("local-music-hash-duplicates"), enabled = state.tracks.isNotEmpty() && !state.isScanning && !state.isAnalyzingDuplicates, onClick = onAnalyzeDuplicates) {
                Text(if (state.isAnalyzingDuplicates) "校验中…" else "查找重复")
            }
            if (state.isAnalyzingDuplicates) Button(modifier = Modifier.testTag("local-music-cancel-analysis"), onClick = onCancelAnalysis) { Text("取消校验") }
            Button(modifier = Modifier.testTag("local-music-upgrade-preview"), enabled = state.tracks.isNotEmpty(), onClick = onPreviewUpgrades) { Text("音质预览") }
            Spacer(Modifier.weight(1f))
            Text("只读索引 · ${state.tracks.size} 首", style = MiuixTheme.textStyles.body2)
        }
        state.recoveryWarning?.let { Text(it, color = MiuixTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text(
            state.progress?.let { "扫描 ${it.completedFiles}/${it.totalFiles} · ${it.currentPath?.substringAfterLast('\\').orEmpty()}" }
                ?: state.analysisProgress?.let { "校验 ${it.completedFiles}/${it.totalFiles} · ${it.currentPath?.substringAfterLast('\\').orEmpty()}" }
                ?: state.message,
            modifier = Modifier.testTag("local-music-status"), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 44.dp)) {
            items(state.roots, key = { it.path }) { root ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(root.path, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(190.dp))
                    Button(modifier = Modifier.testTag("local-music-watch-${root.path.hashCode()}"), onClick = { onToggleRootAutoWatch(root) }) {
                        Text(if (root.autoWatch) "监听中" else "监听")
                    }
                    Button(modifier = Modifier.testTag("local-music-remove-root"), enabled = !state.isScanning && !state.isAnalyzingDuplicates, onClick = { onRemoveRoot(root.path) }) { Text("移除索引") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(value = state.searchFilter, onValueChange = onSearchFilter, label = "搜索本地音乐", singleLine = true, modifier = Modifier.weight(1f).testTag("local-music-search"))
            Text("${state.visibleTracks.size} / ${state.tracks.size} 首")
            LocalBrowseTab("专辑", LocalMusicBrowseMode.ALBUMS, state.browseMode, onBrowseMode, "local-browse-albums")
            LocalBrowseTab("歌手", LocalMusicBrowseMode.ARTISTS, state.browseMode, onBrowseMode, "local-browse-artists")
            LocalBrowseTab("歌曲", LocalMusicBrowseMode.SONGS, state.browseMode, onBrowseMode, "local-browse-songs")
            LocalBrowseTab("文件夹", LocalMusicBrowseMode.FOLDERS, state.browseMode, onBrowseMode, "local-browse-folders")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(value = state.formatFilter, onValueChange = onFormatFilter, label = "格式", singleLine = true, modifier = Modifier.weight(0.8f).testTag("local-music-format-filter"))
            TextField(value = state.artistFilter, onValueChange = onArtistFilter, label = "歌手", singleLine = true, modifier = Modifier.weight(1f).testTag("local-music-artist-filter"))
            TextField(value = state.albumFilter, onValueChange = onAlbumFilter, label = "专辑", singleLine = true, modifier = Modifier.weight(1f).testTag("local-music-album-filter"))
            Button(modifier = Modifier.testTag("local-music-play-filtered"), enabled = state.visibleTracks.isNotEmpty() && onStartTracks != null, colors = ButtonDefaults.buttonColorsPrimary(), onClick = { onStartTracks?.invoke(state.visibleTracks) }) { Text("播放") }
            Button(modifier = Modifier.testTag("local-music-queue-filtered"), enabled = state.visibleTracks.isNotEmpty() && onPlayTracks != null, onClick = { onPlayTracks?.invoke(state.visibleTracks) }) { Text("加入队列") }
        }
        HorizontalDivider()

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val contentWidth = maxWidth
            when (state.browseMode) {
                LocalMusicBrowseMode.ALBUMS -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 176.dp), modifier = Modifier.fillMaxSize().testTag("local-album-grid"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    gridItems(albums, key = { it.key }) { album ->
                        LocalAlbumCard(
                            album, artworkRepository,
                            onClick = {
                            onAlbumFilter(album.title)
                            onArtistFilter("")
                            onAlbumSelection(album.tracks)
                            onBrowseMode(LocalMusicBrowseMode.SONGS)
                            },
                            onPlay = { onStartTracks?.invoke(album.tracks) },
                            onPlayNext = { onPlayNextTracks?.invoke(album.tracks) },
                            onQueue = { onPlayTracks?.invoke(album.tracks) },
                            canPlay = onStartTracks != null,
                            canPlayNext = onPlayNextTracks != null,
                            canQueue = onPlayTracks != null,
                        )
                    }
                    if (albums.isEmpty()) item { Text("扫描后会在此显示本地专辑封面墙。") }
                }
                LocalMusicBrowseMode.ARTISTS -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 176.dp), modifier = Modifier.fillMaxSize().testTag("local-artist-grid"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    gridItems(artists, key = { it.name.lowercase(Locale.ROOT) }) { artist ->
                        LocalArtistCard(
                            artist, artworkRepository,
                            onClick = {
                            onArtistFilter(artist.name)
                            onAlbumFilter("")
                            onBrowseMode(LocalMusicBrowseMode.SONGS)
                            },
                            onPlay = { onStartTracks?.invoke(artist.tracks) },
                            onPlayNext = { onPlayNextTracks?.invoke(artist.tracks) },
                            onQueue = { onPlayTracks?.invoke(artist.tracks) },
                            canPlay = onStartTracks != null,
                            canPlayNext = onPlayNextTracks != null,
                            canQueue = onPlayTracks != null,
                        )
                    }
                    if (artists.isEmpty()) item { Text("扫描后会在此显示本地歌手。") }
                }
                LocalMusicBrowseMode.SONGS -> Column(Modifier.fillMaxSize()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("排序")
                        LocalMusicSortMode.entries.forEach { mode ->
                            Button(
                                modifier = Modifier.testTag("local-sort-${mode.name.lowercase(Locale.ROOT)}"),
                                colors = if (mode == state.sortMode) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                                onClick = { onSortMode(mode) },
                            ) { Text(mode.label()) }
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize().testTag("local-music-table"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(state.visibleTracks, key = { it.id }) { track -> LocalTrackRow(track, onPlayTrack, onPlayNextTrack, onQueueTrack) }
                        if (state.visibleTracks.isEmpty()) item { Text("暂无符合条件的曲目。", modifier = Modifier.padding(12.dp)) }
                    }
                }
                LocalMusicBrowseMode.FOLDERS -> Row(Modifier.fillMaxSize().testTag("local-folder-list"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LazyColumn(Modifier.width(contentWidth * if (contentWidth < 760.dp) 0.46f else 0.34f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item {
                            Button(onClick = { onFolderFilter(null) }, colors = if (state.folderFilter == null) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                                Text("全部文件夹", maxLines = 1)
                            }
                        }
                        items(folders, key = { it.key }) { folder ->
                            Button(
                                modifier = Modifier.fillMaxWidth().padding(start = (folder.depth * 12).dp).testTag("local-folder-${folder.key.hashCode()}"),
                                colors = if (normalizedLibraryPath(state.folderFilter.orEmpty()) == folder.key) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                                onClick = { onFolderFilter(folder.path) },
                            ) { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(state.folderFilter ?: "所有已索引文件夹", style = MiuixTheme.textStyles.headline2)
                        Text("${state.visibleTracks.size} 首")
                        LazyColumn(Modifier.fillMaxSize().testTag("local-folder-tracks"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(state.visibleTracks, key = { it.id }) { track -> LocalTrackRow(track, onPlayTrack, onPlayNextTrack, onQueueTrack) }
                        }
                    }
                }
            }
        }
        state.duplicateAnalysis?.let { analysis ->
            Text("完全重复 ${analysis.exactDuplicates.size} 组 · 疑似重复 ${analysis.similarCandidates.size} 组", modifier = Modifier.testTag("local-duplicate-summary"))
        }
        if (state.upgradeCandidates.isNotEmpty()) Text("音质预览 ${state.upgradeCandidates.size} 项；候选参数需人工核对。")
        Text("只读取所选目录中的标签和技术参数；索引与扫描不会修改音频文件。", style = MiuixTheme.textStyles.body2)
    }
}

private data class LocalAlbumBucket(val key: String, val title: String, val artist: String, val tracks: List<LocalTrack>)
private data class LocalArtistBucket(val name: String, val tracks: List<LocalTrack>)
private data class LocalFolderEntry(val key: String, val path: String, val name: String, val depth: Int)

@Composable
private fun LocalBrowseTab(label: String, mode: LocalMusicBrowseMode, selected: LocalMusicBrowseMode, onSelect: (LocalMusicBrowseMode) -> Unit, tag: String) {
    Button(modifier = Modifier.testTag(tag), colors = if (mode == selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(), onClick = { onSelect(mode) }) { Text(label) }
}

@Composable
private fun LocalAlbumCard(
    album: LocalAlbumBucket,
    artworkRepository: LocalArtworkRepository?,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onQueue: () -> Unit,
    canPlay: Boolean,
    canPlayNext: Boolean,
    canQueue: Boolean,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp).testTag("local-album-${album.key.hashCode()}"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        LocalArtworkActionCover(
            track = album.tracks.first(),
            artworkRepository = artworkRepository,
            subject = "专辑：${album.title}",
            key = album.key,
            prefix = "album",
            canPlay = canPlay,
            canPlayNext = canPlayNext,
            canQueue = canQueue,
            onPlay = onPlay,
            onPlayNext = onPlayNext,
            onQueue = onQueue,
        )
        Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body2)
        Text("${album.tracks.size} 首", style = MiuixTheme.textStyles.body2)
    }
}

@Composable
private fun LocalArtistCard(
    artist: LocalArtistBucket,
    artworkRepository: LocalArtworkRepository?,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onQueue: () -> Unit,
    canPlay: Boolean,
    canPlayNext: Boolean,
    canQueue: Boolean,
) {
    val albumCount = artist.tracks.map { it.album.trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank).distinct().size
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp).testTag("local-artist-${artist.name.hashCode()}"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        LocalArtworkActionCover(
            track = artist.tracks.first(),
            artworkRepository = artworkRepository,
            subject = "歌手：${artist.name}",
            key = artist.name,
            prefix = "artist",
            canPlay = canPlay,
            canPlayNext = canPlayNext,
            canQueue = canQueue,
            onPlay = onPlay,
            onPlayNext = onPlayNext,
            onQueue = onQueue,
        )
        Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$albumCount 张专辑 · ${artist.tracks.size} 首", style = MiuixTheme.textStyles.body2)
    }
}

@Composable
private fun LocalArtworkActionCover(
    track: LocalTrack,
    artworkRepository: LocalArtworkRepository?,
    subject: String,
    key: String,
    prefix: String,
    canPlay: Boolean,
    canPlayNext: Boolean,
    canQueue: Boolean,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onQueue: () -> Unit,
) {
    var menuExpanded by remember(prefix, key) { mutableStateOf(false) }
    val id = remember(prefix, key) { key.hashCode() }
    Box(Modifier.fillMaxWidth().height(144.dp), contentAlignment = Alignment.Center) {
        LocalArtworkThumbnail(track, artworkRepository, 144.dp)
        CoverIconButton(
            description = "播放$subject",
            tag = "$prefix-play-$id",
            background = MiuixTheme.colorScheme.primary,
            size = 48.dp,
            onClick = onPlay,
            enabled = canPlay,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        ) { DrawPlayGlyph() }
        CoverIconButton(
            description = "$subject 更多操作",
            tag = "$prefix-more-$id",
            background = Color.Black.copy(alpha = 0.62f),
            size = 40.dp,
            onClick = { menuExpanded = true },
            enabled = canPlayNext || canQueue,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        ) { DrawMoreGlyph() }
        if (menuExpanded) {
            Popup(
                alignment = Alignment.TopEnd,
                onDismissRequest = { menuExpanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 152.dp, max = 208.dp)
                        .shadow(14.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(MiuixTheme.colorScheme.background)
                        .border(1.dp, MiuixTheme.colorScheme.onBackground.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    CoverMenuItem("下一首播放", "$prefix-next-$id", canPlayNext) {
                        menuExpanded = false
                        onPlayNext()
                    }
                    CoverMenuItem("加入播放队列", "$prefix-queue-$id", canQueue) {
                        menuExpanded = false
                        onQueue()
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverIconButton(
    description: String,
    tag: String,
    background: Color,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        backgroundColor = background,
        cornerRadius = size / 2,
        minWidth = size,
        minHeight = size,
        modifier = modifier
            .size(size)
            .semantics { contentDescription = description; role = Role.Button }
            .testTag(tag),
        content = icon,
    )
}

@Composable
private fun CoverMenuItem(label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (hovered && enabled) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = label; role = Role.Button }
            .testTag(tag)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (enabled) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.42f), maxLines = 1)
    }
}

@Composable
private fun DrawPlayGlyph() {
    Canvas(Modifier.size(20.dp)) {
        val path = ComposePath().apply {
            moveTo(size.width * 0.30f, size.height * 0.16f)
            lineTo(size.width * 0.82f, size.height * 0.50f)
            lineTo(size.width * 0.30f, size.height * 0.84f)
            close()
        }
        drawPath(path, Color.White)
    }
}

@Composable
private fun DrawMoreGlyph() {
    Canvas(Modifier.size(18.dp)) {
        val radius = size.minDimension * 0.09f
        val centerY = size.height / 2f
        listOf(0.22f, 0.5f, 0.78f).forEach { fraction ->
            drawCircle(Color.White, radius, Offset(size.width * fraction, centerY))
        }
    }
}

private fun buildFolderEntries(roots: List<LocalMusicRoot>, tracks: List<LocalTrack>): List<LocalFolderEntry> {
    val entries = linkedMapOf<String, LocalFolderEntry>()
    roots.forEach { root ->
        val rootPath = root.path.replace('\\', '/').trimEnd('/')
        val rootKey = normalizedLibraryPath(Path.of(rootPath))
        val rootParts = rootPath.split('/').filter(String::isNotBlank)
        entries[rootKey] = LocalFolderEntry(rootKey, rootPath, rootParts.lastOrNull() ?: rootPath, 0)
        tracks.forEach trackLoop@{ track ->
            val filePath = track.filePath.replace('\\', '/')
            val parts = filePath.split('/').filter(String::isNotBlank)
            if (parts.size <= rootParts.size || !parts.take(rootParts.size).joinToString("/").equals(rootParts.joinToString("/"), ignoreCase = true)) return@trackLoop
            var current = rootPath
            parts.drop(rootParts.size).dropLast(1).forEachIndexed { index, part ->
                current = "$current/$part"
                val key = normalizedLibraryPath(current)
                entries.putIfAbsent(key, LocalFolderEntry(key, current, part, index + 1))
            }
        }
    }
    return entries.values.sortedWith(compareBy<LocalFolderEntry> { it.path.lowercase(Locale.ROOT) }.thenBy { it.depth })
}

private fun LocalMusicSortMode.label(): String = when (this) {
    LocalMusicSortMode.TITLE -> "标题"
    LocalMusicSortMode.ARTIST -> "歌手"
    LocalMusicSortMode.ALBUM -> "专辑"
    LocalMusicSortMode.FORMAT -> "格式"
    LocalMusicSortMode.DURATION -> "时长"
}
@Composable
private fun LocalArtworkThumbnail(track: LocalTrack, repository: LocalArtworkRepository?, size: androidx.compose.ui.unit.Dp = 112.dp) {
    val density = LocalDensity.current
    val targetEdgePx = with(density) { size.toPx().roundToInt().coerceAtLeast(1) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, repository, track.pathKey, targetEdgePx) {
        value = repository?.let { artwork -> runCatching { artwork.thumbnail(track, targetEdgePx) }.getOrNull() }
    }
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(requireNotNull(bitmap)),
            contentDescription = "${track.album} 封面",
            modifier = Modifier.fillMaxWidth().heightIn(max = size).clip(RoundedCornerShape(10.dp)),
        )
    } else {
        Box(
            Modifier.fillMaxWidth().heightIn(min = size, max = size).clip(RoundedCornerShape(10.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Text("无封面", style = MiuixTheme.textStyles.body2) }
    }
}

@Composable
private fun LocalTrackRow(
    track: LocalTrack,
    onPlayTrack: ((LocalTrack) -> Unit)?,
    onPlayNextTrack: ((LocalTrack) -> Unit)?,
    onQueueTrack: ((LocalTrack) -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp).testTag("local-track-${track.id}")
            .pointerInput(track.id, onPlayTrack) {
                detectTapGestures(onDoubleTap = { onPlayTrack?.invoke(track) })
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(track.title.ifBlank { "未知标题" }, style = MiuixTheme.textStyles.body1)
            Spacer(Modifier.weight(1f))
            Text("${track.format} · ${track.metadataStatus}")
        }
        Text("${track.artists.joinToString().ifBlank { "未知艺术家" }} · ${track.album.ifBlank { "未知专辑" }}")
        Text(track.audioQuality.summary(), style = MiuixTheme.textStyles.body2)
        track.error?.let { Text("${it.kind} · ${it.summary}") }
        Text(track.filePath, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.testTag("local-track-play-${track.id}"),
                enabled = onPlayTrack != null,
                colors = ButtonDefaults.buttonColorsPrimary(),
                onClick = { onPlayTrack?.invoke(track) },
            ) { Text("播放") }
            Button(
                modifier = Modifier.testTag("local-track-next-${track.id}"),
                enabled = onPlayNextTrack != null,
                onClick = { onPlayNextTrack?.invoke(track) },
            ) { Text("下一首") }
            Button(
                modifier = Modifier.testTag("local-track-queue-${track.id}"),
                enabled = onQueueTrack != null,
                onClick = { onQueueTrack?.invoke(track) },
            ) { Text("加入队列") }
        }
        HorizontalDivider()
    }
}

private fun chooseMusicDirectories(): List<Path> {
    return DesktopFilePicker.chooseMusicDirectories()
}

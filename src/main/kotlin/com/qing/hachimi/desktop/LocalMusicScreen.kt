package com.qing.hachimi.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.nio.file.Path
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser

@Composable
internal fun LocalMusicScreen(viewModel: LocalMusicViewModel, playerViewModel: PlayerViewModel? = null) {
    val state by viewModel.state.collectAsState()
    LocalMusicContent(
        state = state,
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
        onPlayTracks = playerViewModel?.let { player -> { tracks -> player.enqueue(tracks) } },
        onStartTracks = playerViewModel?.let { player -> { tracks -> player.playTracks(tracks); Unit } },
        onArtistFilter = viewModel::setArtistFilter,
        onAlbumFilter = viewModel::setAlbumFilter,
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
    onSearchFilter: (String) -> Unit = {},
    onPlayTrack: ((LocalTrack) -> Unit)? = null,
    onQueueTrack: ((LocalTrack) -> Unit)? = null,
    onPlayTracks: ((List<LocalTrack>) -> Unit)? = null,
    onStartTracks: ((List<LocalTrack>) -> Unit)? = null,
) {
    val albums = state.tracks.filter { it.album.isNotBlank() }
        .groupBy { (it.albumArtist.ifBlank { it.artists.firstOrNull().orEmpty() } + "\u0000" + it.album).lowercase(Locale.ROOT) }
        .values.map { group -> group.first() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("只读取所选目录中的音乐标签和技术参数。扫描、哈希和索引不会修改音频文件。")
        Text("参数差异不代表音源质量；候选身份需要人工核对。此阶段不会下载、替换或删除文件。", color = MiuixTheme.colorScheme.primary)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                modifier = Modifier.testTag("local-music-add-root"),
                onClick = { onAddDirectories(chooseMusicDirectories()) },
            ) { Text("添加音乐目录…") }
            Button(
                modifier = Modifier.testTag("local-music-scan"),
                enabled = state.roots.isNotEmpty() && !state.isScanning && !state.isAnalyzingDuplicates,
                colors = ButtonDefaults.buttonColorsPrimary(),
                onClick = onScan,
            ) { Text(if (state.isScanning) "扫描中…" else "开始扫描") }
            if (state.isScanning) {
                Button(modifier = Modifier.testTag("local-music-cancel-scan"), onClick = onCancelScan) { Text("取消扫描") }
            }
            Button(
                modifier = Modifier.testTag("local-music-hash-duplicates"),
                enabled = state.tracks.isNotEmpty() && !state.isScanning && !state.isAnalyzingDuplicates,
                onClick = onAnalyzeDuplicates,
            ) { Text(if (state.isAnalyzingDuplicates) "校验中…" else "分析重复文件") }
            if (state.isAnalyzingDuplicates) {
                Button(modifier = Modifier.testTag("local-music-cancel-analysis"), onClick = onCancelAnalysis) {
                    Text("取消校验")
                }
            }
            Button(
                modifier = Modifier.testTag("local-music-upgrade-preview"),
                enabled = state.tracks.isNotEmpty(),
                onClick = onPreviewUpgrades,
            ) { Text("升级候选预览") }
        }

        state.recoveryWarning?.let { Text(it, color = MiuixTheme.colorScheme.primary) }
        Text(state.message, modifier = Modifier.testTag("local-music-status"))
        state.progress?.let { progress ->
            Text("扫描进度 ${progress.completedFiles} / ${progress.totalFiles} · ${progress.currentPath?.substringAfterLast('\\').orEmpty()}")
        }
        state.analysisProgress?.let { progress ->
            Text("内容校验 ${progress.completedFiles} / ${progress.totalFiles} · ${progress.currentPath?.substringAfterLast('\\').orEmpty()}")
        }

        if (state.roots.isNotEmpty()) {
            Text("扫描目录 ${state.roots.size} 个")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                items(state.roots, key = { it.path }) { root ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(root.path, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.size(width = 320.dp, height = 32.dp))
                        Button(
                            modifier = Modifier.testTag("local-music-watch-${root.path.hashCode()}"),
                            onClick = { onToggleRootAutoWatch(root) },
                        ) { Text(if (root.autoWatch) "关闭自动监听" else "自动监听") }
                        Button(
                            modifier = Modifier.testTag("local-music-remove-root"),
                            enabled = !state.isScanning && !state.isAnalyzingDuplicates,
                            onClick = { onRemoveRoot(root.path) },
                        ) { Text("移除索引") }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("专辑封面墙", style = MiuixTheme.textStyles.headline2)
            Spacer(Modifier.weight(1f))
            Text("${albums.size} 张专辑")
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp).testTag("local-music-album-wall"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(albums, key = { it.pathKey }) { albumTrack ->
                Column(
                    modifier = Modifier.size(width = 132.dp, height = 178.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            onAlbumFilter(albumTrack.album)
                            onArtistFilter(albumTrack.albumArtist.ifBlank { albumTrack.artists.firstOrNull().orEmpty() })
                        }
                        .testTag("local-album-${albumTrack.pathKey.hashCode()}"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    LocalArtworkThumbnail(albumTrack)
                    Text(albumTrack.album, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(albumTrack.albumArtist.ifBlank { albumTrack.artists.joinToString() }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body2)
                }
            }
            if (albums.isEmpty()) item { Text("扫描后将在这里展示本地专辑封面。") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.searchFilter,
                onValueChange = onSearchFilter,
                label = "搜索歌曲",
                singleLine = true,
                modifier = Modifier.weight(1.1f).testTag("local-music-search"),
            )
            TextField(
                value = state.formatFilter,
                onValueChange = onFormatFilter,
                label = "格式筛选",
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("local-music-format-filter"),
            )
            TextField(
                value = state.artistFilter,
                onValueChange = onArtistFilter,
                label = "歌手筛选",
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("local-music-artist-filter"),
            )
            TextField(
                value = state.albumFilter,
                onValueChange = onAlbumFilter,
                label = "专辑筛选",
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("local-music-album-filter"),
            )
            Text("${state.visibleTracks.size} / ${state.tracks.size} 首")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.testTag("local-music-play-filtered"),
                enabled = state.visibleTracks.isNotEmpty() && onStartTracks != null,
                colors = ButtonDefaults.buttonColorsPrimary(),
                onClick = { onStartTracks?.invoke(state.visibleTracks) },
            ) { Text("播放筛选曲目") }
            Button(
                modifier = Modifier.testTag("local-music-queue-filtered"),
                enabled = state.visibleTracks.isNotEmpty() && onPlayTracks != null,
                onClick = { onPlayTracks?.invoke(state.visibleTracks) },
            ) { Text("筛选曲目加入队列") }
        }
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("local-music-table"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(state.visibleTracks, key = { it.id }) { track ->
                LocalTrackRow(track, onPlayTrack, onQueueTrack)
            }
            if (state.visibleTracks.isEmpty()) {
                item { Text("暂无符合条件的曲目。", modifier = Modifier.padding(12.dp)) }
            }
        }

        state.duplicateAnalysis?.let { analysis ->
            Column(Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                Text("完全重复 ${analysis.exactDuplicates.size} 组（流式 SHA-256 内容相同）")
                analysis.exactDuplicates.take(30).forEachIndexed { index, group ->
                    Text("${index + 1}. ${group.tracks.joinToString("  ↔  ") { it.filePath }}")
                }
                Text("疑似重复 ${analysis.similarCandidates.size} 组（元数据线索，不代表文件相同）")
                analysis.similarCandidates.take(30).forEachIndexed { index, group ->
                    Text("${index + 1}. ${group.tracks.joinToString("  ↔  ") { it.title }} · ${group.conflicts.joinToString("、").ifBlank { "未发现明显冲突" }}")
                }
            }
        }
        if (state.upgradeCandidates.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                Text("升级候选预览 ${state.upgradeCandidates.size} 项")
                state.upgradeCandidates.take(20).forEach { candidate ->
                    Text("${candidate.localTrackId.take(10)} · ${candidate.identityStatus} · ${candidate.qualityComparison} · ${candidate.reason}")
                }
            }
        }
    }
}

@Composable
private fun LocalArtworkThumbnail(track: LocalTrack) {
    val payload by produceState<ArtworkPayload?>(initialValue = null, track.filePath) {
        value = withContext(Dispatchers.IO) {
            runCatching { LocalArtworkResolver().resolve(Path.of(track.filePath)) }.getOrNull()
        }
    }
    val bitmap = androidx.compose.runtime.remember(payload) {
        payload?.let { runCatching { it.bytes.decodeToImageBitmap() }.getOrNull() }
    }
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(bitmap),
            contentDescription = "${track.album} 封面",
            modifier = Modifier.size(112.dp).clip(RoundedCornerShape(10.dp)),
        )
    } else {
        Box(
            Modifier.size(112.dp).clip(RoundedCornerShape(10.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Text("无封面", style = MiuixTheme.textStyles.body2) }
    }
}

@Composable
private fun LocalTrackRow(
    track: LocalTrack,
    onPlayTrack: ((LocalTrack) -> Unit)?,
    onQueueTrack: ((LocalTrack) -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp).testTag("local-track-${track.id}"),
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
                modifier = Modifier.testTag("local-track-queue-${track.id}"),
                enabled = onQueueTrack != null,
                onClick = { onQueueTrack?.invoke(track) },
            ) { Text("加入队列") }
        }
        HorizontalDivider()
    }
}

private fun chooseMusicDirectories(): List<Path> {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isMultiSelectionEnabled = true
        dialogTitle = "选择本地音乐目录"
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return emptyList()
    val selections = chooser.selectedFiles.orEmpty().map { it.toPath() }
    return selections.ifEmpty { listOfNotNull(chooser.selectedFile?.toPath()) }
}

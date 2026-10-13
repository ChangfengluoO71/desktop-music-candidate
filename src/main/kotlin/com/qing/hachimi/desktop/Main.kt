package com.qing.hachimi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.util.Locale

fun main() = application {
    // Keep shutdown work alive for the full application composition. A scope created
    // inside Window content is disposed as that window closes.
    val shutdownScope = rememberCoroutineScope()
    val appDataDirectory = remember { desktopAppDataDirectory() }
    val instanceLock = remember {
        DesktopInstanceLock.tryAcquire(appDataDirectory.resolve("instance.lock"))
    }
    val viewModel = remember(instanceLock) {
        instanceLock?.let { DesktopViewModel.createDefault(appDataDirectory) }
    }
    val localMusicViewModel = remember(instanceLock) {
        instanceLock?.let { LocalMusicViewModel.createDefault(appDataDirectory) }
    }
    val playerViewModel = remember(localMusicViewModel) {
        localMusicViewModel?.let { localMusic ->
            PlayerViewModel(
                coordinator = PlaybackCoordinator(JavaFxPlaybackEngine()),
                localMusicViewModel = localMusic,
                queueStore = PlaybackQueueStore(appDataDirectory.resolve("playback-queue.json")),
            )
        }
    }
    val appIcon = remember {
        val iconBytes = requireNotNull(
            Thread.currentThread().contextClassLoader.getResourceAsStream("dmc-icon.png"),
        ) { "Missing application icon resource dmc-icon.png" }.use { it.readBytes() }
        BitmapPainter(iconBytes.decodeToImageBitmap())
    }
    val exitApplicationNow: () -> Unit = { exitApplication() }
    var closeRequested by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }

    Window(
        icon = appIcon,
        onCloseRequest = {
            if (viewModel == null) {
                instanceLock?.close()
                exitApplicationNow()
            } else {
                closeRequested = true
            }
        },
        title = DesktopProductIdentity.WINDOW_TITLE,
        state = rememberWindowState(width = 1280.dp, height = 820.dp),
        resizable = true,
    ) {
        if (viewModel == null) {
            DuplicateInstanceScreen(onExit = {
                instanceLock?.close()
                exitApplicationNow()
            })
        } else {
            HachimiDesktopApp(viewModel, localMusicViewModel, playerViewModel)
        }
        if (closeRequested && viewModel != null) {
            DialogWindow(
                onCloseRequest = { if (!closing) closeRequested = false },
                title = "退出 ${DesktopProductIdentity.DISPLAY_NAME}",
                resizable = false,
            ) {
                Column(
                    Modifier.width(440.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MiuixTheme.colorScheme.background)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("退出 ${DesktopProductIdentity.DISPLAY_NAME}", style = MiuixTheme.textStyles.headline2)
                    Text("运行中的下载任务会暂停并保存进度；播放设备会释放，播放队列会保存但不会自动播放。")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            enabled = !closing,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            onClick = {
                                closing = true
                                shutdownScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val playerShutdown = async { runCatching { playerViewModel?.close() } }
                                            val desktopShutdown = async { runCatching { viewModel.shutdown() } }
                                            val localMusicShutdown = async { runCatching { localMusicViewModel?.shutdown() } }
                                            playerShutdown.await()
                                            desktopShutdown.await()
                                            localMusicShutdown.await()
                                        }
                                    } finally {
                                        runCatching { instanceLock?.close() }
                                        exitApplicationNow()
                                    }
                                }
                            },
                        ) { Text(if (closing) "正在保存…" else "暂停并退出") }
                        Button(
                            enabled = !closing,
                            onClick = { closeRequested = false },
                        ) { Text("继续使用") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateInstanceScreen(onExit: () -> Unit) {
    MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("${DesktopProductIdentity.DISPLAY_NAME} 已在运行", style = MiuixTheme.textStyles.headline1)
            Text("为保护任务文件，不能同时启动第二个实例。请切换到已打开的窗口后继续。")
            Button(colors = ButtonDefaults.buttonColorsPrimary(), onClick = onExit) {
                Text("关闭此窗口")
            }
        }
    }
}

@Composable
internal fun HachimiDesktopApp(
    viewModel: DesktopViewModel,
    localMusicViewModel: LocalMusicViewModel? = null,
    playerViewModel: PlayerViewModel? = null,
) {
    val ui by viewModel.ui.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val collectionImports by viewModel.collectionImports.collectAsState()
    val persistenceWarning by viewModel.persistenceWarning.collectAsState()
    val controller = remember { ThemeController(ColorSchemeMode.System) }

    MiuixTheme(controller = controller) {
        Row(
            Modifier.fillMaxSize()
                .background(MiuixTheme.colorScheme.background),
        ) {
            Column(
                Modifier.width(204.dp).fillMaxHeight().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(DesktopProductIdentity.DISPLAY_NAME, style = MiuixTheme.textStyles.headline1)
                Text("Independent desktop candidate", style = MiuixTheme.textStyles.body2)
                Spacer(Modifier.height(8.dp))
                NavigationButton("发现", ui.page == DesktopPage.DISCOVERY, Modifier.testTag("nav-discovery")) {
                    viewModel.navigate(DesktopPage.DISCOVERY)
                }
                NavigationButton("歌曲搜索", ui.page == DesktopPage.SEARCH) {
                    viewModel.navigate(DesktopPage.SEARCH)
                }
                NavigationButton("歌单 / 专辑", ui.page == DesktopPage.COLLECTIONS) {
                    viewModel.navigate(DesktopPage.COLLECTIONS)
                }
                NavigationButton("下载管理", ui.page == DesktopPage.DOWNLOADS) {
                    viewModel.navigate(DesktopPage.DOWNLOADS)
                }
                NavigationButton("本地音乐", ui.page == DesktopPage.LOCAL_MUSIC, Modifier.testTag("nav-local-music")) {
                    viewModel.navigate(DesktopPage.LOCAL_MUSIC)
                }
                NavigationButton("设置", ui.page == DesktopPage.SETTINGS) {
                    viewModel.navigate(DesktopPage.SETTINGS)
                }
                Spacer(Modifier.weight(1f))
                Text("仅保存你有权下载的内容。")
            }
            Box(Modifier.fillMaxHeight().width(1.dp).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)))
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 26.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        when (ui.page) {
                            DesktopPage.DISCOVERY -> "发现"
                            DesktopPage.SEARCH -> "歌曲搜索"
                            DesktopPage.COLLECTIONS -> "歌单 / 专辑"
                            DesktopPage.DOWNLOADS -> "下载管理"
                            DesktopPage.LOCAL_MUSIC -> "本地音乐"
                            DesktopPage.SETTINGS -> "设置"
                        },
                        style = MiuixTheme.textStyles.headline1,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("并发 ${settings.maxConcurrentDownloads} · ${tasks.count { it.status == DownloadTaskStatus.DOWNLOADING }} 个正在下载")
                }
                persistenceWarning?.let {
                    Text(it, color = MiuixTheme.colorScheme.primary)
                }
                ui.actionMessage?.let { Text(it) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (ui.page) {
                        DesktopPage.DISCOVERY -> DiscoveryScreen(viewModel, ui, settings)
                        DesktopPage.SEARCH -> SearchScreen(viewModel, ui, settings)
                        DesktopPage.COLLECTIONS -> CollectionScreen(viewModel, ui, settings, tasks, collectionImports)
                        DesktopPage.DOWNLOADS -> DownloadsScreen(viewModel, tasks, collectionImports)
                        DesktopPage.LOCAL_MUSIC -> if (localMusicViewModel != null) LocalMusicScreen(localMusicViewModel, playerViewModel)
                        else Text("本地音乐索引暂时不可用。")
                        DesktopPage.SETTINGS -> SettingsScreen(viewModel, settings, ui.downloadDiagnosticsEnabled)
                    }
                }
                playerViewModel?.let { PlayerMiniBar(it) }
            }
        }
        ui.artworkPreview?.let { ArtworkPreviewDialog(viewModel, it) }
    }
}

@Composable
private fun NavigationButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        modifier = modifier.fillMaxWidth(),
        colors = if (selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
        onClick = onClick,
    ) { Text(label) }
}

@Composable
internal fun DiscoveryScreen(viewModel: DesktopViewModel, ui: DesktopUiState, settings: DesktopSettings) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(ui.discoveryMessage, Modifier.weight(1f), style = MiuixTheme.textStyles.body2)
            Button(
                modifier = Modifier.testTag("discovery-retry"),
                enabled = ui.session.status == SessionStatus.SIGNED_IN && !ui.discoveryLoading,
                onClick = viewModel::loadDiscovery,
            ) { Text(if (ui.discoveryLoading) "正在刷新…" else "刷新") }
        }
        HorizontalDivider()
        when (ui.discoveryDetail) {
            DiscoveryDetail.HOME -> DiscoveryHome(viewModel, ui)
            DiscoveryDetail.DAILY_RECOMMENDATIONS -> DiscoverySongList(
                viewModel = viewModel,
                ui = ui,
                settings = settings,
                title = "每日推荐",
                songs = ui.dailyRecommendations,
                selectedCount = ui.selectedDailyRecommendationIds.size,
                onBack = viewModel::closeDiscoveryDetail,
                onSelectAll = viewModel::selectAllDailyRecommendations,
                onClearSelection = viewModel::clearDiscoverySelection,
                onToggleDaily = viewModel::toggleDailyRecommendationSelection,
            )
            DiscoveryDetail.USER_PLAYLIST -> DiscoveryPlaylistSongs(viewModel, ui, settings)
        }
    }
}

@Composable
private fun DiscoveryHome(viewModel: DesktopViewModel, ui: DesktopUiState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            modifier = Modifier.fillMaxWidth().testTag("discovery-daily"),
            enabled = ui.session.status == SessionStatus.SIGNED_IN && !ui.dailyRecommendationsLoading,
            colors = ButtonDefaults.buttonColorsPrimary(),
            onClick = viewModel::loadDailyRecommendations,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("♥", style = MiuixTheme.textStyles.headline1)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("每日推荐", style = MiuixTheme.textStyles.headline2)
                    Text("打开今天为你推荐的歌曲（${ui.dailyRecommendations.size} 首）", style = MiuixTheme.textStyles.body2)
                }
                Text("›", style = MiuixTheme.textStyles.headline1)
            }
        }
        Text("我的歌单", style = MiuixTheme.textStyles.headline2)
        if (ui.myPlaylists.isEmpty()) {
            Text(if (ui.discoveryLoading) "正在读取个人歌单…" else "暂时没有个人歌单。")
            Spacer(Modifier.weight(1f))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 190.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                gridItems(ui.myPlaylists, key = { it.id }) { playlist ->
                    Button(
                        modifier = Modifier.fillMaxWidth().testTag("discovery-playlist-${playlist.id}"),
                        onClick = { viewModel.openUserPlaylist(playlist.id) },
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            ArtworkThumbnail(
                                viewModel = viewModel,
                                url = playlist.coverUrl,
                                tag = "artwork-thumbnail-playlist-${playlist.id}",
                                modifier = Modifier.size(76.dp),
                                onClick = { viewModel.previewArtwork(playlist.name, playlist.coverUrl) },
                            )
                            Text(playlist.name.ifBlank { "未命名歌单" }, style = MiuixTheme.textStyles.body1)
                            Text("${playlist.trackCount} 首 · ${playlist.creator}", style = MiuixTheme.textStyles.body2)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryPlaylistSongs(viewModel: DesktopViewModel, ui: DesktopUiState, settings: DesktopSettings) {
    val result = ui.activePlaylist
    val songs = result?.items.orEmpty().mapNotNull { row -> row.song }
    val title = result?.descriptor?.name
        ?: ui.myPlaylists.firstOrNull { it.id == ui.activePlaylistId }?.name
        ?: "歌单曲目"
    DiscoverySongList(
        viewModel = viewModel,
        ui = ui,
        settings = settings,
        title = title,
        songs = songs,
        selectedCount = ui.selectedUserPlaylistIndices.size,
        onBack = viewModel::closeUserPlaylist,
        onSelectAll = viewModel::selectAllUserPlaylistTracks,
        onClearSelection = viewModel::clearDiscoverySelection,
        onTogglePlaylist = viewModel::toggleUserPlaylistSelection,
        playlistRows = result?.items.orEmpty(),
        loading = ui.activePlaylistLoading,
    )
}

@Composable
private fun DiscoverySongList(
    viewModel: DesktopViewModel,
    ui: DesktopUiState,
    settings: DesktopSettings,
    title: String,
    songs: List<SongHit>,
    selectedCount: Int,
    onBack: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onToggleDaily: (Long) -> Unit = {},
    onTogglePlaylist: (Int) -> Unit = {},
    playlistRows: List<CollectionResolvedItem> = emptyList(),
    loading: Boolean = ui.dailyRecommendationsLoading,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(modifier = Modifier.testTag("discovery-playlist-back"), onClick = onBack) { Text("返回") }
            Text(title, style = MiuixTheme.textStyles.headline2)
            Spacer(Modifier.weight(1f))
            Text("音质 ${settings.defaultQuality} · 已选 $selectedCount 首", style = MiuixTheme.textStyles.body2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onSelectAll, enabled = songs.isNotEmpty()) { Text("全选") }
            Button(onClick = onClearSelection, enabled = selectedCount > 0) { Text("清除选择") }
            Spacer(Modifier.weight(1f))
            Button(
                modifier = Modifier.testTag("discovery-add-selected"),
                enabled = selectedCount > 0,
                colors = ButtonDefaults.buttonColorsPrimary(),
                onClick = viewModel::enqueueSelectedDiscoverySongs,
            ) { Text("加入下载队列（$selectedCount）") }
        }
        HorizontalDivider()
        when {
            loading -> Text("正在读取歌曲…")
            songs.isEmpty() && playlistRows.isEmpty() -> Text("暂时没有可显示的歌曲。")
            else -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (playlistRows.isEmpty()) {
                    itemsIndexed(songs, key = { index, song -> "${song.id}-$index" }) { index, song ->
                        DiscoverySongRow(
                            viewModel = viewModel,
                            song = song,
                            selected = song.id in ui.selectedDailyRecommendationIds,
                            selectable = true,
                            tag = "discovery-song-${song.id}-$index",
                            collectionName = title,
                            onToggle = { onToggleDaily(song.id) },
                        )
                    }
                } else {
                    items(playlistRows, key = { it.originalIndex }) { item ->
                        val song = item.song
                        if (song != null && item.status == CollectionItemStatus.RESOLVED) {
                            DiscoverySongRow(
                                viewModel = viewModel,
                                song = song,
                                selected = item.originalIndex in ui.selectedUserPlaylistIndices,
                                selectable = true,
                                tag = "discovery-song-${song.id}-${item.originalIndex}",
                                collectionName = title,
                                onToggle = { onTogglePlaylist(item.originalIndex) },
                            )
                        } else {
                            Text("${item.originalIndex}. ${item.message ?: collectionItemStatusLabel(item.status)}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverySongRow(
    viewModel: DesktopViewModel,
    song: SongHit,
    selected: Boolean,
    selectable: Boolean,
    tag: String,
    collectionName: String,
    onToggle: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                modifier = Modifier.testTag(tag),
                enabled = selectable,
                colors = if (selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                onClick = onToggle,
            ) { Text(if (selected) "✓ 已选" else "选择") }
            ArtworkThumbnail(
                viewModel = viewModel,
                url = song.coverUrl,
                tag = "artwork-thumbnail-song-${song.id}",
                modifier = Modifier.size(64.dp),
                onClick = { viewModel.previewSongArtwork(song, collectionName) },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(song.name, style = MiuixTheme.textStyles.body1)
                Text("${song.artists} · ${song.album}", style = MiuixTheme.textStyles.body2)
            }
            Text("${song.id}", style = MiuixTheme.textStyles.body2)
        }
        HorizontalDivider()
    }
}

@Composable
private fun SearchScreen(viewModel: DesktopViewModel, ui: DesktopUiState, settings: DesktopSettings) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = ui.query,
            onValueChange = viewModel::updateQuery,
            label = "输入歌名或歌手",
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            enabled = !ui.searchLoading && ui.query.isNotBlank(),
            colors = ButtonDefaults.buttonColorsPrimary(),
            onClick = viewModel::search,
        ) { Text(if (ui.searchLoading) "搜索中…" else "搜索") }
    }
    QrLoginPanel(
        ui = ui,
        onStart = viewModel::startQrLogin,
        onCancel = viewModel::cancelQrLogin,
        onToggleRemember = viewModel::toggleRememberLogin,
        onLogout = viewModel::logout,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("音质")
        QualityButton("标准", "standard", ui.requestedQuality, viewModel::setQuality)
        QualityButton("极高", "exhigh", ui.requestedQuality, viewModel::setQuality)
        QualityButton("无损", "lossless", ui.requestedQuality, viewModel::setQuality)
        QualityButton("Hi-Res", "hires", ui.requestedQuality, viewModel::setQuality)
        Spacer(Modifier.weight(1f))
        Text("保存到：${settings.defaultDownloadDirectory}")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(ui.searchMessage)
        Spacer(Modifier.weight(1f))
        Button(
            modifier = Modifier.testTag("search-add-selected"),
            enabled = ui.selectedSongIds.isNotEmpty(),
            colors = ButtonDefaults.buttonColorsPrimary(),
            onClick = viewModel::addSelectedSongs,
        ) { Text("添加选中 ${ui.selectedSongIds.size} 首") }
        Button(onClick = viewModel::clearSelection) { Text("清除选择") }
    }
    HorizontalDivider()
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(ui.searchResults, key = { it.id }) { song ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { viewModel.toggleSelection(song.id) },
                    colors = if (song.id in ui.selectedSongIds) ButtonDefaults.buttonColorsPrimary()
                    else ButtonDefaults.buttonColors(),
                ) { Text(if (song.id in ui.selectedSongIds) "✓ 已选" else "选择") }
                ArtworkThumbnail(
                    viewModel = viewModel,
                    url = song.coverUrl,
                    tag = "artwork-thumbnail-search-${song.id}",
                    modifier = Modifier.size(64.dp),
                    onClick = { viewModel.previewSongArtwork(song) },
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(song.name, style = MiuixTheme.textStyles.body1)
                    Text("${song.artists}  ·  ${song.album}  ·  #${song.id}", style = MiuixTheme.textStyles.body2)
                }
                Button(onClick = { viewModel.addSong(song) }) { Text("加入队列") }
            }
            HorizontalDivider()
        }
    }
    }
}

@Composable
internal fun QrLoginPanel(
    ui: DesktopUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onToggleRemember: () -> Unit,
    onLogout: () -> Unit,
) {
    val active = ui.qrLoginStatus in setOf(
        DesktopQrLoginStatus.CREATING,
        DesktopQrLoginStatus.WAITING,
        DesktopQrLoginStatus.SCANNED,
        DesktopQrLoginStatus.VERIFYING,
    )
    val statusText = when (ui.qrLoginStatus) {
        DesktopQrLoginStatus.IDLE -> null
        DesktopQrLoginStatus.CREATING -> "正在获取二维码…"
        DesktopQrLoginStatus.WAITING -> "等待手机网易云音乐扫码…"
        DesktopQrLoginStatus.SCANNED -> "已扫码，请在手机上确认登录…"
        DesktopQrLoginStatus.VERIFYING -> "正在验证账号会话…"
        DesktopQrLoginStatus.SIGNED_IN -> "登录成功。"
        DesktopQrLoginStatus.EXPIRED -> "二维码已过期，请重新获取。"
        DesktopQrLoginStatus.FAILED -> "扫码登录失败，请重新获取二维码。"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (ui.session.status) {
            SessionStatus.CHECKING -> Text("正在检查保存的登录状态…")
            SessionStatus.SIGNING_IN -> Text("正在验证扫码登录…")
            SessionStatus.SIGNED_IN -> Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("账号会话有效")
                Spacer(Modifier.weight(1f))
                Button(onClick = onLogout) { Text("退出登录") }
            }
            SessionStatus.SIGNED_OUT, SessionStatus.SESSION_EXPIRED, SessionStatus.REAUTH_REQUIRED -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (ui.session.status) {
                            SessionStatus.SESSION_EXPIRED -> "登录状态已过期"
                            SessionStatus.REAUTH_REQUIRED -> "需要重新登录"
                            else -> "尚未登录"
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onToggleRemember, enabled = !active) {
                        Text(if (ui.rememberLogin) "记住此设备：是" else "记住此设备：否")
                    }
                    if (!active) {
                        Button(
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            onClick = onStart,
                        ) { Text(if (ui.qrLoginStatus == DesktopQrLoginStatus.IDLE) "手机扫码登录" else "重新获取二维码") }
                    }
                    if (active && ui.qrCode == null) Button(onClick = onCancel) { Text("取消扫码") }
                }
                if (ui.qrCode != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DesktopQrCodeImage(ui.qrCode)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(statusText.orEmpty())
                            Text("用手机网易云音乐扫一扫，并在手机上确认登录。")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onStart) { Text("刷新二维码") }
                                Button(onClick = onCancel) { Text("取消扫码") }
                            }
                        }
                    }
                } else if (statusText != null) {
                    Text(statusText)
                }
                Text("未登录也可搜索；勾选“记住此设备”后，会将会话保存到当前 Windows 用户的凭据管理器。")
            }
        }
    }
}

@Composable
private fun DesktopQrCodeImage(code: QrCodeData) {
    Canvas(Modifier.size(220.dp).background(Color.White)) {
        val cellWidth = size.width / code.size
        val cellHeight = size.height / code.size
        code.darkModules.forEachIndexed { index, isDark ->
            if (isDark) {
                drawRect(
                    color = Color.Black,
                    topLeft = Offset((index % code.size) * cellWidth, (index / code.size) * cellHeight),
                    size = Size(cellWidth + 0.5f, cellHeight + 0.5f),
                )
            }
        }
    }
}

@Composable
private fun CollectionScreen(
    viewModel: DesktopViewModel,
    ui: DesktopUiState,
    settings: DesktopSettings,
    tasks: List<DownloadTask>,
    imports: List<CollectionImport>,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = ui.collectionLink,
                onValueChange = viewModel::updateCollectionLink,
                label = "music.163.com 歌单或专辑链接",
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = !ui.collectionLoading && ui.collectionLink.isNotBlank(),
                colors = ButtonDefaults.buttonColorsPrimary(),
                onClick = viewModel::loadCollection,
            ) { Text(if (ui.collectionLoading) "读取中…" else "读取并预览") }
        }
        Text(ui.collectionMessage)

        val preview = ui.collectionPreview
        if (preview != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val firstSong = preview.items.firstNotNullOfOrNull { it.song }
                ArtworkThumbnail(
                    viewModel = viewModel,
                    url = preview.descriptor.coverUrl,
                    tag = "artwork-thumbnail-collection-${preview.descriptor.reference.id}",
                    modifier = Modifier.size(76.dp),
                    onClick = {
                        if (preview.descriptor.reference.type == CollectionSourceType.ALBUM) {
                            viewModel.previewAlbumArtwork(
                                preview.descriptor.name,
                                preview.descriptor.creator,
                                preview.descriptor.coverUrl,
                                firstSong,
                                preview.descriptor.name,
                            )
                        } else {
                            viewModel.previewArtwork(preview.descriptor.name, preview.descriptor.coverUrl)
                        }
                    },
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(preview.descriptor.name, style = MiuixTheme.textStyles.headline2)
                    Text(
                        "${if (preview.descriptor.reference.type == CollectionSourceType.PLAYLIST) "歌单" else "专辑"} · " +
                            "${preview.totalCount} 首 · ${preview.descriptor.creator.ifBlank { "未知作者" }}",
                    )
                }
                Text("音质")
                QualityButton("标准", "standard", ui.requestedQuality, viewModel::setQuality)
                QualityButton("极高", "exhigh", ui.requestedQuality, viewModel::setQuality)
                QualityButton("无损", "lossless", ui.requestedQuality, viewModel::setQuality)
                QualityButton("Hi-Res", "hires", ui.requestedQuality, viewModel::setQuality)
            }
            TextField(
                value = ui.collectionDownloadDirectory.ifBlank { settings.defaultDownloadDirectory },
                onValueChange = viewModel::updateCollectionDownloadDirectory,
                label = "保存根目录",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("分组：${folderGroupingLabel(settings.folderGrouping)} · 选择 ${ui.selectedCollectionIndices.size} 首")
                Spacer(Modifier.weight(1f))
                Button(onClick = viewModel::selectAllResolvedCollectionItems) { Text("全选可用曲目") }
                Button(onClick = viewModel::clearCollectionSelection) { Text("清除选择") }
                Button(onClick = viewModel::closeCollectionPreview) { Text("关闭预览") }
                Button(
                    modifier = Modifier.testTag("collection-add-selected"),
                    enabled = ui.selectedCollectionIndices.isNotEmpty(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    onClick = viewModel::addSelectedCollectionItems,
                ) { Text("确认添加 ${ui.selectedCollectionIndices.size} 首") }
            }
            HorizontalDivider()
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(preview.items, key = { it.originalIndex }) { item ->
                    val song = item.song
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            enabled = item.status == CollectionItemStatus.RESOLVED,
                            colors = if (item.originalIndex in ui.selectedCollectionIndices) {
                                ButtonDefaults.buttonColorsPrimary()
                            } else ButtonDefaults.buttonColors(),
                            onClick = { viewModel.toggleCollectionSelection(item.originalIndex) },
                        ) { Text(if (item.originalIndex in ui.selectedCollectionIndices) "✓" else "选择") }
                        Text(String.format(Locale.ROOT, "%02d", item.originalIndex))
                        ArtworkThumbnail(
                            viewModel = viewModel,
                            url = song?.coverUrl.orEmpty(),
                            tag = "artwork-thumbnail-collection-song-${item.originalIndex}",
                            modifier = Modifier.size(52.dp),
                            onClick = { song?.let { viewModel.previewSongArtwork(it, preview.descriptor.name) } },
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(song?.name ?: "未知曲目 #${item.songId ?: "?"}")
                            Text(song?.let { "${it.artists} · ${it.album}" } ?: item.message.orEmpty())
                        }
                        Text(collectionItemStatusLabel(item.status))
                    }
                    HorizontalDivider()
                }
            }
        } else if (imports.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("读取的内容会先显示预览；只有点击“确认添加”后才会进入下载队列。")
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(imports, key = { it.id }) { record ->
                    CollectionImportRow(viewModel, record, tasks)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
internal fun ArtworkThumbnail(
    viewModel: DesktopViewModel,
    url: String,
    tag: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(null, url) {
        value = if (url.isBlank()) null else {
            viewModel.loadArtwork(url)?.let { bytes ->
                withContext(Dispatchers.Default) { runCatching { bytes.decodeToImageBitmap() }.getOrNull() }
            }
        }
    }
    Box(
        modifier.size(76.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap = requireNotNull(bitmap), contentDescription = "Cover preview", modifier = Modifier.fillMaxSize())
        } else {
            Text("封面")
        }
    }
}

@Composable
internal fun ArtworkPreviewDialog(viewModel: DesktopViewModel, preview: ArtworkPreviewState) {
    val bitmap by produceState<ImageBitmap?>(null, preview.payload?.bytes?.contentHashCode()) {
        val bytes = preview.payload?.bytes
        value = bytes?.let { data ->
            withContext(Dispatchers.Default) { runCatching { data.decodeToImageBitmap() }.getOrNull() }
        }
    }
    DialogWindow(
        onCloseRequest = viewModel::closeArtworkPreview,
        title = "封面预览",
        resizable = false,
    ) {
        Column(
            Modifier.width(520.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MiuixTheme.colorScheme.background)
                .padding(22.dp)
                .testTag("artwork-preview-dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(preview.title, style = MiuixTheme.textStyles.headline2)
                    if (preview.artist.isNotBlank() || preview.albumName.isNotBlank()) {
                        Text(listOf(preview.artist, preview.albumName).filter(String::isNotBlank).joinToString(" · "))
                    }
                }
                Button(modifier = Modifier.testTag("artwork-preview-close"), onClick = viewModel::closeArtworkPreview) {
                    Text("关闭")
                }
            }
            preview.message?.let { Text(it, Modifier.testTag("artwork-preview-message")) }
            if (preview.payload == null && !preview.loading) {
                Button(
                    modifier = Modifier.testTag("artwork-preview-retry"),
                    onClick = viewModel::retryArtworkPreview,
                ) { Text("重试") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (preview.canSaveSong) {
                    Button(
                        modifier = Modifier.testTag("artwork-save-song"),
                        enabled = preview.payload != null && !preview.loading && !preview.saving,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        onClick = { viewModel.savePreviewArtwork(ArtworkSaveKind.SONG) },
                    ) { Text(if (preview.saving) "保存中…" else "保存歌曲封面") }
                }
                if (preview.canSaveAlbum) {
                    Button(
                        modifier = Modifier.testTag("artwork-save-album"),
                        enabled = preview.payload != null && !preview.loading && !preview.saving,
                        onClick = { viewModel.savePreviewArtwork(ArtworkSaveKind.ALBUM) },
                    ) { Text("保存专辑封面") }
                }
            }
            Box(
                Modifier.size(150.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    preview.loading -> Text("正在加载封面…")
                    bitmap != null -> Image(
                        bitmap = requireNotNull(bitmap),
                        contentDescription = preview.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> Text("封面暂不可用")
                }
            }

        }
    }
}

@Composable
private fun CollectionArtwork(viewModel: DesktopViewModel, url: String) {
    val bitmap by produceState<ImageBitmap?>(null, url) {
        value = if (url.isBlank()) null else {
            viewModel.loadArtwork(url)?.let { bytes -> runCatching { bytes.decodeToImageBitmap() }.getOrNull() }
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = requireNotNull(bitmap),
            contentDescription = "歌单或专辑封面",
            modifier = Modifier.size(76.dp).clip(RoundedCornerShape(10.dp)),
        )
    } else {
        Box(
            Modifier.size(76.dp).clip(RoundedCornerShape(10.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Text("封面") }
    }
}

@Composable
private fun CollectionImportRow(
    viewModel: DesktopViewModel,
    record: CollectionImport,
    tasks: List<DownloadTask>,
) {
    val taskMap = tasks.associateBy { it.id }
    val related = record.items.mapNotNull { it.downloadTaskId?.let(taskMap::get) }.distinctBy { it.id }
    val completed = related.count { it.status == DownloadTaskStatus.COMPLETED }
    val active = related.count { it.status in setOf(DownloadTaskStatus.RESOLVING, DownloadTaskStatus.DOWNLOADING) }
    val waiting = related.count { it.status in setOf(DownloadTaskStatus.QUEUED, DownloadTaskStatus.PAUSED) }
    val failed = related.count { it.status == DownloadTaskStatus.FAILED }
    val recoverable = record.items.count {
        it.status == CollectionItemStatus.PREPARING && it.songId != null && it.downloadTaskId == null
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(record.sourceName, style = MiuixTheme.textStyles.body1)
        Text("${if (record.sourceType == CollectionSourceType.PLAYLIST) "歌单" else "专辑"} · ${record.totalCount} 首")
        Text("已完成 $completed · 下载中 $active · 等待 $waiting · 失败 ${failed + record.failedCount} · 跳过 ${record.skippedCount}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recoverable > 0) {
                Button(onClick = { viewModel.retryUnqueuedCollectionItems(record.id) }) {
                    Text("恢复排队 $recoverable 首")
                }
            }
            if (active > 0) Button(onClick = { viewModel.pauseCollection(record.id) }) { Text("暂停整组") }
            if (waiting > 0) Button(onClick = { viewModel.resumeCollection(record.id) }) { Text("继续整组") }
            if (active + waiting > 0) Button(onClick = { viewModel.cancelCollection(record.id) }) { Text("取消整组") }
            Button(onClick = { viewModel.navigate(DesktopPage.DOWNLOADS) }) { Text("查看下载任务") }
        }
    }
}

private fun collectionItemStatusLabel(status: CollectionItemStatus): String = when (status) {
    CollectionItemStatus.RESOLVED -> "可下载"
    CollectionItemStatus.PREPARING -> "待恢复排队"
    CollectionItemStatus.QUEUED -> "等待中"
    CollectionItemStatus.FAILED -> "解析失败"
    CollectionItemStatus.MISSING -> "曲目信息缺失"
    CollectionItemStatus.UNAUTHORIZED -> "需要授权"
    CollectionItemStatus.SKIPPED -> "已跳过"
}

private fun folderGroupingLabel(grouping: FolderGrouping): String = when (grouping) {
    FolderGrouping.NONE -> "下载根目录"
    FolderGrouping.ARTIST_ALBUM -> "歌手 - 专辑"
    FolderGrouping.PLAYLIST -> "歌单目录"
}

@Composable
private fun QualityButton(label: String, value: String, current: String, onSelect: (String) -> Unit) {
    Button(
        colors = if (current == value) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
        onClick = { onSelect(value) },
    ) { Text(label) }
}

private enum class TaskFilter(val label: String) {
    ALL("全部"), ACTIVE("下载中"), WAITING("等待中"), COMPLETED("已完成"), FAILED("失败"),
}

@Composable
private fun DownloadsScreen(
    viewModel: DesktopViewModel,
    tasks: List<DownloadTask>,
    imports: List<CollectionImport>,
) {
    var filter by remember { mutableStateOf(TaskFilter.ALL) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    val visible = when (filter) {
        TaskFilter.ALL -> tasks
        TaskFilter.ACTIVE -> tasks.filter { it.status in setOf(DownloadTaskStatus.RESOLVING, DownloadTaskStatus.DOWNLOADING) }
        TaskFilter.WAITING -> tasks.filter { it.status in setOf(DownloadTaskStatus.QUEUED, DownloadTaskStatus.PAUSED) }
        TaskFilter.COMPLETED -> tasks.filter { it.status == DownloadTaskStatus.COMPLETED }
        TaskFilter.FAILED -> tasks.filter(DownloadTask::belongsInFailedFilter)
    }
    val retryableFailureCount = tasks.count(DownloadTask::isBulkRetryable)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskFilter.entries.forEach { item ->
                Button(
                    colors = if (filter == item) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                    onClick = { filter = item },
                ) { Text("${item.label} (${tasks.count { it.matches(item) }})") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (retryableFailureCount > 0) {
                Button(
                    modifier = Modifier.testTag("download-retry-all-failures"),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    onClick = viewModel::retryAllFailures,
                ) { Text("一键重试 ($retryableFailureCount)") }
            }
            if (tasks.any { it.status in setOf(DownloadTaskStatus.COMPLETED, DownloadTaskStatus.FAILED, DownloadTaskStatus.CANCELLED) }) {
                Button(onClick = { showClearHistoryDialog = true }) { Text("清理下载记录") }
            }
        }
    }
    HorizontalDivider()
    if (visible.isEmpty()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("这个列表暂时没有任务。")
        }
    } else {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visible, key = { it.id }) { task ->
                DownloadTaskRow(viewModel, task, imports)
                HorizontalDivider()
            }
        }
    }
    if (showClearHistoryDialog) {
        DialogWindow(
            onCloseRequest = { showClearHistoryDialog = false },
            title = "清理下载记录",
            resizable = false,
        ) {
            Column(
                Modifier.width(520.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MiuixTheme.colorScheme.background)
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("清理下载记录", style = MiuixTheme.textStyles.headline2)
                Text("只处理已完成、失败和已取消的任务。排队、暂停或正在处理的任务会保留。")
                Text("删除文件选项只会删除已完成任务对应的普通音频文件；无法确认安全的文件会保留记录。")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            showClearHistoryDialog = false
                            viewModel.clearDownloadHistory(deleteCompletedFiles = false)
                        },
                    ) { Text("仅清理记录，保留文件") }
                    Button(
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        onClick = {
                            showClearHistoryDialog = false
                            viewModel.clearDownloadHistory(deleteCompletedFiles = true)
                        },
                    ) { Text("清理记录并删除已完成文件") }
                    Button(onClick = { showClearHistoryDialog = false }) { Text("取消") }
                }
            }
        }
    }
    }
}

private fun DownloadTask.matches(filter: TaskFilter): Boolean = when (filter) {
    TaskFilter.ALL -> true
    TaskFilter.ACTIVE -> status in setOf(DownloadTaskStatus.RESOLVING, DownloadTaskStatus.DOWNLOADING)
    TaskFilter.WAITING -> status in setOf(DownloadTaskStatus.QUEUED, DownloadTaskStatus.PAUSED)
    TaskFilter.COMPLETED -> status == DownloadTaskStatus.COMPLETED
    TaskFilter.FAILED -> belongsInFailedFilter()
}

@Composable
private fun DownloadTaskRow(viewModel: DesktopViewModel, task: DownloadTask, imports: List<CollectionImport>) {
    val importNames = imports.filter { it.id in task.collectionImportIds }.map { it.sourceName }.distinct()
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(task.songName, style = MiuixTheme.textStyles.body1)
                Text(
                    "${task.artists} · ${task.album} · ${qualityLabel(task.actualQuality ?: task.requestedQuality)} · " +
                        "${statusLabel(task.status)}${automaticRetryLabel(task.automaticRetriesUsed)}" +
                        postProcessingLabel(task.postProcessingStatus),
                )
                if (importNames.isNotEmpty()) Text("来自：${importNames.joinToString("、")}", style = MiuixTheme.textStyles.body2)
                task.postProcessingMessage?.let { Text(it, style = MiuixTheme.textStyles.body2) }
            }
            Text(task.progress?.let { "${(it * 100).toInt()}%" } ?: formatBytes(task.bytesDownloaded))
            Text(formatSpeed(task.speedBytesPerSecond))
        }
        Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(8.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))) {
            val progress = when (task.status) {
                DownloadTaskStatus.COMPLETED -> 1f
                else -> task.progress ?: 0f
            }
            Box(
                Modifier.fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(MiuixTheme.colorScheme.primary),
            )
        }
        task.errorSummary?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MiuixTheme.textStyles.body2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (task.status) {
                DownloadTaskStatus.QUEUED, DownloadTaskStatus.RESOLVING, DownloadTaskStatus.DOWNLOADING ->
                    Button(modifier = Modifier.testTag("download-pause-${task.id}"), onClick = { viewModel.pause(task.id) }) { Text("暂停") }
                DownloadTaskStatus.PAUSED ->
                    Button(modifier = Modifier.testTag("download-resume-${task.id}"), onClick = { viewModel.resume(task.id) }, colors = ButtonDefaults.buttonColorsPrimary()) { Text("继续") }
                DownloadTaskStatus.FAILED, DownloadTaskStatus.CANCELLED ->
                    Button(modifier = Modifier.testTag("download-retry-${task.id}"), onClick = { viewModel.retry(task.id) }, colors = ButtonDefaults.buttonColorsPrimary()) { Text("重试") }
                DownloadTaskStatus.COMPLETED -> {
                    if (task.postProcessingStatus in setOf(PostProcessingStatus.FAILED, PostProcessingStatus.WARNING)) {
                        Button(onClick = { viewModel.retryPostProcessing(task.id) }) { Text("重试歌词 / 标签") }
                    }
                }
            }
            if (task.status !in setOf(DownloadTaskStatus.COMPLETED, DownloadTaskStatus.CANCELLED)) {
                Button(modifier = Modifier.testTag("download-cancel-${task.id}"), onClick = { viewModel.cancel(task.id) }) { Text("取消") }
            }
            Button(modifier = Modifier.testTag("download-open-location-${task.id}"), onClick = { viewModel.openLocation(task) }) { Text("打开所在位置") }
        }
    }
}

@Composable
internal fun SettingsScreen(
    viewModel: DesktopViewModel,
    settings: DesktopSettings,
    diagnosticsEnabled: Boolean,
) {
    var directory by remember(settings.defaultDownloadDirectory) {
        mutableStateOf(settings.defaultDownloadDirectory)
    }
    var concurrency by remember(settings.maxConcurrentDownloads) {
        mutableStateOf(settings.maxConcurrentDownloads)
    }
    var quality by remember(settings.defaultQuality) { mutableStateOf(settings.defaultQuality) }
    var conflictPolicy by remember(settings.fileConflictPolicy) { mutableStateOf(settings.fileConflictPolicy) }
    var grouping by remember(settings.folderGrouping) { mutableStateOf(settings.folderGrouping) }
    var downloadLyrics by remember(settings.downloadLyrics) { mutableStateOf(settings.downloadLyrics) }
    var translatedLyrics by remember(settings.saveTranslatedLyrics) { mutableStateOf(settings.saveTranslatedLyrics) }
    var romanizedLyrics by remember(settings.saveRomanizedLyrics) { mutableStateOf(settings.saveRomanizedLyrics) }
    var wordLevelLyrics by remember(settings.saveWordLevelLyrics) { mutableStateOf(settings.saveWordLevelLyrics) }
    var embedLyrics by remember(settings.embedLyrics) { mutableStateOf(settings.embedLyrics) }
    var writeTags by remember(settings.writeAudioTags) { mutableStateOf(settings.writeAudioTags) }
    var embedCover by remember(settings.embedCoverArt) { mutableStateOf(settings.embedCoverArt) }
    var coverMode by remember(settings.coverDirectoryMode) { mutableStateOf(settings.coverDirectoryMode) }
    var customCoverDirectory by remember(settings.customCoverDirectory) { mutableStateOf(settings.customCoverDirectory) }
    var networkMode by remember(settings.network.mode) { mutableStateOf(settings.network.mode) }
    var proxyType by remember(settings.network.proxyType) { mutableStateOf(settings.network.proxyType) }
    var proxyHost by remember(settings.network.proxyHost) { mutableStateOf(settings.network.proxyHost) }
    var proxyPort by remember(settings.network.proxyPort) { mutableStateOf(settings.network.proxyPort.toString()) }
    val networkSettings = DesktopNetworkSettings(
        mode = networkMode,
        proxyType = proxyType,
        proxyHost = proxyHost,
        proxyPort = proxyPort.toIntOrNull()
            ?: if (networkMode == DesktopNetworkMode.CUSTOM_PROXY) 0 else 7890,
    )
    val networkValidationError = networkSettings.validationError()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("默认下载目录", style = MiuixTheme.textStyles.headline2)
        TextField(
            value = directory,
            onValueChange = { directory = it },
            label = "Windows 文件夹路径",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("最大并发数", style = MiuixTheme.textStyles.headline2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..4).forEach { value ->
                Button(
                    colors = if (concurrency == value) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                    onClick = { concurrency = value },
                ) { Text(value.toString()) }
            }
            Text("默认同时下载 2 首；调低并发不会中断已经开始的任务。")
        }
        Text("默认音质", style = MiuixTheme.textStyles.headline2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("standard" to "标准", "exhigh" to "极高", "lossless" to "无损", "hires" to "Hi-Res")
                .forEach { (value, label) ->
                    Button(
                        colors = if (quality == value) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                        onClick = { quality = value },
                    ) { Text(label) }
                }
        }
        Text("文件重名策略", style = MiuixTheme.textStyles.headline2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                colors = if (conflictPolicy == FileConflictPolicy.KEEP_BOTH) ButtonDefaults.buttonColorsPrimary()
                else ButtonDefaults.buttonColors(),
                onClick = { conflictPolicy = FileConflictPolicy.KEEP_BOTH },
            ) { Text("保留两份，自动加序号") }
            Button(
                colors = if (conflictPolicy == FileConflictPolicy.FAIL_IF_EXISTS) ButtonDefaults.buttonColorsPrimary()
                else ButtonDefaults.buttonColors(),
                onClick = { conflictPolicy = FileConflictPolicy.FAIL_IF_EXISTS },
            ) { Text("目标存在时失败") }
        }
        Text("下载文件夹分组", style = MiuixTheme.textStyles.headline2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FolderGrouping.entries.forEach { option ->
                Button(
                    colors = if (grouping == option) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                    onClick = { grouping = option },
                ) { Text(folderGroupingLabel(option)) }
            }
        }
        Text("歌词和音频标签", style = MiuixTheme.textStyles.headline2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingToggle("保存 LRC 歌词", downloadLyrics) { downloadLyrics = it }
            SettingToggle("保存翻译", translatedLyrics) { translatedLyrics = it }
            SettingToggle("保存罗马音", romanizedLyrics) { romanizedLyrics = it }
            SettingToggle("保存逐字歌词", wordLevelLyrics) { wordLevelLyrics = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingToggle("嵌入歌词", embedLyrics) { embedLyrics = it }
            SettingToggle("写入音频标签", writeTags) { writeTags = it }
            SettingToggle("嵌入封面", embedCover) { embedCover = it }
        }
        Text("标签写入仅支持能被识别的音频格式；处理失败会保留已下载音频并显示状态。")
        Text("下载诊断记录", style = MiuixTheme.textStyles.headline2)
        SettingToggle("启用本机脱敏诊断记录", diagnosticsEnabled) {
            viewModel.setDownloadDiagnosticsEnabled(it)
        }
        Text("默认关闭；记录最多保留 512 条，不保存 Cookie、响应正文或签名音源地址。应用重启后会自动关闭。")
        viewModel.diagnosticsFilePath?.let { Text("诊断文件：$it") }
        Text("封面保存位置", style = MiuixTheme.textStyles.headline2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.testTag("cover-mode-beside-media"),
                colors = if (coverMode == CoverDirectoryMode.BESIDE_MEDIA) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                onClick = { coverMode = CoverDirectoryMode.BESIDE_MEDIA },
            ) { Text("与歌曲放在一起") }
            Button(
                modifier = Modifier.testTag("cover-mode-custom"),
                colors = if (coverMode == CoverDirectoryMode.CUSTOM_DIRECTORY) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                onClick = { coverMode = CoverDirectoryMode.CUSTOM_DIRECTORY },
            ) { Text("自定义目录") }
        }
            TextField(
                value = customCoverDirectory,
                onValueChange = { customCoverDirectory = it },
                enabled = coverMode == CoverDirectoryMode.CUSTOM_DIRECTORY,
                label = "自定义封面目录",
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("settings-cover-directory"),
            )
        Text("网络连接方式", style = MiuixTheme.textStyles.headline2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesktopNetworkMode.entries.forEach { option ->
                Button(
                    modifier = Modifier.weight(1f),
                    colors = if (networkMode == option) ButtonDefaults.buttonColorsPrimary()
                    else ButtonDefaults.buttonColors(),
                    onClick = { networkMode = option },
                ) {
                    Text(
                        when (option) {
                            DesktopNetworkMode.DIRECT -> "直连（默认）"
                            DesktopNetworkMode.SYSTEM_DEFAULT -> "系统默认"
                            DesktopNetworkMode.CUSTOM_PROXY -> "自定义代理"
                        },
                    )
                }
            }
        }
        if (networkMode == DesktopNetworkMode.CUSTOM_PROXY) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DesktopProxyType.entries.forEach { option ->
                    Button(
                        colors = if (proxyType == option) ButtonDefaults.buttonColorsPrimary()
                        else ButtonDefaults.buttonColors(),
                        onClick = { proxyType = option },
                    ) { Text(if (option == DesktopProxyType.HTTP) "HTTP" else "SOCKS5") }
                }
            }
            TextField(
                value = proxyHost,
                onValueChange = { proxyHost = it },
                label = "代理主机名或 IP 地址",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = proxyPort,
                onValueChange = { proxyPort = it.filter { character -> character in '0'..'9' }.take(5) },
                label = "代理端口（1–65535）",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            networkValidationError?.let { Text("代理设置无效：$it") }
        }
        Text("网络设置保存后需重启应用生效；当前已运行的请求会继续使用原路由。")
        Button(onClick = viewModel::openDiagnosticsFile) { Text("打开诊断文件") }
        Button(
            modifier = Modifier.testTag("settings-save"),
            colors = ButtonDefaults.buttonColorsPrimary(),
            onClick = {
                if (networkValidationError == null) {
                    runCatching {
                        viewModel.saveSettings(
                            DesktopSettings(
                                defaultDownloadDirectory = directory.trim(),
                                maxConcurrentDownloads = concurrency,
                                defaultQuality = quality,
                                fileConflictPolicy = conflictPolicy,
                                folderGrouping = grouping,
                                downloadLyrics = downloadLyrics,
                                saveTranslatedLyrics = translatedLyrics,
                                saveRomanizedLyrics = romanizedLyrics,
                                saveWordLevelLyrics = wordLevelLyrics,
                                embedLyrics = embedLyrics,
                                writeAudioTags = writeTags,
                                embedCoverArt = embedCover,
                                coverDirectoryMode = coverMode,
                                customCoverDirectory = customCoverDirectory.trim(),
                                network = networkSettings,
                            ),
                        )
                    }
                }
            },
        ) { Text("保存设置") }
    }
}

@Composable
private fun SettingToggle(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Button(
        colors = if (enabled) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
        onClick = { onChange(!enabled) },
    ) { Text("${if (enabled) "✓ " else ""}$label") }
}

private fun statusLabel(status: DownloadTaskStatus): String = when (status) {
    DownloadTaskStatus.QUEUED -> "等待中"
    DownloadTaskStatus.RESOLVING -> "解析音源"
    DownloadTaskStatus.DOWNLOADING -> "下载中"
    DownloadTaskStatus.PAUSED -> "已暂停"
    DownloadTaskStatus.COMPLETED -> "已完成"
    DownloadTaskStatus.FAILED -> "失败"
    DownloadTaskStatus.CANCELLED -> "已取消"
}

private fun automaticRetryLabel(retriesUsed: Int): String =
    if (retriesUsed > 0) " · 自动重试 $retriesUsed/$MAX_AUTOMATIC_DOWNLOAD_RETRIES" else ""

private fun postProcessingLabel(status: PostProcessingStatus): String = when (status) {
    PostProcessingStatus.NOT_REQUESTED -> ""
    PostProcessingStatus.PENDING -> " · 附加信息等待处理"
    PostProcessingStatus.PROCESSING -> " · 歌词 / 标签处理中"
    PostProcessingStatus.COMPLETED -> " · 附加信息完成"
    PostProcessingStatus.WARNING -> " · 附加信息有警告"
    PostProcessingStatus.FAILED -> " · 附加信息处理失败"
}

private fun qualityLabel(quality: String): String = when (quality) {
    "standard" -> "标准音质"
    "exhigh" -> "极高音质"
    "lossless" -> "无损音质"
    "hires" -> "Hi-Res"
    else -> quality
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun formatSpeed(bytesPerSecond: Long): String = if (bytesPerSecond <= 0L) "—" else
    "${formatBytes(bytesPerSecond)}/s"

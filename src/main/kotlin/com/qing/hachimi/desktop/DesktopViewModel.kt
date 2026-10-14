package com.qing.hachimi.desktop

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

enum class DesktopPage { DISCOVERY, SEARCH, COLLECTIONS, DOWNLOADS, LOCAL_MUSIC, NOW_PLAYING, SETTINGS }
enum class DiscoveryDetail { HOME, DAILY_RECOMMENDATIONS, USER_PLAYLIST }
enum class ArtworkPreviewKind { SONG, ALBUM }
enum class ArtworkSaveKind { SONG, ALBUM }

data class ArtworkPreviewState(
    val title: String,
    val artist: String,
    val albumName: String,
    val kind: ArtworkPreviewKind,
    val payload: ArtworkPayload? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val message: String? = null,
    val canSaveSong: Boolean = false,
    val canSaveAlbum: Boolean = false,
)

enum class DesktopQrLoginStatus { IDLE, CREATING, WAITING, SCANNED, VERIFYING, SIGNED_IN, EXPIRED, FAILED }

internal fun desktopAppDataDirectory(): Path = System.getenv("APPDATA")
    ?.takeIf(String::isNotBlank)
    ?.let(Path::of)
    ?.resolve(DesktopProductIdentity.DATA_DIRECTORY)
    ?: Path.of(System.getProperty("user.home"), ".config", DesktopProductIdentity.DATA_DIRECTORY)

data class DesktopUiState(
    val page: DesktopPage = DesktopPage.SEARCH,
    val query: String = "",
    val rememberLogin: Boolean = false,
    val qrLoginStatus: DesktopQrLoginStatus = DesktopQrLoginStatus.IDLE,
    val qrCode: QrCodeData? = null,
    val session: SessionState = SessionState(),
    val searchLoading: Boolean = false,
    val searchResults: List<SongHit> = emptyList(),
    val selectedSongIds: Set<Long> = emptySet(),
    val discoveryLoading: Boolean = false,
    val dailyRecommendationsLoading: Boolean = false,
    val discoveryMessage: String = "登录后可查看你的歌单和每日推荐。",
    val discoveryDetail: DiscoveryDetail = DiscoveryDetail.HOME,
    val myPlaylists: List<UserPlaylistSummary> = emptyList(),
    val dailyRecommendations: List<SongHit> = emptyList(),
    val selectedDailyRecommendationIds: Set<Long> = emptySet(),
    val activePlaylistId: Long? = null,
    val activePlaylistLoading: Boolean = false,
    val activePlaylist: CollectionResolveResult? = null,
    val selectedUserPlaylistIndices: Set<Int> = emptySet(),
    val requestedQuality: String = "exhigh",
    val searchMessage: String = "搜索歌曲后可加入下载队列。",
    val actionMessage: String? = null,
    val collectionLink: String = "",
    val collectionLoading: Boolean = false,
    val collectionPreview: CollectionResolveResult? = null,
    val selectedCollectionIndices: Set<Int> = emptySet(),
    val collectionMessage: String = "粘贴网易云歌单或专辑链接后，可先检查曲目再确认加入队列。",
    val collectionDownloadDirectory: String = "",
    val downloadDiagnosticsEnabled: Boolean = false,
    val artworkPreview: ArtworkPreviewState? = null,
)

class DesktopViewModel(
    private val service: DesktopGateway,
    val taskManager: DownloadTaskManager,
    val sessionManager: SessionManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val collectionStore: CollectionImportStore? = null,
    private val qrLoginGateway: DesktopQrLoginGateway? = null,
    private val qrPollIntervalMillis: Long = 2_000L,
    private val diagnosticsStore: DownloadDiagnosticsStore? = null,
    private val artworkFileStore: ArtworkFileStore = ArtworkFileStore(),
) {
    private val logger = Logger.getLogger(DesktopViewModel::class.java.name)
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val mutableUi = MutableStateFlow(
        DesktopUiState(requestedQuality = taskManager.settings.value.defaultQuality),
    )
    private var searchJob: Job? = null
    private var discoveryJob: Job? = null
    private var playlistJob: Job? = null
    private var qrLoginJob: Job? = null
    private var artworkJob: Job? = null
    private var artworkPreviewUrl: String? = null
    private var artworkTarget: ArtworkSaveTarget? = null
    private var artworkPreviewSequence = 0L
    private val loadedCollectionImports = runCatching { collectionStore?.load().orEmpty() }
        .getOrDefault(emptyList())
    private val mutableCollectionImports = MutableStateFlow(reconcileCollectionImports(loadedCollectionImports))

    init {
        if (mutableCollectionImports.value != loadedCollectionImports) {
            scope.launch { runCatching { persistCollectionImports(mutableCollectionImports.value) } }
        }
        scope.launch {
            sessionManager.state.collect { session ->
                mutableUi.update { it.copy(session = session) }
            }
        }
        scope.launch { sessionManager.restore() }
    }

    val ui: StateFlow<DesktopUiState> = mutableUi.asStateFlow()
    val tasks: StateFlow<List<DownloadTask>> = taskManager.tasks
    val settings: StateFlow<DesktopSettings> = taskManager.settings
    val persistenceWarning: StateFlow<String?> = taskManager.persistenceWarning
    val collectionImports: StateFlow<List<CollectionImport>> = mutableCollectionImports.asStateFlow()
    val diagnosticsFilePath: String? get() = diagnosticsStore?.filePath()?.toString()

    fun navigate(page: DesktopPage) = mutableUi.update { it.copy(page = page) }
    fun updateQuery(value: String) = mutableUi.update { it.copy(query = value) }
    fun toggleRememberLogin() = mutableUi.update { it.copy(rememberLogin = !it.rememberLogin) }

    fun setDownloadDiagnosticsEnabled(enabled: Boolean) {
        diagnosticsStore?.setEnabled(enabled)
        mutableUi.update {
            it.copy(
                downloadDiagnosticsEnabled = diagnosticsStore?.enabled == true,
                actionMessage = if (diagnosticsStore != null) {
                    if (enabled) "已启用本机脱敏下载诊断记录。" else "已关闭下载诊断记录。"
                } else "此构建未提供下载诊断存储。",
            )
        }
    }

    fun openDiagnosticsFile() {
        val file = diagnosticsStore?.filePath()
        runCatching {
            if (file == null || !Files.isRegularFile(file)) {
                throw IllegalStateException("尚无诊断记录；启用诊断并运行一次任务后会生成文件。")
            }
            if (!Desktop.isDesktopSupported()) throw IllegalStateException("系统文件管理器不可用")
            Desktop.getDesktop().open(file.toFile())
        }.onFailure { error ->
            mutableUi.update { it.copy(actionMessage = error.message ?: "无法打开本机诊断记录。") }
        }
    }

    fun startQrLogin() {
        val gateway = qrLoginGateway
        if (gateway == null) {
            mutableUi.update { it.copy(qrLoginStatus = DesktopQrLoginStatus.FAILED, actionMessage = "扫码登录暂时不可用。") }
            return
        }
        qrLoginJob?.cancel()
        val remember = ui.value.rememberLogin
        mutableUi.update {
            it.copy(qrLoginStatus = DesktopQrLoginStatus.CREATING, qrCode = null, actionMessage = null)
        }
        qrLoginJob = scope.launch {
            try {
                val ticket = withContext(ioDispatcher) { gateway.createTicket() }
                val image = withContext(Dispatchers.Default) {
                    DesktopQrCodeEncoder.encode(gateway.loginUrl(ticket))
                }
                mutableUi.update { it.copy(qrCode = image, qrLoginStatus = DesktopQrLoginStatus.WAITING) }
                while (true) {
                    kotlinx.coroutines.delay(qrPollIntervalMillis.coerceAtLeast(1L))
                    when (val result = withContext(ioDispatcher) { gateway.poll(ticket) }) {
                        QrLoginPoll.WAITING -> mutableUi.update {
                            it.copy(qrLoginStatus = DesktopQrLoginStatus.WAITING)
                        }
                        QrLoginPoll.SCANNED -> mutableUi.update {
                            it.copy(qrLoginStatus = DesktopQrLoginStatus.SCANNED)
                        }
                        QrLoginPoll.EXPIRED -> {
                            mutableUi.update {
                                it.copy(
                                    qrCode = null,
                                    qrLoginStatus = DesktopQrLoginStatus.EXPIRED,
                                    actionMessage = "二维码已过期，请重新获取。",
                                )
                            }
                            return@launch
                        }
                        QrLoginPoll.FAILED -> {
                            mutableUi.update {
                                it.copy(
                                    qrCode = null,
                                    qrLoginStatus = DesktopQrLoginStatus.FAILED,
                                    actionMessage = "扫码登录暂时失败，请刷新二维码后重试。",
                                )
                            }
                            return@launch
                        }
                        is QrLoginPoll.Authenticated -> {
                            mutableUi.update { it.copy(qrLoginStatus = DesktopQrLoginStatus.VERIFYING) }
                            val login = withContext(ioDispatcher) {
                                sessionManager.login(result.musicU, remember = remember)
                            }
                            mutableUi.update {
                                it.copy(
                                    qrCode = null,
                                    qrLoginStatus = if (login.accepted) DesktopQrLoginStatus.SIGNED_IN
                                    else DesktopQrLoginStatus.FAILED,
                                    actionMessage = login.message ?: if (login.accepted) "登录成功。"
                                    else "登录校验未通过，请刷新二维码重试。",
                                )
                            }
                            return@launch
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUi.update {
                    it.copy(
                        qrCode = null,
                        qrLoginStatus = DesktopQrLoginStatus.FAILED,
                        actionMessage = "扫码登录暂时失败，请检查网络后刷新二维码。",
                    )
                }
            }
        }
    }

    fun cancelQrLogin() {
        qrLoginJob?.cancel()
        qrLoginJob = null
        mutableUi.update { it.copy(qrCode = null, qrLoginStatus = DesktopQrLoginStatus.IDLE) }
    }

    fun logout() {
        cancelQrLogin()
        scope.launch {
            taskManager.pauseForSessionChange()
            val removed = sessionManager.logout()
            mutableUi.update {
                it.copy(
                    qrLoginStatus = DesktopQrLoginStatus.IDLE,
                    actionMessage = if (removed) "已退出登录，保存的 Windows 凭据已清除。"
                    else "进程登录状态已清除，但 Windows 未能删除保存的凭据。",
                )
            }
        }
    }
    fun setQuality(value: String) {
        if (value in SUPPORTED_QUALITIES) mutableUi.update { it.copy(requestedQuality = value) }
    }
    fun toggleSelection(songId: Long) = mutableUi.update { state ->
        val next = if (songId in state.selectedSongIds) state.selectedSongIds - songId
        else state.selectedSongIds + songId
        state.copy(selectedSongIds = next)
    }
    fun clearSelection() = mutableUi.update { it.copy(selectedSongIds = emptySet()) }

    fun loadDiscovery() {
        discoveryJob?.cancel()
        playlistJob?.cancel()
        discoveryJob = scope.launch {
            val token = discoveryCredentialOrShowLogin() ?: return@launch
            mutableUi.update {
                it.copy(
                    discoveryLoading = true,
                    dailyRecommendationsLoading = true,
                    discoveryMessage = "正在读取个人歌单和每日推荐…",
                    discoveryDetail = DiscoveryDetail.HOME,
                    activePlaylistLoading = false,
                    activePlaylistId = null,
                    activePlaylist = null,
                    selectedUserPlaylistIndices = emptySet(),
                )
            }
            val (playlists, daily) = coroutineScope {
                val playlistResult = async(ioDispatcher) {
                    safeDiscoveryRequest { service.loadMyPlaylists(token) }
                }
                val dailyResult = async(ioDispatcher) {
                    safeDiscoveryRequest { service.loadDailyRecommendations(token) }
                }
                playlistResult.await() to dailyResult.await()
            }
            val expired = playlists.exceptionOrNull() is SessionExpiredException ||
                daily.exceptionOrNull() is SessionExpiredException
            if (expired) sessionManager.markExpired()
            mutableUi.update { state ->
                val playlistRows = playlists.getOrNull()
                val dailyRows = daily.getOrNull()
                val failures = listOfNotNull(playlists.exceptionOrNull(), daily.exceptionOrNull())
                state.copy(
                    discoveryLoading = false,
                    dailyRecommendationsLoading = false,
                    myPlaylists = playlistRows ?: state.myPlaylists,
                    dailyRecommendations = dailyRows ?: state.dailyRecommendations,
                    selectedDailyRecommendationIds = dailyRows?.let { rows ->
                        state.selectedDailyRecommendationIds.intersect(rows.mapTo(mutableSetOf()) { it.id })
                    } ?: state.selectedDailyRecommendationIds,
                    discoveryMessage = when {
                        expired -> RELOGIN_DISCOVERY_MESSAGE
                        failures.isEmpty() -> "已加载 ${playlistRows.orEmpty().size} 个歌单和 ${dailyRows.orEmpty().size} 首每日推荐。"
                        else -> failures.firstNotNullOfOrNull(::safeDiscoveryErrorMessage)
                            ?: "发现内容加载失败，请检查网络后重试。"
                    },
                )
            }
        }
    }

    fun loadDailyRecommendations() {
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            val token = discoveryCredentialOrShowLogin() ?: return@launch
            mutableUi.update {
                it.copy(
                    discoveryLoading = true,
                    dailyRecommendationsLoading = true,
                    discoveryMessage = "正在读取每日推荐…",
                    discoveryDetail = DiscoveryDetail.DAILY_RECOMMENDATIONS,
                )
            }
            val result = safeDiscoveryRequest { service.loadDailyRecommendations(token) }
            val expired = result.exceptionOrNull() is SessionExpiredException
            if (expired) sessionManager.markExpired()
            mutableUi.update { state ->
                val songs = result.getOrNull()
                state.copy(
                    discoveryLoading = false,
                    dailyRecommendationsLoading = false,
                    dailyRecommendations = songs ?: state.dailyRecommendations,
                    selectedDailyRecommendationIds = songs?.let { rows ->
                        state.selectedDailyRecommendationIds.intersect(rows.mapTo(mutableSetOf()) { it.id })
                    } ?: state.selectedDailyRecommendationIds,
                    discoveryMessage = when {
                        expired -> RELOGIN_DISCOVERY_MESSAGE
                        result.isSuccess -> "已加载 ${songs.orEmpty().size} 首每日推荐。"
                        else -> safeDiscoveryErrorMessage(result.exceptionOrNull())
                            ?: "每日推荐加载失败，请检查网络后重试。"
                    },
                )
            }
        }
    }

    fun openUserPlaylist(playlistId: Long) {
        discoveryJob?.cancel()
        playlistJob?.cancel()
        playlistJob = scope.launch {
            val token = discoveryCredentialOrShowLogin() ?: return@launch
            val reference = runCatching { CollectionReference(CollectionSourceType.PLAYLIST, playlistId) }
                .getOrElse {
                    mutableUi.update { state -> state.copy(discoveryMessage = "歌单编号无效。") }
                    return@launch
                }
            mutableUi.update {
                it.copy(
                discoveryLoading = false,
                dailyRecommendationsLoading = false,
                    activePlaylistId = playlistId,
                    discoveryDetail = DiscoveryDetail.USER_PLAYLIST,
                    activePlaylistLoading = true,
                    activePlaylist = null,
                    selectedUserPlaylistIndices = emptySet(),
                    discoveryMessage = "正在读取歌单曲目…",
                )
            }
            try {
                val result = withContext(ioDispatcher) {
                    service.resolveCollection(reference, token)
                }
                mutableUi.update {
                    it.copy(
                        activePlaylistLoading = false,
                        activePlaylist = result,
                        selectedUserPlaylistIndices = emptySet(),
                        discoveryMessage = "读取到 ${result.items.size} 首曲目；可用 ${result.resolvedCount} 首。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SessionExpiredException) {
                sessionManager.markExpired()
                mutableUi.update {
                    it.copy(activePlaylistLoading = false, discoveryMessage = RELOGIN_DISCOVERY_MESSAGE)
                }
            } catch (_: CollectionApiException) {
                mutableUi.update {
                    it.copy(activePlaylistLoading = false, discoveryMessage = "歌单暂时无法读取，请检查授权或稍后重试。")
                }
            } catch (_: Exception) {
                mutableUi.update {
                    it.copy(activePlaylistLoading = false, discoveryMessage = "读取歌单失败，请检查网络后重试。")
                }
            }
        }
    }

    fun closeUserPlaylist() {
        closeDiscoveryDetail()
    }

    fun closeDiscoveryDetail() {
        playlistJob?.cancel()
        playlistJob = null
        mutableUi.update {
            it.copy(
                discoveryDetail = DiscoveryDetail.HOME,
                activePlaylistId = null,
                activePlaylistLoading = false,
                activePlaylist = null,
                selectedUserPlaylistIndices = emptySet(),
            )
        }
    }

    fun toggleDailyRecommendationSelection(songId: Long) = mutableUi.update { state ->
        if (state.dailyRecommendations.none { it.id == songId }) return@update state
        val next = if (songId in state.selectedDailyRecommendationIds) {
            state.selectedDailyRecommendationIds - songId
        } else {
            state.selectedDailyRecommendationIds + songId
        }
        state.copy(selectedDailyRecommendationIds = next)
    }

    fun toggleUserPlaylistSelection(originalIndex: Int) = mutableUi.update { state ->
        val item = state.activePlaylist?.items?.firstOrNull { it.originalIndex == originalIndex }
        if (item?.status != CollectionItemStatus.RESOLVED || item.song == null) return@update state
        val next = if (originalIndex in state.selectedUserPlaylistIndices) {
            state.selectedUserPlaylistIndices - originalIndex
        } else {
            state.selectedUserPlaylistIndices + originalIndex
        }
        state.copy(selectedUserPlaylistIndices = next)
    }

    fun selectAllDailyRecommendations() = mutableUi.update { state ->
        state.copy(selectedDailyRecommendationIds = state.dailyRecommendations.mapTo(mutableSetOf()) { it.id })
    }

    fun selectAllUserPlaylistTracks() = mutableUi.update { state ->
        state.copy(
            selectedUserPlaylistIndices = state.activePlaylist?.items
                ?.filter { row -> row.status == CollectionItemStatus.RESOLVED && row.song != null }
                ?.mapTo(mutableSetOf()) { row -> row.originalIndex }
                .orEmpty(),
        )
    }

    fun clearDiscoverySelection() = mutableUi.update {
        it.copy(selectedDailyRecommendationIds = emptySet(), selectedUserPlaylistIndices = emptySet())
    }

    fun enqueueSelectedDiscoverySongs() {
        val snapshot = ui.value
        val playlist = snapshot.activePlaylist
        val playlistSelection = snapshot.selectedUserPlaylistIndices
        val dailySelection = snapshot.selectedDailyRecommendationIds
        val songs = if (playlist != null) {
            playlist.items.filter { row ->
                row.originalIndex in playlistSelection &&
                    row.status == CollectionItemStatus.RESOLVED && row.song != null
            }.mapNotNull { it.song }
        } else {
            snapshot.dailyRecommendations.filter { it.id in dailySelection }
        }
        if (songs.isEmpty()) {
            mutableUi.update { it.copy(actionMessage = "请先选择至少一首可用歌曲。") }
            return
        }
        val settingsSnapshot = settings.value
        val root = Path.of(settingsSnapshot.defaultDownloadDirectory).toAbsolutePath().normalize()
        val sourceName = playlist?.descriptor?.name?.takeIf(String::isNotBlank) ?: "每日推荐"
        scope.launch {
            try {
                val ids = withContext(ioDispatcher) {
                    taskManager.addSongs(
                        songs = songs,
                        requestedQuality = settingsSnapshot.defaultQuality,
                        downloadDirectory = settingsSnapshot.defaultDownloadDirectory,
                        destinationDirectory = { song ->
                            FileNamePolicy.groupedDirectory(
                                root,
                                settingsSnapshot.folderGrouping,
                                sourceName,
                                song,
                            )
                        },
                    )
                }
                mutableUi.update { state ->
                    state.copy(
                        actionMessage = "已将 ${ids.size} 首歌曲加入下载队列。",
                        selectedDailyRecommendationIds = if (playlist == null &&
                            state.selectedDailyRecommendationIds == dailySelection
                        ) emptySet() else state.selectedDailyRecommendationIds,
                        selectedUserPlaylistIndices = if (playlist != null &&
                            state.activePlaylist?.descriptor?.reference == playlist.descriptor.reference &&
                            state.selectedUserPlaylistIndices == playlistSelection
                        ) emptySet() else state.selectedUserPlaylistIndices,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUi.update { it.copy(actionMessage = "无法创建下载任务，请检查下载目录和设置。") }
            }
        }
    }

    private suspend fun discoveryCredentialOrShowLogin(): String? {
        sessionManager.awaitReady()
        return sessionManager.credentialForRequest() ?: run {
            mutableUi.update {
                it.copy(
                    discoveryLoading = false,
                    dailyRecommendationsLoading = false,
                    activePlaylistLoading = false,
                    discoveryMessage = RELOGIN_DISCOVERY_MESSAGE,
                )
            }
            null
        }
    }

    private suspend fun <T> safeDiscoveryRequest(request: suspend () -> T): Result<T> = try {
        Result.success(request())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun safeDiscoveryErrorMessage(error: Throwable?): String? = when (error) {
        is DiscoveryApiException -> error.message
        is SessionExpiredException -> RELOGIN_DISCOVERY_MESSAGE
        else -> null
    }

    fun updateCollectionLink(value: String) = mutableUi.update { it.copy(collectionLink = value) }
    fun updateCollectionDownloadDirectory(value: String) =
        mutableUi.update { it.copy(collectionDownloadDirectory = value) }

    suspend fun loadArtwork(url: String): ByteArray? = withContext(ioDispatcher) {
        runCatching { service.fetchArtwork(url) }.getOrNull()
    }

    fun previewSongArtwork(song: SongHit, collectionName: String? = null) {
        val safeSong = song.copy(coverUrl = "")
        loadArtworkPreview(
            title = song.name,
            artist = song.artists,
            albumName = song.album,
            kind = ArtworkPreviewKind.SONG,
            url = song.coverUrl,
            song = safeSong,
            collectionName = collectionName,
        )
    }

    fun previewArtwork(title: String, url: String) {
        loadArtworkPreview(
            title = title,
            artist = "",
            albumName = "",
            kind = ArtworkPreviewKind.ALBUM,
            url = url,
            song = null,
            collectionName = null,
        )
    }

    fun previewAlbumArtwork(albumName: String, artist: String, url: String, song: SongHit? = null, collectionName: String? = null) {
        loadArtworkPreview(
            title = albumName,
            artist = artist,
            albumName = albumName,
            kind = ArtworkPreviewKind.ALBUM,
            url = url,
            song = song?.copy(coverUrl = ""),
            collectionName = collectionName,
        )
    }

    fun retryArtworkPreview() {
        val current = ui.value.artworkPreview ?: return
        val url = artworkPreviewUrl ?: return
        val target = artworkTarget ?: return
        loadArtworkPreview(
            title = current.title,
            artist = current.artist,
            albumName = current.albumName,
            kind = current.kind,
            url = url,
            song = target.song,
            collectionName = target.collectionName,
        )
    }

    fun closeArtworkPreview() {
        artworkJob?.cancel()
        artworkJob = null
        artworkPreviewSequence++
        artworkPreviewUrl = null
        artworkTarget = null
        mutableUi.update { it.copy(artworkPreview = null) }
    }

    fun savePreviewArtwork(kind: ArtworkSaveKind) {
        val preview = ui.value.artworkPreview ?: return
        val payload = preview.payload ?: return
        val target = artworkTarget ?: return
        if ((kind == ArtworkSaveKind.SONG && target.song == null) ||
            (kind == ArtworkSaveKind.ALBUM && target.albumName.isBlank())
        ) return
        val settingsSnapshot = settings.value
        val saveSequence = artworkPreviewSequence
        mutableUi.update { state ->
            state.copy(artworkPreview = state.artworkPreview?.copy(saving = true, message = null))
        }
        scope.launch {
            try {
                val destination = withContext(ioDispatcher) {
                    when (kind) {
                        ArtworkSaveKind.SONG -> {
                            val song = requireNotNull(target.song)
                            val existingAudio = taskManager.tasks.value
                                .asSequence()
                                .filter { it.songId == song.id && it.status != DownloadTaskStatus.CANCELLED }
                                .maxByOrNull { it.updatedAtMillis }
                                ?.let { Path.of(it.filePath) }
                            ArtworkDestinationResolver.songCoverPath(
                                song,
                                existingAudio,
                                settingsSnapshot,
                                target.collectionName,
                                payload.extension,
                            )
                        }
                        ArtworkSaveKind.ALBUM -> {
                            val existingAudio = target.song?.let { song ->
                                taskManager.tasks.value
                                    .asSequence()
                                    .filter { it.songId == song.id && it.status != DownloadTaskStatus.CANCELLED }
                                    .maxByOrNull { it.updatedAtMillis }
                                    ?.let { Path.of(it.filePath) }
                            }
                            ArtworkDestinationResolver.albumCoverPath(
                                target.albumName,
                                target.artist,
                                settingsSnapshot,
                                target.collectionName,
                                payload.extension,
                                existingAudio,
                            )
                        }
                    }
                }
                val saved = withContext(ioDispatcher) { artworkFileStore.save(payload, destination) }
                if (saveSequence != artworkPreviewSequence) return@launch
                mutableUi.update { state ->
                    state.copy(artworkPreview = state.artworkPreview?.copy(saving = false, message = "封面已保存：$saved"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (saveSequence != artworkPreviewSequence) return@launch
                mutableUi.update { state ->
                    state.copy(artworkPreview = state.artworkPreview?.copy(saving = false, message = "封面保存失败，请检查目录权限和磁盘空间。"))
                }
            }
        }
    }

    private fun loadArtworkPreview(
        title: String,
        artist: String,
        albumName: String,
        kind: ArtworkPreviewKind,
        url: String,
        song: SongHit?,
        collectionName: String?,
    ) {
        artworkJob?.cancel()
        val requestSequence = ++artworkPreviewSequence
        artworkPreviewUrl = url.takeIf(String::isNotBlank)
        artworkTarget = ArtworkSaveTarget(song, albumName, artist, collectionName)
        mutableUi.update {
            it.copy(
                artworkPreview = ArtworkPreviewState(
                    title = title.ifBlank { "封面预览" },
                    artist = artist,
                    albumName = albumName,
                    kind = kind,
                    loading = true,
                    canSaveSong = song != null,
                    canSaveAlbum = albumName.isNotBlank(),
                ),
            )
        }
        artworkJob = scope.launch {
            val (payload, fetchFailureMessage) = if (url.isBlank()) {
                null to "封面地址为空，无法预览。"
            } else withContext(ioDispatcher) {
                try {
                    service.fetchArtworkForPreview(url) to null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: ArtworkFetchException) {
                    null to error.safeMessage
                } catch (_: Exception) {
                    null to "封面请求失败，请检查网络设置。"
                }
            }
            if (requestSequence != artworkPreviewSequence) return@launch
            mutableUi.update { state ->
                val current = state.artworkPreview ?: return@update state
                state.copy(
                    artworkPreview = current.copy(
                        payload = payload,
                        loading = false,
                        message = if (payload == null) {
                            fetchFailureMessage ?: "封面暂时无法读取，请检查网络后重试。"
                        } else null,
                    ),
                )
            }
        }
    }

    private data class ArtworkSaveTarget(
        val song: SongHit?,
        val albumName: String,
        val artist: String,
        val collectionName: String?,
    )

    fun loadCollection() {
        val link = ui.value.collectionLink.trim()
        val reference = NeteaseCollectionLinkParser.parse(link)
        if (reference == null) {
            mutableUi.update { it.copy(collectionMessage = "链接无效。请使用 music.163.com 的歌单或专辑链接。") }
            return
        }
        scope.launch {
            mutableUi.update { it.copy(collectionLoading = true, collectionMessage = "正在读取曲目列表…") }
            try {
                val result = withContext(ioDispatcher) {
                    service.resolveCollection(reference, sessionManager.credentialForRequest().orEmpty())
                }
                mutableUi.update {
                    it.copy(
                        collectionLoading = false,
                        collectionPreview = result,
                        selectedCollectionIndices = emptySet(),
                        collectionMessage = buildString {
                            append("读取到 ${result.items.size} 首；已解析 ${result.resolvedCount} 首，")
                            append("失败 ${result.failedCount} 首，跳过 ${result.skippedCount} 首。")
                            if (result.countMismatch) append("接口声明曲目数与实际列表不一致。")
                        },
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: SessionExpiredException) {
                sessionManager.markExpired()
                mutableUi.update {
                    it.copy(collectionLoading = false, collectionMessage = "登录状态已过期。请重新登录后再读取此内容。")
                }
            } catch (_: CollectionApiException) {
                mutableUi.update {
                    it.copy(collectionLoading = false, collectionMessage = "歌单或专辑暂时无法读取；请检查授权状态或稍后重试。")
                }
            } catch (_: Exception) {
                mutableUi.update { it.copy(collectionLoading = false, collectionMessage = "读取失败。请检查网络后重试。") }
            }
        }
    }

    fun toggleCollectionSelection(originalIndex: Int) = mutableUi.update { state ->
        val item = state.collectionPreview?.items?.firstOrNull { it.originalIndex == originalIndex }
        if (item?.status != CollectionItemStatus.RESOLVED || item.song == null) return@update state
        val next = if (originalIndex in state.selectedCollectionIndices) {
            state.selectedCollectionIndices - originalIndex
        } else state.selectedCollectionIndices + originalIndex
        state.copy(selectedCollectionIndices = next)
    }

    fun selectAllResolvedCollectionItems() = mutableUi.update { state ->
        state.copy(
            selectedCollectionIndices = state.collectionPreview?.items
                ?.filter { it.status == CollectionItemStatus.RESOLVED }
                ?.mapTo(mutableSetOf()) { it.originalIndex }
                .orEmpty(),
        )
    }

    fun clearCollectionSelection() = mutableUi.update { it.copy(selectedCollectionIndices = emptySet()) }

    fun closeCollectionPreview() = mutableUi.update {
        it.copy(collectionPreview = null, selectedCollectionIndices = emptySet())
    }

    fun addSelectedCollectionItems() {
        val state = ui.value
        val result = state.collectionPreview ?: return
        val selected = result.items.filter {
            it.originalIndex in state.selectedCollectionIndices &&
                it.status == CollectionItemStatus.RESOLVED && it.song != null
        }
        if (selected.isEmpty()) {
            mutableUi.update { it.copy(collectionMessage = "请先勾选至少一首可用歌曲。") }
            return
        }

        val importId = UUID.randomUUID().toString()
        val settingsSnapshot = settings.value
        val downloadRoot = state.collectionDownloadDirectory.trim()
            .ifBlank { settingsSnapshot.defaultDownloadDirectory }
        val importRecord = CollectionImport(
            id = importId,
            sourceType = result.descriptor.reference.type,
            sourceId = result.descriptor.reference.id,
            sourceName = result.descriptor.name,
            requestedQuality = state.requestedQuality,
            downloadDirectory = downloadRoot,
            createdAtMillis = System.currentTimeMillis(),
            folderGrouping = settingsSnapshot.folderGrouping,
            coverUrl = result.descriptor.coverUrl,
            items = result.items.map { item ->
                val status = when {
                    item.status != CollectionItemStatus.RESOLVED -> item.status
                    item.originalIndex in state.selectedCollectionIndices -> CollectionItemStatus.PREPARING
                    else -> CollectionItemStatus.SKIPPED
                }
                CollectionImportItem(
                    originalIndex = item.originalIndex,
                    songId = item.songId,
                    songName = item.song?.name ?: "未知曲目",
                    artists = item.song?.artists.orEmpty(),
                    album = item.song?.album.orEmpty(),
                    status = status,
                    message = item.message,
                )
            },
        )

        scope.launch {
            try {
                val initialImports = mutableCollectionImports.value + importRecord
                persistCollectionImports(initialImports)
                mutableCollectionImports.value = initialImports
                val songs = selected.map { item ->
                    requireNotNull(item.song).copy(trackNumber = item.originalIndex, trackTotal = result.items.size)
                }
                val root = Path.of(downloadRoot)
                val taskIds = withContext(ioDispatcher) {
                    taskManager.addSongs(
                        songs = songs,
                        requestedQuality = state.requestedQuality,
                        downloadDirectory = downloadRoot,
                        collectionImportId = importId,
                        destinationDirectory = { song ->
                            FileNamePolicy.groupedDirectory(
                                root,
                                settingsSnapshot.folderGrouping,
                                result.descriptor.name,
                                song,
                            )
                        },
                    )
                }
                val idsByIndex = selected.mapIndexed { index, item -> item.originalIndex to taskIds[index] }.toMap()
                val savedRecord = importRecord.copy(items = importRecord.items.map { item ->
                    if (idsByIndex[item.originalIndex] == null) item else item.copy(
                        status = CollectionItemStatus.QUEUED,
                        downloadTaskId = idsByIndex[item.originalIndex],
                    )
                })
                val updated = mutableCollectionImports.value.map { if (it.id == importId) savedRecord else it }
                mutableCollectionImports.value = updated
                persistCollectionImports(updated)
                mutableUi.update {
                    it.copy(
                        collectionPreview = null,
                        selectedCollectionIndices = emptySet(),
                        collectionMessage = "已排入 ${taskIds.size} 首；集合中 ${result.failedCount} 首失败、${result.skippedCount} 首跳过。",
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUi.update { it.copy(collectionMessage = "集合入队或记录保存未完成；已保留可恢复状态，请检查任务列表和保存目录。") }
            }
        }
    }

    fun pauseCollection(importId: String) = launchCollectionAction(importId) { taskId -> taskManager.pause(taskId) }
    fun resumeCollection(importId: String) = launchCollectionAction(importId) { taskId -> taskManager.resume(taskId) }
    fun cancelCollection(importId: String) = launchCollectionAction(importId) { taskId ->
        val task = taskManager.tasks.value.firstOrNull { it.id == taskId }
        if (task != null && task.collectionImportIds.size > 1) {
            taskManager.removeCollectionMembership(taskId, importId)
        } else taskManager.cancel(taskId)
    }

    fun retryUnqueuedCollectionItems(importId: String) {
        val record = mutableCollectionImports.value.firstOrNull { it.id == importId } ?: return
        val pending = record.items.filter {
            it.status == CollectionItemStatus.PREPARING && it.songId != null && it.downloadTaskId == null
        }
        if (pending.isEmpty()) return
        scope.launch {
            try {
                val songs = pending.map { item ->
                    SongHit(
                        id = requireNotNull(item.songId),
                        name = item.songName,
                        artists = item.artists,
                        album = item.album,
                        trackNumber = item.originalIndex,
                        trackTotal = record.totalCount,
                    )
                }
                val root = Path.of(record.downloadDirectory)
                val ids = withContext(ioDispatcher) {
                    taskManager.addSongs(
                        songs = songs,
                        requestedQuality = record.requestedQuality,
                        downloadDirectory = record.downloadDirectory,
                        collectionImportId = record.id,
                        destinationDirectory = { song ->
                            FileNamePolicy.groupedDirectory(root, record.folderGrouping, record.sourceName, song)
                        },
                    )
                }
                val idsByIndex = pending.mapIndexed { index, item -> item.originalIndex to ids[index] }.toMap()
                val current = mutableCollectionImports.value.firstOrNull { it.id == importId } ?: record
                val updatedRecord = current.copy(items = current.items.map { item ->
                    idsByIndex[item.originalIndex]?.let { taskId ->
                        item.copy(status = CollectionItemStatus.QUEUED, downloadTaskId = taskId, message = null)
                    } ?: item
                })
                val updated = mutableCollectionImports.value.map {
                    if (it.id == importId) updatedRecord else it
                }
                mutableCollectionImports.value = updated
                runCatching { persistCollectionImports(updated) }
                mutableUi.update { it.copy(collectionMessage = "已恢复 ${ids.size} 首歌曲的队列关联。") }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUi.update { it.copy(collectionMessage = "恢复入队失败；记录仍保留，可检查目录和任务存储后重试。") }
            }
        }
    }

    fun search() {
        val query = ui.value.query.trim()
        if (query.isEmpty()) {
            mutableUi.update { it.copy(searchMessage = "请输入歌曲名或歌手。") }
            return
        }
        searchJob?.cancel()
        val token = sessionManager.credentialForRequest().orEmpty()
        searchJob = scope.launch {
            mutableUi.update { it.copy(searchLoading = true, searchMessage = "正在搜索…") }
            try {
                val results = withContext(ioDispatcher) { service.searchSongs(query, token) }
                mutableUi.update {
                    it.copy(
                        searchLoading = false,
                        searchResults = results,
                        selectedSongIds = it.selectedSongIds.intersect(results.mapTo(mutableSetOf()) { song -> song.id }),
                        searchMessage = "找到 ${results.size} 首歌曲。",
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: SessionExpiredException) {
                sessionManager.markExpired()
                mutableUi.update { it.copy(searchLoading = false, searchMessage = "登录状态已过期，请重新登录。") }
            } catch (_: Exception) {
                mutableUi.update { it.copy(searchLoading = false, searchMessage = "搜索失败，请检查网络后重试。") }
            }
        }
    }

    fun addSong(song: SongHit) {
        addSongs(listOf(song))
    }

    fun addSelectedSongs() {
        val state = ui.value
        val selected = state.searchResults.filter { it.id in state.selectedSongIds }
        if (selected.isEmpty()) {
            mutableUi.update { it.copy(actionMessage = "请先选择至少一首歌曲。") }
            return
        }
        addSongs(selected)
    }

    private fun addSongs(songs: List<SongHit>) {
        val state = ui.value
        val currentSettings = settings.value
        scope.launch {
            try {
                val ids = taskManager.addSongs(
                    songs = songs,
                    requestedQuality = state.requestedQuality,
                    downloadDirectory = currentSettings.defaultDownloadDirectory,
                )
                mutableUi.update { it.copy(actionMessage = "已加入 ${ids.size} 项下载任务。") }
                if (songs.size > 1) clearSelection()
            } catch (_: Exception) {
                mutableUi.update { it.copy(actionMessage = "无法创建下载任务，请检查下载目录和设置。") }
            }
        }
    }

    fun pause(taskId: String) = launchTaskAction { taskManager.pause(taskId) }
    fun resume(taskId: String) = launchTaskAction {
        val resumed = taskManager.resume(taskId)
        if (!resumed) mutableUi.update { it.copy(actionMessage = "请先提供有效凭据，或确认该任务当前可继续。") }
    }
    fun cancel(taskId: String) = launchTaskAction { taskManager.cancel(taskId) }
    fun clearDownloadHistory(deleteCompletedFiles: Boolean) = launchTaskAction {
        val result = taskManager.clearHistory(deleteCompletedFiles)
        val message = when {
            !result.persistenceSucceeded -> "下载记录未能保存，已恢复记录列表；请检查磁盘空间或权限后重试。已按所选操作删除 ${result.deletedFiles} 个文件。"
            deleteCompletedFiles && result.deletionFailures > 0 ->
                "已清理 ${result.removedRecords} 条结束任务记录，删除 ${result.deletedFiles} 个已完成文件；${result.deletionFailures} 项无法安全删除并保留记录。运行中和等待任务未更改。"
            deleteCompletedFiles ->
                "已清理 ${result.removedRecords} 条结束任务记录，删除 ${result.deletedFiles} 个已完成文件。运行中和等待任务未更改。"
            else -> "已清理 ${result.removedRecords} 条结束任务记录，原文件已保留。运行中和等待任务未更改。"
        }
        mutableUi.update { it.copy(actionMessage = message) }
    }
    fun retry(taskId: String) = launchTaskAction {
        if (!taskManager.retry(taskId)) mutableUi.update { it.copy(actionMessage = "该任务当前无法重试。") }
    }
    fun retryAllFailures() = launchTaskAction {
        val candidates = taskManager.tasks.value.filter(DownloadTask::isBulkRetryable)
        var downloadRetries = 0
        var postProcessingRetries = 0
        candidates.forEach { task ->
            when {
                task.status == DownloadTaskStatus.FAILED && taskManager.retry(task.id) -> downloadRetries++
                task.isPostProcessingRetryable() && taskManager.retryPostProcessing(task.id) -> postProcessingRetries++
            }
        }
        val total = downloadRetries + postProcessingRetries
        val message = if (total == 0) {
            "当前没有可重试的失败任务。"
        } else {
            "已提交 $total 项重试：下载失败 $downloadRetries 项，歌词/封面/标签处理 $postProcessingRetries 项。任务按当前并发上限排队处理。"
        }
        mutableUi.update { it.copy(actionMessage = message) }
    }
    fun retryPostProcessing(taskId: String) = launchTaskAction {
        if (!taskManager.retryPostProcessing(taskId)) mutableUi.update {
            it.copy(actionMessage = "该任务当前没有可重试的歌词或标签处理。")
        }
    }

    fun saveSettings(settings: DesktopSettings) {
        scope.launch {
            try {
                taskManager.updateSettings(settings)
                mutableUi.update { it.copy(requestedQuality = settings.defaultQuality, actionMessage = "设置已保存。") }
            } catch (_: Exception) {
                mutableUi.update { it.copy(actionMessage = "设置无效，未能保存。") }
            }
        }
    }

    fun openLocation(task: DownloadTask) {
        runCatching {
            val file = Path.of(task.filePath).toAbsolutePath().normalize()
            val folder = if (Files.isDirectory(file)) file else file.parent
                ?: throw IllegalStateException("文件夹不可用")
            if (!Desktop.isDesktopSupported()) throw IllegalStateException("系统文件管理器不可用")
            val desktop = Desktop.getDesktop()
            if (Files.isRegularFile(file) && desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                desktop.browseFileDirectory(file.toFile())
            } else {
                Files.createDirectories(folder)
                desktop.open(folder.toFile())
            }
        }.onFailure {
            mutableUi.update { state -> state.copy(actionMessage = "无法打开文件所在位置。") }
        }
    }

    suspend fun shutdown() {
        try {
            taskManager.shutdown()
        } finally {
            try {
                val jobs = scope.coroutineContext[Job]?.children?.toList().orEmpty()
                jobs.forEach { it.cancel() }
                val allStopped = withTimeoutOrNull(SHUTDOWN_JOB_JOIN_TIMEOUT_MILLIS) {
                    jobs.joinAll()
                    true
                } ?: false
                if (!allStopped) {
                    val stillActive = jobs.count { it.isActive }
                    if (stillActive > 0) {
                        logger.warning("Shutdown timed out waiting for $stillActive view model job(s) to stop.")
                    }
                }
            } finally {
                scope.cancel()
            }
        }
    }

    private fun launchTaskAction(action: suspend () -> Unit) {
        scope.launch {
            runCatching { action() }
                .onFailure { mutableUi.update { it.copy(actionMessage = "任务操作失败，请稍后重试。") } }
        }
    }

    private suspend fun persistCollectionImports(imports: List<CollectionImport>) {
        val store = collectionStore ?: throw IllegalStateException("集合记录存储不可用")
        withContext(ioDispatcher) { store.save(imports) }
    }

    private fun reconcileCollectionImports(imports: List<CollectionImport>): List<CollectionImport> {
        val tasks = taskManager.tasks.value
        val tasksById = tasks.associateBy { it.id }
        return imports.map { record ->
            val reconciledItems = record.items.map { item ->
                if (item.status !in setOf(CollectionItemStatus.PREPARING, CollectionItemStatus.QUEUED) || item.songId == null) {
                    item
                } else {
                    val associated = item.downloadTaskId?.let(tasksById::get)?.takeIf {
                        it.songId == item.songId && record.id in it.collectionImportIds
                    } ?: tasks.firstOrNull { it.songId == item.songId && record.id in it.collectionImportIds }
                    if (associated != null) {
                        item.copy(status = CollectionItemStatus.QUEUED, downloadTaskId = associated.id)
                    } else {
                        item.copy(status = CollectionItemStatus.PREPARING, downloadTaskId = null)
                    }
                }
            }
            record.copy(items = reconciledItems)
        }
    }

    private fun launchCollectionAction(importId: String, action: suspend (String) -> Unit) {
        val record = mutableCollectionImports.value.firstOrNull { it.id == importId } ?: return
        val taskIds = record.items.mapNotNull { it.downloadTaskId }.distinct().ifEmpty {
            taskManager.tasks.value
                .filter { importId in it.collectionImportIds }
                .map { it.id }
                .distinct()
        }
        scope.launch {
            taskIds.forEach { taskId ->
                runCatching { action(taskId) }
            }
        }
    }

    companion object {
        private val SUPPORTED_QUALITIES = setOf("standard", "exhigh", "lossless", "hires")
        private const val RELOGIN_DISCOVERY_MESSAGE = "请先扫码登录，登录状态过期时请重新登录后再查看发现内容。"

        fun createDefault(appDataDirectory: Path = desktopAppDataDirectory()): DesktopViewModel {
            val store = DownloadTaskStore(appDataDirectory.resolve("tasks.json"))
            val diagnostics = DownloadDiagnosticsStore(appDataDirectory.resolve("download-diagnostics.json"))
            val startupNetworkSettings = store.load().settings.network
            val networkFactory = DesktopNetworkClientFactory(startupNetworkSettings)
            val service = DesktopService(diagnostics = diagnostics, networkFactory = networkFactory)
            val credentialStore: CredentialStore = if (
                System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
            ) WindowsCredentialStore() else UnsupportedCredentialStore()
            val sessionManager = SessionManager(
                credentialStore = credentialStore,
                validator = SessionValidator { musicU -> service.validateSession(musicU) },
            )
            // The manager must resolve every fresh URL through the current validated session.
            val manager = DownloadTaskManager(
                sourceResolver = service,
                transport = DesktopDownloader(diagnostics = diagnostics, networkFactory = networkFactory),
                store = store,
                sessionManager = sessionManager,
                postProcessor = DesktopAudioPostProcessor(
                    DesktopPostProcessingDataSource { task, musicU ->
                        service.loadPostProcessingData(task, task.postProcessingOptions, musicU)
                    },
                ),
                diagnostics = diagnostics,
            )
            return DesktopViewModel(
                service,
                manager,
                sessionManager,
                collectionStore = CollectionImportStore(appDataDirectory.resolve("collections.json")),
                qrLoginGateway = DesktopQrLoginApi(OkHttpQrLoginTransport(networkFactory = networkFactory)),
                diagnosticsStore = diagnostics,
            )
        }
    }
}

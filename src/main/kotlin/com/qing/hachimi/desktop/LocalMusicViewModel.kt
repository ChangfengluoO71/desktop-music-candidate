package com.qing.hachimi.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.logging.Logger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue

data class LocalMusicUiState(
    val roots: List<LocalMusicRoot> = emptyList(),
    val tracks: List<LocalTrack> = emptyList(),
    val searchFilter: String = "",
    val formatFilter: String = "",
    val artistFilter: String = "",
    val albumFilter: String = "",
    val selectedAlbumTrackKeys: Set<String>? = null,
    val browseMode: LocalMusicBrowseMode = LocalMusicBrowseMode.ALBUMS,
    val sortMode: LocalMusicSortMode = LocalMusicSortMode.TITLE,
    val folderFilter: String? = null,
    val isScanning: Boolean = false,
    val isAnalyzingDuplicates: Boolean = false,
    val progress: LocalScanProgress? = null,
    val analysisProgress: LocalScanProgress? = null,
    val duplicateAnalysis: DuplicateAnalysisResult? = null,
    val upgradeCandidates: List<LocalUpgradeCandidate> = emptyList(),
    val message: String = "选择本地音乐目录后开始扫描。",
    val recoveryWarning: String? = null,
) {
    val visibleTracks: List<LocalTrack>
        get() = tracks.asSequence()
            .filter { searchFilter.isBlank() || it.title.contains(searchFilter.trim(), ignoreCase = true) }
            .filter { formatFilter.isBlank() || it.format.name.contains(formatFilter.trim(), ignoreCase = true) }
            .filter { artistFilter.isBlank() || it.artists.any { artist -> artist.contains(artistFilter.trim(), ignoreCase = true) } }
            .filter { albumFilter.isBlank() || it.album.contains(albumFilter.trim(), ignoreCase = true) }
            .filter { selectedAlbumTrackKeys == null || it.pathKey in selectedAlbumTrackKeys }
            .filter { track ->
                val folder = folderFilter?.let(::normalizedLibraryPath) ?: return@filter true
                val path = normalizedLibraryPath(track.filePath)
                path == folder || path.startsWith("$folder/")
            }
            .sortedWith(localTrackComparator(sortMode))
            .toList()
}

private fun localTrackComparator(mode: LocalMusicSortMode): Comparator<LocalTrack> =
    compareBy<LocalTrack> {
        when (mode) {
            LocalMusicSortMode.TITLE -> it.title
            LocalMusicSortMode.ARTIST -> it.albumArtist.ifBlank { it.artists.firstOrNull().orEmpty() }
            LocalMusicSortMode.ALBUM -> it.album
            LocalMusicSortMode.FORMAT -> it.format.name
            LocalMusicSortMode.DURATION -> it.durationSeconds?.let { seconds -> "%020.3f".format(java.util.Locale.ROOT, seconds) }.orEmpty()
        }.lowercase(java.util.Locale.ROOT)
    }.thenBy { it.title.lowercase(java.util.Locale.ROOT) }.thenBy { it.filePath.lowercase(java.util.Locale.ROOT) }

class LocalMusicViewModel(
    private val store: LocalMusicStore,
    private val scanner: DesktopMusicScanner = DesktopMusicScanner(store),
    private val duplicateAnalyzer: LocalDuplicateAnalyzer = LocalDuplicateAnalyzer(store),
    private val candidateSource: LocalUpgradeCandidateSource? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val artworkInvalidator: (Path) -> Unit = {},
) {
    private val logger = Logger.getLogger(LocalMusicViewModel::class.java.name)
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val mutableState = MutableStateFlow(
        LocalMusicUiState(
            roots = runCatching { store.loadRoots() }.getOrDefault(emptyList()),
            tracks = runCatching { store.loadTracks() }.getOrDefault(emptyList()),
            recoveryWarning = store.recoveryWarning,
        ),
    )
    private var scanJob: Job? = null
    private var duplicateJob: Job? = null
    private val autoScanRequested = AtomicBoolean(false)
    private val queuedChanges = ConcurrentLinkedQueue<LocalMusicChange>()
    private val changeProcessing = AtomicBoolean(false)
    private val libraryMutationMutex = Mutex()
    private val directoryWatcher = LocalMusicDirectoryWatcher(onChange = ::onDirectoryChange)

    val state: StateFlow<LocalMusicUiState> = mutableState.asStateFlow()

    init {
        syncDirectoryWatcher()
    }

    fun addRoots(paths: List<Path>) {
        if (paths.isEmpty()) return
        scope.launch(ioDispatcher) {
            libraryMutationMutex.withLock {
                var added = 0
                var failed = 0
                paths.distinct().forEach { path ->
                    runCatching { store.registerRoot(path) }
                        .onSuccess { added++ }
                        .onFailure { failed++ }
                }
                refreshLibrary()
                syncDirectoryWatcher()
                mutableState.update {
                    it.copy(message = when {
                        added > 0 && failed == 0 -> "已添加 $added 个目录；原始文件不会被移动或修改。"
                        added > 0 -> "已添加 $added 个目录，另有 $failed 个目录无法读取。"
                        else -> "没有可添加的目录；请选择现存的音乐文件夹。"
                    })
                }
            }
        }
    }

    fun removeRoot(path: String) {
        if (state.value.isScanning || state.value.isAnalyzingDuplicates) return
        scope.launch(ioDispatcher) {
            libraryMutationMutex.withLock {
                runCatching { store.removeRoot(path) }
                refreshLibrary()
                syncDirectoryWatcher()
                mutableState.update { it.copy(message = "已移除目录索引；目录中的音乐文件没有删除。") }
            }
        }
    }

    fun setRootAutoWatch(path: String, enabled: Boolean) {
        scope.launch(ioDispatcher) {
            libraryMutationMutex.withLock {
                runCatching { store.setRootAutoWatch(path, enabled) }
                val roots = store.loadRoots()
                val tracks = store.loadTracks()
                syncDirectoryWatcher(roots)
                mutableState.update {
                    it.copy(
                        roots = roots,
                        tracks = tracks,
                        message = if (enabled) "已开启此目录的自动监听；文件变更将合并后重新扫描。" else "已关闭此目录的自动监听。",
                    )
                }
            }
        }
    }

    fun setFormatFilter(value: String) = mutableState.update { it.copy(formatFilter = value) }
    fun setSearchFilter(value: String) = mutableState.update { it.copy(searchFilter = value) }
    fun setArtistFilter(value: String) = mutableState.update { it.copy(artistFilter = value, selectedAlbumTrackKeys = null) }
    fun setAlbumFilter(value: String) = mutableState.update { it.copy(albumFilter = value, selectedAlbumTrackKeys = null) }
    fun setAlbumTracks(tracks: List<LocalTrack>) = mutableState.update {
        it.copy(artistFilter = "", albumFilter = "", selectedAlbumTrackKeys = tracks.mapTo(linkedSetOf()) { track -> track.pathKey })
    }
    fun setBrowseMode(mode: LocalMusicBrowseMode) = mutableState.update { it.copy(browseMode = mode) }
    fun setSortMode(mode: LocalMusicSortMode) = mutableState.update { it.copy(sortMode = mode) }
    fun setFolderFilter(path: String?) = mutableState.update { it.copy(folderFilter = path) }

    fun scan() {
        if (state.value.isScanning || scanJob?.isActive == true) return
        val roots = state.value.roots.map { Path.of(it.path) }
        if (roots.isEmpty()) {
            mutableState.update { it.copy(message = "请先添加至少一个音乐目录。") }
            return
        }
        scanJob = scope.launch {
            libraryMutationMutex.withLock {
                val activeRoots = store.loadRoots().map { Path.of(it.path) }
                if (activeRoots.isEmpty()) return@withLock
                mutableState.update { it.copy(isScanning = true, progress = LocalScanProgress(0, 0, null, null), message = "正在扫描本地音乐…") }
                try {
                    val result = scanner.scan(activeRoots) { progress -> mutableState.update { it.copy(progress = progress) } }
                    mutableState.update {
                        it.copy(
                            tracks = result.tracks,
                            roots = store.loadRoots(),
                            isScanning = false,
                            progress = null,
                            message = buildString {
                                append("扫描完成：索引 ${result.tracks.size} 项，新解析 ${result.scannedFiles} 项，")
                                append("复用 ${result.reusedMetadata} 项，失败 ${result.failedFiles} 项，")
                                append("不支持 ${result.unsupportedFiles} 项。")
                                if (result.rootErrors.isNotEmpty()) append("部分目录未完整读取，旧索引已保留。")
                            },
                        )
                    }
                    runPendingAutomaticRescan()
                } catch (cancelled: CancellationException) {
                    mutableState.update { it.copy(isScanning = false, progress = null, message = "扫描已取消；原始音乐文件未修改。") }
                    runPendingAutomaticRescan()
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.update { it.copy(isScanning = false, progress = null, message = "扫描失败；已保存的索引仍可使用。") }
                    runPendingAutomaticRescan()
                }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    fun analyzeDuplicates() {
        if (state.value.isScanning || state.value.isAnalyzingDuplicates || duplicateJob?.isActive == true) return
        val tracks = state.value.tracks
        if (tracks.isEmpty()) {
            mutableState.update { it.copy(message = "本地索引为空，请先扫描音乐目录。") }
            return
        }
        duplicateJob = scope.launch {
            libraryMutationMutex.withLock {
                mutableState.update { it.copy(isAnalyzingDuplicates = true, analysisProgress = LocalScanProgress(0, tracks.size, null, null), message = "正在逐个读取文件并计算 SHA-256…") }
                try {
                    val result = duplicateAnalyzer.analyze(tracks) { completed, total, path ->
                        mutableState.update { it.copy(analysisProgress = LocalScanProgress(completed, total, path, null)) }
                    }
                    mutableState.update {
                        it.copy(
                            isAnalyzingDuplicates = false,
                            analysisProgress = null,
                            duplicateAnalysis = result,
                            tracks = store.loadTracks(),
                            message = "已校验 ${result.hashedFiles} 个文件；完全重复 ${result.exactDuplicates.size} 组，疑似曲目 ${result.similarCandidates.size} 组。",
                        )
                    }
                    runPendingAutomaticRescan()
                } catch (cancelled: CancellationException) {
                    mutableState.update { it.copy(isAnalyzingDuplicates = false, analysisProgress = null, message = "重复分析已取消。") }
                    runPendingAutomaticRescan()
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.update { it.copy(isAnalyzingDuplicates = false, analysisProgress = null, message = "重复分析未完成；音乐文件未修改。") }
                    runPendingAutomaticRescan()
                }
            }
        }
    }

    fun cancelDuplicateAnalysis() {
        duplicateJob?.cancel()
    }

    fun previewUpgrades() {
        if (state.value.tracks.isEmpty()) {
            mutableState.update { it.copy(message = "本地索引为空，请先扫描音乐目录。") }
            return
        }
        scope.launch(ioDispatcher) {
            val candidates = mutableListOf<LocalUpgradeCandidate>()
            state.value.tracks.forEach { track ->
                val found = candidateSource?.findCandidates(track).orEmpty()
                if (found.isEmpty()) candidates += LocalMusicAnalyzer.previewWithoutOnlineCandidate(track)
                else found.forEach { candidates += LocalMusicAnalyzer.compareCandidate(track, it) }
            }
            mutableState.update {
                it.copy(
                    upgradeCandidates = candidates,
                    message = "候选仅供身份和技术参数预览；没有下载、替换或删除操作。",
                )
            }
        }
    }

    suspend fun shutdown() {
        // Include directory registration, removal, and preview jobs too: they can be
        // using the same database even when no scan or duplicate analysis is active.
        val scopeJob = scope.coroutineContext[Job]
        scopeJob?.cancel()
        directoryWatcher.close()
        val jobs = scopeJob?.children?.toList().orEmpty()
        val allStopped = try {
            withTimeoutOrNull(SHUTDOWN_JOB_JOIN_TIMEOUT_MILLIS) {
                jobs.joinAll()
                true
            } ?: false
        } finally {
            scope.cancel()
        }
        if (!allStopped) {
            val stillActive = jobs.count { it.isActive }
            logger.warning(
                "Shutdown timed out waiting for $stillActive local music job(s) to stop; leaving database cleanup to process exit.",
            )
            return
        }
        withContext(ioDispatcher) { store.close() }
    }

    private suspend fun refreshLibrary() {
        val roots = store.loadRoots()
        val tracks = store.loadTracks()
        mutableState.update { it.copy(roots = roots, tracks = tracks) }
    }

    private fun syncDirectoryWatcher(roots: List<LocalMusicRoot> = state.value.roots) {
        val watchedRoots = roots.filter(LocalMusicRoot::autoWatch).map { Path.of(it.path) }
        runCatching { directoryWatcher.start(watchedRoots) }
            .onFailure { logger.warning("Could not start local music directory watcher: ${it.javaClass.simpleName}") }
    }

    private fun onDirectoryChange(change: LocalMusicChange) {
        queuedChanges.add(change)
        runCatching { artworkInvalidator(change.path) }
        scheduleChangeProcessing()
    }

    private fun scheduleChangeProcessing() {
        if (state.value.isScanning || state.value.isAnalyzingDuplicates) {
            autoScanRequested.set(true)
            return
        }
        if (!changeProcessing.compareAndSet(false, true)) return
        scope.launch(ioDispatcher) {
            try {
                while (true) {
                    val pendingBatch = mutableListOf<LocalMusicChange>()
                    while (true) {
                        val next = queuedChanges.poll() ?: break
                        pendingBatch += next
                    }
                    val batch = pendingBatch.distinctBy {
                        "${normalizedLibraryPath(it.root.toString())}|${normalizedLibraryPath(it.path.toString())}|${it.kind}"
                    }
                    if (batch.isEmpty()) break
                    val result = libraryMutationMutex.withLock {
                        val watchedRoots = store.loadRoots().filter(LocalMusicRoot::autoWatch)
                            .mapTo(mutableSetOf()) { normalizedLibraryPath(it.path) }
                        val activeBatch = batch.filter { change ->
                            normalizedLibraryPath(change.root.toString()) in watchedRoots
                        }
                        if (activeBatch.isEmpty()) null else scanner.applyChanges(activeBatch)
                    } ?: continue
                    mutableState.update {
                        it.copy(
                            tracks = result.tracks,
                            roots = store.loadRoots(),
                            message = if (result.retryChanges.isEmpty()) {
                                "已更新监听目录中的 ${result.scannedFiles} 项变更；源文件未修改。"
                            } else {
                                "文件仍在写入；保留旧索引，稳定后自动重试。"
                            },
                        )
                    }
                    result.retryChanges.forEach(queuedChanges::add)
                    if (result.retryChanges.isNotEmpty()) delay(WATCH_RETRY_DELAY_MILLIS)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.warning("Incremental local music update failed: ${failure.javaClass.simpleName}")
                mutableState.update { it.copy(message = "监听更新失败；旧索引保留，请手动重新扫描。") }
            } finally {
                changeProcessing.set(false)
                if (queuedChanges.isNotEmpty()) scheduleChangeProcessing()
            }
        }
    }

    private fun runPendingAutomaticRescan() {
        if (autoScanRequested.getAndSet(false) || queuedChanges.isNotEmpty()) scheduleChangeProcessing()
    }

    companion object {
        fun createDefault(appDataDirectory: Path, artworkInvalidator: (Path) -> Unit = {}): LocalMusicViewModel = LocalMusicViewModel(
            store = LocalMusicStore(appDataDirectory.resolve("local-music.db")),
            artworkInvalidator = artworkInvalidator,
        )

        private const val WATCH_RETRY_DELAY_MILLIS = 1_000L
    }
}

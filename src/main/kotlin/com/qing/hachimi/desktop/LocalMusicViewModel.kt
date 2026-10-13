package com.qing.hachimi.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.logging.Logger

data class LocalMusicUiState(
    val roots: List<LocalMusicRoot> = emptyList(),
    val tracks: List<LocalTrack> = emptyList(),
    val formatFilter: String = "",
    val artistFilter: String = "",
    val albumFilter: String = "",
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
            .filter { formatFilter.isBlank() || it.format.name.contains(formatFilter.trim(), ignoreCase = true) }
            .filter { artistFilter.isBlank() || it.artists.any { artist -> artist.contains(artistFilter.trim(), ignoreCase = true) } }
            .filter { albumFilter.isBlank() || it.album.contains(albumFilter.trim(), ignoreCase = true) }
            .toList()
}

class LocalMusicViewModel(
    private val store: LocalMusicStore,
    private val scanner: DesktopMusicScanner = DesktopMusicScanner(store),
    private val duplicateAnalyzer: LocalDuplicateAnalyzer = LocalDuplicateAnalyzer(store),
    private val candidateSource: LocalUpgradeCandidateSource? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
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

    val state: StateFlow<LocalMusicUiState> = mutableState.asStateFlow()

    fun addRoots(paths: List<Path>) {
        if (paths.isEmpty()) return
        scope.launch(ioDispatcher) {
            var added = 0
            var failed = 0
            paths.distinct().forEach { path ->
                runCatching { store.registerRoot(path) }
                    .onSuccess { added++ }
                    .onFailure { failed++ }
            }
            refreshLibrary()
            mutableState.update {
                it.copy(message = when {
                    added > 0 && failed == 0 -> "已添加 $added 个目录；原始文件不会被移动或修改。"
                    added > 0 -> "已添加 $added 个目录，另有 $failed 个目录无法读取。"
                    else -> "没有可添加的目录；请选择现存的音乐文件夹。"
                })
            }
        }
    }

    fun removeRoot(path: String) {
        if (state.value.isScanning || state.value.isAnalyzingDuplicates) return
        scope.launch(ioDispatcher) {
            runCatching { store.removeRoot(path) }
            refreshLibrary()
            mutableState.update { it.copy(message = "已移除目录索引；目录中的音乐文件没有删除。") }
        }
    }

    fun setFormatFilter(value: String) = mutableState.update { it.copy(formatFilter = value) }
    fun setArtistFilter(value: String) = mutableState.update { it.copy(artistFilter = value) }
    fun setAlbumFilter(value: String) = mutableState.update { it.copy(albumFilter = value) }

    fun scan() {
        if (state.value.isScanning) return
        val roots = state.value.roots.map { Path.of(it.path) }
        if (roots.isEmpty()) {
            mutableState.update { it.copy(message = "请先添加至少一个音乐目录。") }
            return
        }
        scanJob = scope.launch {
            mutableState.update { it.copy(isScanning = true, progress = LocalScanProgress(0, 0, null, null), message = "正在扫描本地音乐…") }
            try {
                val result = scanner.scan(roots) { progress -> mutableState.update { it.copy(progress = progress) } }
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
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(isScanning = false, progress = null, message = "扫描已取消；原始音乐文件未修改。") }
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(isScanning = false, progress = null, message = "扫描失败；已保存的索引仍可使用。") }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    fun analyzeDuplicates() {
        if (state.value.isScanning || state.value.isAnalyzingDuplicates) return
        val tracks = state.value.tracks
        if (tracks.isEmpty()) {
            mutableState.update { it.copy(message = "本地索引为空，请先扫描音乐目录。") }
            return
        }
        duplicateJob = scope.launch {
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
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(isAnalyzingDuplicates = false, analysisProgress = null, message = "重复分析已取消。") }
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(isAnalyzingDuplicates = false, analysisProgress = null, message = "重复分析未完成；音乐文件未修改。") }
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

    companion object {
        fun createDefault(appDataDirectory: Path): LocalMusicViewModel = LocalMusicViewModel(
            store = LocalMusicStore(appDataDirectory.resolve("local-music.db")),
        )
    }
}

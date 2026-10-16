package com.qing.hachimi.desktop

import org.json.JSONArray
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/** SQLite index kept separate from download task and collection stores. */
class LocalMusicStore(private val databasePath: Path) : AutoCloseable {
    private val monitor = Any()
    private var connection: Connection
    @Volatile
    var recoveryWarning: String? = null
        private set

    init {
        databasePath.toAbsolutePath().normalize().parent?.let(Files::createDirectories)
        Class.forName("org.sqlite.JDBC")
        connection = openConnection()
        try {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA journal_mode = WAL")
                statement.execute("PRAGMA synchronous = FULL")
                statement.execute("PRAGMA busy_timeout = 5000")
                statement.executeQuery("PRAGMA quick_check").use { result ->
                    if (!result.next() || !result.getString(1).equals("ok", ignoreCase = true)) {
                        throw SQLException("local music index integrity check failed")
                    }
                }
            }
        } catch (failure: SQLException) {
            runCatching { connection.close() }
            if (!Files.isRegularFile(databasePath) || Files.size(databasePath) == 0L) throw failure
            val backup = nextCorruptBackupPath()
            Files.move(databasePath, backup)
            moveSidecar("-wal", backup)
            moveSidecar("-shm", backup)
            recoveryWarning = "本地音乐索引无法读取，已保留损坏副本并重建索引；音乐文件未修改。"
            connection = openConnection()
            configureConnection(connection)
            createSchema(connection)
        }
        configureConnection(connection)
        createSchema(connection)
    }

    fun registerRoot(root: Path): LocalMusicRoot = synchronized(monitor) {
        val canonical = root.toAbsolutePath().normalize().toRealPath()
        require(Files.isDirectory(canonical)) { "扫描路径不是文件夹" }
        val path = canonical.toString()
        val addedAt = System.currentTimeMillis()
        connection.prepareStatement(
            "INSERT INTO roots(root_path, added_at) VALUES(?, ?) " +
                "ON CONFLICT(root_path) DO NOTHING",
        ).use { statement ->
            statement.setString(1, path)
            statement.setLong(2, addedAt)
            statement.executeUpdate()
        }
        val rootSettings = connection.prepareStatement("SELECT added_at, auto_watch FROM roots WHERE root_path = ?").use { statement ->
            statement.setString(1, path)
            statement.executeQuery().use { result -> if (result.next()) result.getLong(1) to (result.getInt(2) != 0) else addedAt to false }
        }
        LocalMusicRoot(path, rootSettings.first, rootSettings.second)
    }

    fun setRootAutoWatch(rootPath: String, enabled: Boolean) = synchronized(monitor) {
        connection.prepareStatement("UPDATE roots SET auto_watch = ? WHERE root_path = ?").use { statement ->
            statement.setInt(1, if (enabled) 1 else 0)
            statement.setString(2, rootPath)
            statement.executeUpdate()
        }
        Unit
    }

    fun loadRoots(): List<LocalMusicRoot> = synchronized(monitor) {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT root_path, added_at, auto_watch FROM roots ORDER BY root_path COLLATE NOCASE").use { result ->
                buildList {
                    while (result.next()) {
                        add(LocalMusicRoot(result.getString(1), result.getLong(2), result.getInt(3) != 0))
                    }
                }
            }
        }
    }

    fun removeRoot(rootPath: String) = synchronized(monitor) {
        transaction {
            connection.prepareStatement("DELETE FROM roots WHERE root_path = ?").use { statement ->
                statement.setString(1, rootPath)
                statement.executeUpdate()
            }
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM tracks WHERE path_key NOT IN (SELECT path_key FROM root_tracks)")
            }
        }
        Unit
    }

    fun findTrack(pathKey: String): LocalTrack? = synchronized(monitor) {
        connection.prepareStatement("SELECT * FROM tracks WHERE path_key = ?").use { statement ->
            statement.setString(1, pathKey)
            statement.executeQuery().use { result -> if (result.next()) result.toLocalTrack() else null }
        }
    }

    fun saveTrack(rootPath: String, scanId: String, track: LocalTrack) = synchronized(monitor) {
        transaction {
            connection.prepareStatement(UPSERT_TRACK).use { statement ->
                statement.bind(track)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO root_tracks(root_path, path_key, scan_id) VALUES(?, ?, ?) " +
                    "ON CONFLICT(root_path, path_key) DO UPDATE SET scan_id = excluded.scan_id",
            ).use { statement ->
                statement.setString(1, rootPath)
                statement.setString(2, track.pathKey)
                statement.setString(3, scanId)
                statement.executeUpdate()
            }
        }
        Unit
    }

    /** Removes a deleted file or subtree from one root; shared tracks survive other root memberships. */
    fun removePathMembership(rootPath: String, path: Path): Int = synchronized(monitor) {
        val target = normalizedLibraryPath(path)
        var removedCount = 0
        transaction {
            val matchingKeys = connection.prepareStatement(
                "SELECT t.path_key, t.file_path FROM tracks t JOIN root_tracks rt ON rt.path_key = t.path_key WHERE rt.root_path = ?",
            ).use { statement ->
                statement.setString(1, rootPath)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            val storedPath = result.getString("file_path")
                            val lexicalPath = normalizedLibraryPath(storedPath)
                            val filePath = if (lexicalPath == target || lexicalPath.startsWith("$target/")) {
                                lexicalPath
                            } else {
                                normalizedLibraryPath(Path.of(storedPath))
                            }
                            if (filePath == target || filePath.startsWith("$target/")) add(result.getString("path_key"))
                        }
                    }
                }
            }
            matchingKeys.forEach { key ->
                connection.prepareStatement("DELETE FROM root_tracks WHERE root_path = ? AND path_key = ?").use { statement ->
                    statement.setString(1, rootPath)
                    statement.setString(2, key)
                    statement.executeUpdate()
                }
            }
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM tracks WHERE path_key NOT IN (SELECT path_key FROM root_tracks)")
            }
            removedCount = matchingKeys.size
        }
        removedCount
    }

    /** Remove stale path memberships only after a complete root scan. */
    fun finishRootScan(rootPath: String, scanId: String) = synchronized(monitor) {
        transaction {
            connection.prepareStatement("DELETE FROM root_tracks WHERE root_path = ? AND scan_id <> ?").use { statement ->
                statement.setString(1, rootPath)
                statement.setString(2, scanId)
                statement.executeUpdate()
            }
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM tracks WHERE path_key NOT IN (SELECT path_key FROM root_tracks)")
            }
        }
        Unit
    }

    fun loadTracks(): List<LocalTrack> = synchronized(monitor) {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM tracks ORDER BY title COLLATE NOCASE, file_path COLLATE NOCASE").use { result ->
                buildList { while (result.next()) add(result.toLocalTrack()) }
            }
        }
    }

    fun updateContentHash(track: LocalTrack, sha256: String): Boolean = synchronized(monitor) {
        connection.prepareStatement(
            "UPDATE tracks SET content_sha256 = ? WHERE path_key = ? AND file_size = ? AND modified_at = ?",
        ).use { statement ->
            statement.setString(1, sha256)
            statement.setString(2, track.pathKey)
            statement.setLong(3, track.fileSize)
            statement.setLong(4, track.lastModifiedMillis)
            statement.executeUpdate() == 1
        }
    }

    fun clearContentHash(pathKey: String) = synchronized(monitor) {
        connection.prepareStatement("UPDATE tracks SET content_sha256 = NULL WHERE path_key = ?").use { statement ->
            statement.setString(1, pathKey)
            statement.executeUpdate()
        }
        Unit
    }

    override fun close() = synchronized(monitor) {
        if (!connection.isClosed) connection.close()
    }

    private fun transaction(block: () -> Unit) {
        val oldAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            block()
            connection.commit()
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            throw failure
        } finally {
            connection.autoCommit = oldAutoCommit
        }
    }

    private fun openConnection(): Connection = DriverManager.getConnection(
        "jdbc:sqlite:${databasePath.toAbsolutePath().normalize()}",
    )

    private fun configureConnection(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute("PRAGMA synchronous = FULL")
            statement.execute("PRAGMA busy_timeout = 5000")
        }
    }

    private fun createSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """CREATE TABLE IF NOT EXISTS roots (
                    root_path TEXT PRIMARY KEY,
                    added_at INTEGER NOT NULL,
                    auto_watch INTEGER NOT NULL DEFAULT 0
                )""".trimIndent(),
            )
            try {
                statement.execute("ALTER TABLE roots ADD COLUMN auto_watch INTEGER NOT NULL DEFAULT 0")
            } catch (failure: SQLException) {
                if (!failure.message.orEmpty().contains("duplicate column name", ignoreCase = true)) throw failure
            }
            statement.execute(
                """CREATE TABLE IF NOT EXISTS tracks (
                    path_key TEXT PRIMARY KEY,
                    id TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    file_size INTEGER NOT NULL,
                    modified_at INTEGER NOT NULL,
                    format TEXT NOT NULL,
                    codec TEXT,
                    lossiness TEXT NOT NULL,
                    duration_seconds REAL,
                    bitrate_kbps INTEGER,
                    sample_rate_hz INTEGER,
                    bit_depth INTEGER,
                    title TEXT NOT NULL,
                    title_source TEXT NOT NULL,
                    artists_json TEXT NOT NULL,
                    album TEXT NOT NULL,
                    album_artist TEXT NOT NULL,
                    track_number INTEGER,
                    disc_number INTEGER,
                    metadata_status TEXT NOT NULL,
                    error_kind TEXT,
                    error_summary TEXT,
                    content_sha256 TEXT,
                    scanned_at INTEGER NOT NULL
                )""".trimIndent(),
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS root_tracks (
                    root_path TEXT NOT NULL REFERENCES roots(root_path) ON DELETE CASCADE,
                    path_key TEXT NOT NULL REFERENCES tracks(path_key) ON DELETE CASCADE,
                    scan_id TEXT NOT NULL,
                    PRIMARY KEY(root_path, path_key)
                )""".trimIndent(),
            )
            statement.execute("CREATE INDEX IF NOT EXISTS tracks_artist_idx ON tracks(title, album_artist)")
            statement.execute("CREATE INDEX IF NOT EXISTS tracks_format_idx ON tracks(format)")
        }
    }

    private fun nextCorruptBackupPath(): Path {
        val timestamp = System.currentTimeMillis()
        val base = databasePath.resolveSibling("${databasePath.fileName}.corrupt.$timestamp")
        var candidate = base
        var index = 1
        while (Files.exists(candidate)) {
            candidate = databasePath.resolveSibling("${databasePath.fileName}.corrupt.$timestamp.$index")
            index++
        }
        return candidate
    }

    private fun moveSidecar(suffix: String, backup: Path) {
        val sidecar = databasePath.resolveSibling(databasePath.fileName.toString() + suffix)
        if (Files.exists(sidecar)) {
            Files.move(sidecar, backup.resolveSibling(backup.fileName.toString() + suffix))
        }
    }

    private fun java.sql.ResultSet.toLocalTrack(): LocalTrack {
        val kind = getString("error_kind")?.let { runCatching { LocalScanErrorKind.valueOf(it) }.getOrNull() }
        val errorSummary = getString("error_summary")
        val error = if (kind == null || errorSummary == null) null else LocalScanError(kind, errorSummary)
        val artists = runCatching {
            val array = JSONArray(getString("artists_json"))
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())
        return LocalTrack(
            id = getString("id"),
            pathKey = getString("path_key"),
            filePath = getString("file_path"),
            fileSize = getLong("file_size"),
            lastModifiedMillis = getLong("modified_at"),
            format = runCatching { LocalAudioFormat.valueOf(getString("format")) }.getOrDefault(LocalAudioFormat.OTHER),
            codec = getString("codec"),
            lossiness = runCatching { AudioLossiness.valueOf(getString("lossiness")) }
                .getOrDefault(AudioLossiness.UNKNOWN),
            durationSeconds = getDouble("duration_seconds").takeIf { !wasNull() },
            bitrateKbps = getInt("bitrate_kbps").takeIf { !wasNull() },
            sampleRateHz = getInt("sample_rate_hz").takeIf { !wasNull() },
            bitDepth = getInt("bit_depth").takeIf { !wasNull() },
            title = getString("title").orEmpty(),
            titleSource = runCatching { LocalTitleSource.valueOf(getString("title_source")) }
                .getOrDefault(LocalTitleSource.UNKNOWN),
            artists = artists,
            album = getString("album").orEmpty(),
            albumArtist = getString("album_artist").orEmpty(),
            trackNumber = getInt("track_number").takeIf { !wasNull() },
            discNumber = getInt("disc_number").takeIf { !wasNull() },
            metadataStatus = runCatching { LocalMetadataStatus.valueOf(getString("metadata_status")) }
                .getOrDefault(LocalMetadataStatus.DAMAGED),
            error = error,
            contentSha256 = getString("content_sha256"),
            scannedAtMillis = getLong("scanned_at"),
        )
    }

    private fun java.sql.PreparedStatement.bind(track: LocalTrack) {
        setString(1, track.pathKey)
        setString(2, track.id)
        setString(3, track.filePath)
        setLong(4, track.fileSize)
        setLong(5, track.lastModifiedMillis)
        setString(6, track.format.name)
        setString(7, track.codec)
        setString(8, track.lossiness.name)
        setNullableDouble(9, track.durationSeconds)
        setNullableInt(10, track.bitrateKbps)
        setNullableInt(11, track.sampleRateHz)
        setNullableInt(12, track.bitDepth)
        setString(13, track.title)
        setString(14, track.titleSource.name)
        setString(15, JSONArray(track.artists).toString())
        setString(16, track.album)
        setString(17, track.albumArtist)
        setNullableInt(18, track.trackNumber)
        setNullableInt(19, track.discNumber)
        setString(20, track.metadataStatus.name)
        setString(21, track.error?.kind?.name)
        setString(22, track.error?.summary)
        setString(23, track.contentSha256)
        setLong(24, track.scannedAtMillis)
    }

    private fun java.sql.PreparedStatement.setNullableInt(index: Int, value: Int?) {
        if (value == null) setNull(index, java.sql.Types.INTEGER) else setInt(index, value)
    }

    private fun java.sql.PreparedStatement.setNullableDouble(index: Int, value: Double?) {
        if (value == null) setNull(index, java.sql.Types.REAL) else setDouble(index, value)
    }

    companion object {
        private val UPSERT_TRACK = """INSERT INTO tracks(
            path_key, id, file_path, file_size, modified_at, format, codec, lossiness, duration_seconds,
            bitrate_kbps, sample_rate_hz, bit_depth, title, title_source, artists_json, album,
            album_artist, track_number, disc_number, metadata_status, error_kind, error_summary,
            content_sha256, scanned_at
        ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(path_key) DO UPDATE SET
            id=excluded.id, file_path=excluded.file_path, file_size=excluded.file_size,
            modified_at=excluded.modified_at, format=excluded.format, codec=excluded.codec,
            lossiness=excluded.lossiness, duration_seconds=excluded.duration_seconds, bitrate_kbps=excluded.bitrate_kbps,
            sample_rate_hz=excluded.sample_rate_hz, bit_depth=excluded.bit_depth,
            title=excluded.title, title_source=excluded.title_source, artists_json=excluded.artists_json,
            album=excluded.album, album_artist=excluded.album_artist,
            track_number=excluded.track_number, disc_number=excluded.disc_number,
            metadata_status=excluded.metadata_status, error_kind=excluded.error_kind,
            error_summary=excluded.error_summary,
            content_sha256=CASE WHEN tracks.file_size=excluded.file_size AND tracks.modified_at=excluded.modified_at
                THEN tracks.content_sha256 ELSE NULL END,
            scanned_at=excluded.scanned_at""".trimIndent()
    }
}

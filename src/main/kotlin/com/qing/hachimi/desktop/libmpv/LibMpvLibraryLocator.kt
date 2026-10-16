package com.qing.hachimi.desktop.libmpv

import java.nio.file.Files
import java.nio.file.Path

class LibMpvUnavailableException(
    val userMessage: String,
    cause: Throwable? = null,
) : IllegalStateException(userMessage, cause)

/** Resolves libmpv only from an explicit override or a private app-data/install folder. */
object LibMpvLibraryLocator {
    const val DLL_NAME = "libmpv-2.dll"
    const val APP_PRIVATE_SUBDIRECTORY = "libmpv"

    fun locate(
        explicitLibraryPath: String?,
        appDataDirectory: Path,
        installationDirectory: Path? = null,
    ): Path {
        val configuredPath = explicitLibraryPath?.takeIf(String::isNotBlank)
        if (configuredPath != null) return resolveExplicit(configuredPath)

        val privateRoots = listOfNotNull(appDataDirectory, installationDirectory)
            .map { it.toAbsolutePath().normalize() }
            .distinct()
        for (privateRoot in privateRoots) {
            resolvePrivateCandidate(privateRoot)?.let { return it }
        }
        if (privateRoots.isNotEmpty()) {
            throw LibMpvUnavailableException(
                "$DLL_NAME was not found in the user's app-data or installed-app private libmpv folder. Install the local runtime in a private libmpv folder to enable playback.",
            )
        }
        throw LibMpvUnavailableException(
            "$DLL_NAME was not found. Install the local libmpv runtime in the application's private libmpv folder to enable playback.",
        )
    }

    private fun resolvePrivateCandidate(privateRoot: Path): Path? {
        val privateDirectory = privateRoot.resolve(APP_PRIVATE_SUBDIRECTORY)
        val candidate = privateDirectory.resolve(DLL_NAME)
        if (!Files.isRegularFile(candidate)) return null
        return try {
            val canonicalRoot = privateRoot.toRealPath()
            val canonicalDirectory = privateDirectory.toRealPath()
            val canonicalFile = candidate.toRealPath()
            require(canonicalDirectory.startsWith(canonicalRoot)) {
                "The app-private libmpv folder resolves outside its private root."
            }
            require(canonicalFile.startsWith(canonicalDirectory)) {
                "The app-private libmpv path resolves outside its protected folder."
            }
            validateFile(canonicalFile)
        } catch (failure: IllegalArgumentException) {
            throw failure
        } catch (failure: Exception) {
            throw LibMpvUnavailableException(
                "The app-private $DLL_NAME could not be read. Reinstall the local libmpv runtime to enable playback.",
                failure,
            )
        }
    }

    private fun resolveExplicit(value: String): Path {
        val configured = try {
            Path.of(value)
        } catch (failure: Exception) {
            throw IllegalArgumentException("The configured libmpv library path is invalid.", failure)
        }
        require(configured.isAbsolute) { "The configured libmpv library path must be absolute." }
        val canonical = try {
            configured.toRealPath()
        } catch (failure: Exception) {
            throw LibMpvUnavailableException("The configured $DLL_NAME could not be found or read.", failure)
        }
        return validateFile(canonical)
    }

    private fun validateFile(path: Path): Path {
        require(Files.isRegularFile(path) && path.fileName.toString().equals(DLL_NAME, ignoreCase = true)) {
            "The configured native library must be an existing $DLL_NAME file."
        }
        return path
    }
}

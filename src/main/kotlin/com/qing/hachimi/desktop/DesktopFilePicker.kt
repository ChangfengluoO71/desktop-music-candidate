package com.qing.hachimi.desktop

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.EnumMap
import java.util.logging.Level
import java.util.logging.Logger
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** Uses the Windows Common Item Dialog on Windows and Swing as a portable fallback elsewhere. */
internal object DesktopFilePicker {
    private enum class PickerKind { BACKDROP_IMAGE, DOWNLOAD_DIRECTORY, MUSIC_DIRECTORY }

    private data class PickerResult(val paths: List<Path>, val currentDirectory: Path?)

    private val logger = Logger.getLogger(DesktopFilePicker::class.java.name)
    private val lastDirectories = EnumMap<PickerKind, Path>(PickerKind::class.java)
    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    @Synchronized
    fun chooseBackdropImage(): Path? = choose(PickerKind.BACKDROP_IMAGE).firstOrNull()

    @Synchronized
    fun chooseMusicDirectories(): List<Path> = choose(PickerKind.MUSIC_DIRECTORY)

    @Synchronized
    fun chooseDownloadDirectory(initialDirectory: Path? = null): Path? =
        choose(PickerKind.DOWNLOAD_DIRECTORY, initialDirectory).firstOrNull()

    private fun choose(kind: PickerKind, preferredDirectory: Path? = null): List<Path> {
        val initialDirectory = preferredDirectory?.takeIf(Files::isDirectory)
            ?: lastDirectories[kind]?.takeIf(Files::isDirectory)
        val result = onAwtEventThread {
            if (isWindows) {
                try {
                    showWindowsDialog(kind, initialDirectory)
                } catch (error: Exception) {
                    logger.log(Level.WARNING, "Windows file dialog failed; using the portable picker.", error)
                    showSwingFallback(kind, initialDirectory)
                }
            } else {
                showSwingFallback(kind, initialDirectory)
            }
        }
        result.currentDirectory?.takeIf(Files::isDirectory)?.let { lastDirectories[kind] = it }
        return result.paths.filter { path ->
            if (kind == PickerKind.BACKDROP_IMAGE) Files.isRegularFile(path) else Files.isDirectory(path)
        }
    }

    private fun onAwtEventThread(action: () -> PickerResult): PickerResult {
        if (EventQueue.isDispatchThread()) return action()
        var result: PickerResult? = null
        var failure: Throwable? = null
        EventQueue.invokeAndWait {
            try {
                result = action()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
        return result ?: PickerResult(emptyList(), null)
    }

    private fun showWindowsDialog(kind: PickerKind, initialDirectory: Path?): PickerResult {
        val initialized = Ole32.INSTANCE.CoInitializeEx(null, COINIT_APARTMENTTHREADED)
        if (initialized < 0) throw NativeDialogException("COM initialization failed: 0x${initialized.toUInt().toString(16)}")

        val clsid = guid(CLSID_FILE_OPEN_DIALOG)
        val iid = guid(IID_FILE_OPEN_DIALOG)
        val dialogRef = PointerByReference()
        var dialog: Pointer? = null
        try {
            checkHResult(
                Ole32.INSTANCE.CoCreateInstance(clsid, null, CLSCTX_INPROC_SERVER, iid, dialogRef),
                "Creating the Windows file dialog",
            )
            dialog = dialogRef.value ?: throw NativeDialogException("Windows file dialog returned no interface.")

            val titleMemory = wideMemory(dialogTitle(kind))
            try {
                checkHResult(call(dialog, IFILEDIALOG_SET_TITLE, titleMemory), "Setting the dialog title")
            } finally {
                titleMemory.close()
            }

            if (kind == PickerKind.BACKDROP_IMAGE) {
                setImageFilter(dialog)
            }
            setDialogOptions(dialog, kind)
            initialDirectory?.let { setDefaultFolder(dialog, it) }

            val owner = User32.INSTANCE.GetForegroundWindow()
                ?.takeIf { Pointer.nativeValue(it) != 0L }
                ?: Pointer.NULL
            val showResult = call(dialog, IFILEDIALOG_SHOW, owner)
            if (showResult == HRESULT_CANCELLED) return PickerResult(emptyList(), null)
            checkHResult(showResult, "Showing the Windows file dialog")

            val selected = if (kind == PickerKind.MUSIC_DIRECTORY) {
                readMultipleResults(dialog)
            } else {
                readSingleResult(dialog)
            }
            val currentDirectory = readCurrentFolder(dialog) ?: selected.firstOrNull()?.parent
            return PickerResult(selected, currentDirectory)
        } finally {
            dialog?.let(::releaseComObject)
            clsid.close()
            iid.close()
            Ole32.INSTANCE.CoUninitialize()
        }
    }

    private fun setImageFilter(dialog: Pointer) {
        val title = wideMemory("Images (JPG, PNG, WebP)")
        val pattern = wideMemory("*.jpg;*.jpeg;*.png;*.webp")
        val filters = Memory((Native.POINTER_SIZE * 2).toLong())
        try {
            filters.setPointer(0, title)
            filters.setPointer(Native.POINTER_SIZE.toLong(), pattern)
            checkHResult(call(dialog, IFILEDIALOG_SET_FILE_TYPES, 1, filters), "Setting image file types")
        } finally {
            filters.close()
            pattern.close()
            title.close()
        }
    }

    private fun setDialogOptions(dialog: Pointer, kind: PickerKind) {
        val options = Memory(Int.SIZE_BYTES.toLong())
        try {
            checkHResult(call(dialog, IFILEDIALOG_GET_OPTIONS, options), "Reading dialog options")
            var flags = options.getInt(0) or FOS_FORCEFILESYSTEM or FOS_PATHMUSTEXIST or FOS_NOCHANGEDIR
            flags = flags and FOS_FILEMUSTEXIST.inv() and FOS_ALLOWMULTISELECT.inv()
            flags = if (kind == PickerKind.BACKDROP_IMAGE) flags or FOS_FILEMUSTEXIST else flags or FOS_PICKFOLDERS
            if (kind == PickerKind.MUSIC_DIRECTORY) flags = flags or FOS_ALLOWMULTISELECT
            checkHResult(call(dialog, IFILEDIALOG_SET_OPTIONS, flags), "Setting dialog options")
        } finally {
            options.close()
        }
    }

    private fun setDefaultFolder(dialog: Pointer, directory: Path) {
        val shellItemIid = guid(IID_SHELL_ITEM)
        val itemRef = PointerByReference()
        var item: Pointer? = null
        try {
            checkHResult(
                Shell32.INSTANCE.SHCreateItemFromParsingName(WString(directory.toString()), null, shellItemIid, itemRef),
                "Opening the initial directory",
            )
            item = itemRef.value ?: throw NativeDialogException("Windows could not open the initial directory.")
            checkHResult(call(dialog, IFILEDIALOG_SET_FOLDER, item), "Setting the initial directory")
        } finally {
            item?.let(::releaseComObject)
            shellItemIid.close()
        }
    }

    private fun readSingleResult(dialog: Pointer): List<Path> {
        val itemRef = PointerByReference()
        checkHResult(call(dialog, IFILEDIALOG_GET_RESULT, itemRef), "Reading the selected file")
        val item = itemRef.value ?: return emptyList()
        return try {
            listOfNotNull(readFileSystemPath(item))
        } finally {
            releaseComObject(item)
        }
    }

    private fun readMultipleResults(dialog: Pointer): List<Path> {
        val arrayRef = PointerByReference()
        checkHResult(call(dialog, IFILEOPEN_GET_RESULTS, arrayRef), "Reading selected directories")
        val array = arrayRef.value ?: return emptyList()
        try {
            val count = Memory(Int.SIZE_BYTES.toLong())
            try {
                checkHResult(call(array, ISHELLITEMARRAY_GET_COUNT, count), "Counting selected directories")
                return buildList {
                    val resultCount = count.getInt(0)
                    if (resultCount !in 0..MAX_MULTISELECT_RESULTS) {
                        throw NativeDialogException("Windows returned an invalid directory selection count.")
                    }
                    repeat(resultCount) { index ->
                        val itemRef = PointerByReference()
                        checkHResult(call(array, ISHELLITEMARRAY_GET_ITEM_AT, index, itemRef), "Reading a selected directory")
                        val item = itemRef.value ?: return@repeat
                        try {
                            readFileSystemPath(item)?.let(::add)
                        } finally {
                            releaseComObject(item)
                        }
                    }
                }
            } finally {
                count.close()
            }
        } finally {
            releaseComObject(array)
        }
    }

    private fun readCurrentFolder(dialog: Pointer): Path? {
        val itemRef = PointerByReference()
        val result = call(dialog, IFILEDIALOG_GET_FOLDER, itemRef)
        if (result < 0) return null
        val item = itemRef.value ?: return null
        return try {
            readFileSystemPath(item)
        } finally {
            releaseComObject(item)
        }
    }

    private fun readFileSystemPath(shellItem: Pointer): Path? {
        val pathRef = PointerByReference()
        checkHResult(call(shellItem, ISHELLITEM_GET_DISPLAY_NAME, SIGDN_FILESYSPATH, pathRef), "Reading a filesystem path")
        val path = pathRef.value ?: return null
        return try {
            Path.of(path.getWideString(0))
        } finally {
            Ole32.INSTANCE.CoTaskMemFree(path)
        }
    }

    private fun showSwingFallback(kind: PickerKind, initialDirectory: Path?): PickerResult {
        val chooser = JFileChooser(initialDirectory?.toFile()).apply {
            dialogTitle = dialogTitle(kind)
            isMultiSelectionEnabled = kind == PickerKind.MUSIC_DIRECTORY
            fileSelectionMode = if (kind == PickerKind.BACKDROP_IMAGE) JFileChooser.FILES_ONLY else JFileChooser.DIRECTORIES_ONLY
            if (kind == PickerKind.BACKDROP_IMAGE) {
                fileFilter = FileNameExtensionFilter("Images (JPG, PNG, WebP)", "jpg", "jpeg", "png", "webp")
            }
        }
        if (chooser.showOpenDialog(KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow) != JFileChooser.APPROVE_OPTION) {
            return PickerResult(emptyList(), null)
        }
        val selected = chooser.selectedFiles.orEmpty().map { it.toPath() }
            .ifEmpty { listOfNotNull(chooser.selectedFile?.toPath()) }
            .filter { Files.isDirectory(it) == (kind != PickerKind.BACKDROP_IMAGE) }
        return PickerResult(selected, chooser.currentDirectory?.toPath())
    }

    private fun releaseComObject(instance: Pointer) {
        call(instance, IUNKNOWN_RELEASE)
    }

    private fun call(instance: Pointer, index: Int, vararg arguments: Any?): Int {
        val vtable = instance.getPointer(0) ?: throw NativeDialogException("Windows dialog interface has no vtable.")
        val functionPointer = vtable.getPointer(index.toLong() * Native.POINTER_SIZE)
            ?: throw NativeDialogException("Windows dialog interface has no function at slot $index.")
        val function = Function.getFunction(functionPointer, Function.ALT_CONVENTION)
        val parameters = arrayOfNulls<Any>(arguments.size + 1)
        parameters[0] = instance
        arguments.copyInto(parameters, destinationOffset = 1)
        return function.invokeInt(parameters)
    }

    private fun checkHResult(result: Int, operation: String) {
        if (result < 0) throw NativeDialogException("$operation failed: 0x${result.toUInt().toString(16)}")
    }

    private fun guid(value: String): Memory {
        val parts = value.split('-')
        val memory = Memory(16)
        memory.setInt(0, parts[0].toLong(16).toInt())
        memory.setShort(4, parts[1].toInt(16).toShort())
        memory.setShort(6, parts[2].toInt(16).toShort())
        val tail = (parts[3] + parts[4]).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        memory.write(8, tail, 0, tail.size)
        return memory
    }

    private fun wideMemory(value: String): Memory = Memory((value.length + 1L) * Native.WCHAR_SIZE).apply {
        setWideString(0, value)
    }

    private fun dialogTitle(kind: PickerKind): String = when (kind) {
        PickerKind.BACKDROP_IMAGE -> "选择沉浸式播放背景图片"
        PickerKind.DOWNLOAD_DIRECTORY -> "选择默认下载目录"
        PickerKind.MUSIC_DIRECTORY -> "选择本地音乐目录"
    }

    private class NativeDialogException(message: String) : IllegalStateException(message)

    private interface Ole32Api : StdCallLibrary {
        fun CoInitializeEx(reserved: Pointer?, coInit: Int): Int
        fun CoUninitialize()
        fun CoCreateInstance(
            classId: Pointer,
            outer: Pointer?,
            context: Int,
            interfaceId: Pointer,
            instance: PointerByReference,
        ): Int
        fun CoTaskMemFree(memory: Pointer?)
    }

    private interface Shell32Api : StdCallLibrary {
        fun SHCreateItemFromParsingName(
            path: WString,
            bindContext: Pointer?,
            interfaceId: Pointer,
            item: PointerByReference,
        ): Int
    }

    private interface User32Api : StdCallLibrary {
        fun GetForegroundWindow(): Pointer?
    }

    private object Ole32 {
        val INSTANCE: Ole32Api by lazy { Native.load("ole32", Ole32Api::class.java) }
    }

    private object Shell32 {
        val INSTANCE: Shell32Api by lazy { Native.load("shell32", Shell32Api::class.java) }
    }

    private object User32 {
        val INSTANCE: User32Api by lazy { Native.load("user32", User32Api::class.java) }
    }

    private const val CLSID_FILE_OPEN_DIALOG = "DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7"
    private const val IID_FILE_OPEN_DIALOG = "D57C7288-D4AD-4768-BE02-9D969532D960"
    private const val IID_SHELL_ITEM = "43826D1E-E718-42EE-BC55-A1E261C37BFE"
    private const val CLSCTX_INPROC_SERVER = 1
    private const val COINIT_APARTMENTTHREADED = 2
    private const val HRESULT_CANCELLED = 0x800704C7.toInt()
    private const val SIGDN_FILESYSPATH = 0x80058000.toInt()
    private const val FOS_NOCHANGEDIR = 0x00000008
    private const val FOS_PICKFOLDERS = 0x00000020
    private const val FOS_FORCEFILESYSTEM = 0x00000040
    private const val FOS_PATHMUSTEXIST = 0x00000800
    private const val FOS_FILEMUSTEXIST = 0x00001000
    private const val FOS_ALLOWMULTISELECT = 0x00000200
    private const val MAX_MULTISELECT_RESULTS = 256

    private const val IUNKNOWN_RELEASE = 2
    private const val IFILEDIALOG_SHOW = 3
    private const val IFILEDIALOG_SET_FILE_TYPES = 4
    private const val IFILEDIALOG_SET_OPTIONS = 9
    private const val IFILEDIALOG_GET_OPTIONS = 10
    private const val IFILEDIALOG_SET_FOLDER = 12
    private const val IFILEDIALOG_GET_FOLDER = 13
    private const val IFILEDIALOG_SET_TITLE = 17
    private const val IFILEDIALOG_GET_RESULT = 20
    private const val IFILEOPEN_GET_RESULTS = 27
    private const val ISHELLITEM_GET_DISPLAY_NAME = 5
    private const val ISHELLITEMARRAY_GET_COUNT = 7
    private const val ISHELLITEMARRAY_GET_ITEM_AT = 8
}

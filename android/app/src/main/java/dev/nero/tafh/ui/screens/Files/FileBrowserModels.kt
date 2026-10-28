package dev.nero.tafh.ui.screens.Files

enum class ConnectionTab {
    Terminal,
    Files,
}

enum class FileEntryType {
    Directory,
    File,
    Symlink,
    Other,
}

enum class FileSort {
    Name,
    Size,
    Modified,
}

data class FileBrowserEntry(
    val name: String,
    val path: String,
    val type: FileEntryType,
    val sizeBytes: Long? = null,
    val modifiedAtText: String? = null,
)

data class UploadProgress(
    val fileName: String,
    val bytesWritten: Long,
    val totalBytes: Long,
    val fileIndex: Int,
    val totalFiles: Int,
) {
    val percent: Int
        get() =
            if (totalBytes > 0) (bytesWritten * 100 / totalBytes).toInt().coerceAtMost(100) else 0
}

data class FileBrowserUiState(
    val currentPath: String = "/",
    val entries: List<FileBrowserEntry> = emptyList(),
    val sort: FileSort = FileSort.Name,
    val isLoading: Boolean = false,
    val error: String? = null,
    val resolvedHomePath: String? = null,
    val uploadProgress: UploadProgress? = null,
)

fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes b"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} kb"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} mb"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0 * 1024.0))} gb"
    }
}

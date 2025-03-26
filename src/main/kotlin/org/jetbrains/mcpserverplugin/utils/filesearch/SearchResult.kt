package org.jetbrains.mcpserverplugin.utils.filesearch

import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Result of a file or directory search operation.
 * This sealed class provides type-safe handling of different search outcomes.
 */
sealed class SearchResult {
    /**
     * A file/directory was found
     */
    data class Found(
        val file: VirtualFile,
        val path: Path,
        val shortenedPath: String,
        val isDirectory: Boolean,
        val isInProject: Boolean
    ) : SearchResult()

    /**
     * Multiple matching files/directories were found
     */
    data class Multiple(
        val files: List<VirtualFile>,
        val shortenedPaths: List<String>
    ) : SearchResult()

    /**
     * No matching files/directories were found
     */
    data class NotFound(
        val error: String
    ) : SearchResult()
}
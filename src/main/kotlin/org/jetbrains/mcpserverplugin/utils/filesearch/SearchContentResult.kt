package org.jetbrains.mcpserverplugin.utils.filesearch

import com.intellij.openapi.vfs.VirtualFile

/**
 * Result of a content search operation.
 * This sealed class allows for different search result types - multi-file results and single-file
 * line-based results (similar to grep).
 */
sealed class SearchContentResult {
    /**
     * Basic properties common to all search result types
     */
    abstract val searchPath: String?
    abstract val searchPathCorrected: Boolean
    
    /**
     * Multi-file search result, containing a list of files matching the search criteria.
     *
     * @property files List of files containing the search pattern
     * @property shortenedPaths User-friendly shortened paths for the matched files
     * @property searchPath The directory path that was searched, if specified
     * @property searchPathCorrected Whether the search path was corrected during search
     * @property limitReached Whether the results were limited by maxResults
     */
    data class MultiFile(
        val files: List<VirtualFile>,
        val shortenedPaths: List<String>,
        override val searchPath: String? = null,
        override val searchPathCorrected: Boolean = false,
        val limitReached: Boolean = false
    ) : SearchContentResult()
    
    /**
     * Single-file search result, containing the matching lines in a specific file.
     * This is similar to grep command-line output.
     *
     * @property file The file that was searched
     * @property shortenedPath User-friendly shortened path of the file
     * @property matchingLines Lines in the file that match the search pattern
     * @property lineNumbers Line numbers corresponding to the matching lines
     * @property searchPath The path that was searched
     * @property searchPathCorrected Whether the search path was corrected
     */
    data class SingleFile(
        val file: VirtualFile,
        val shortenedPath: String,
        val matchingLines: List<String>,
        val lineNumbers: List<Int>,
        override val searchPath: String?,
        override val searchPathCorrected: Boolean
    ) : SearchContentResult()
    
    /**
     * Empty result set - no matches found.
     *
     * @property searchPath The path that was searched
     * @property searchPathCorrected Whether the search path was corrected
     */
    data class Empty(
        override val searchPath: String?,
        override val searchPathCorrected: Boolean
    ) : SearchContentResult()
}
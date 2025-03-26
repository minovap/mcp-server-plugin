package org.jetbrains.mcpserverplugin.utils.filesearch

/**
 * Result of reading file content.
 */
data class FileContent(
    val lines: List<String>,
    val offset: Int,
    val limit: Int,
    val totalLines: Int,
    val shortenedPath: String,
    val pathCorrected: Boolean
)
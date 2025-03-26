package org.jetbrains.mcpserverplugin.utils.filesearch

import com.intellij.openapi.vfs.LocalFileSystem

/**
 * A unified file search utility for IntelliJ IDEA projects.
 * Provides simplified file operations for the MCP server plugin.
 * 
 * This class serves as a central point for all file-related operations:
 * - Finding files and directories by path
 * - Listing directory contents
 * - Reading file contents
 * - Pattern matching for file names
 * - Searching for text patterns within files
 * 
 * It handles complexities like:
 * - Path correction and normalization
 * - External library files vs. project files
 * - Excluded files
 * - Fallback strategies when files can't be found exactly
 */
class FileSearch {
    val fileSystem = LocalFileSystem.getInstance()
}
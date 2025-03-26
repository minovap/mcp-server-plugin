package org.jetbrains.mcpserverplugin.utils.filesearch

/**
 * Directory entry for listing directory contents.
 * Represents a file or directory within a directory listing.
 * 
 * @property name The name of the file or directory (directories include trailing slash)
 * @property isDirectory Whether this entry is a directory (true) or file (false)
 */
data class DirectoryEntry(val name: String, val isDirectory: Boolean)
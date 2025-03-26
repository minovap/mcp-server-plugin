package org.jetbrains.mcpserverplugin.utils.filesearch

import com.intellij.openapi.project.Project
import org.jetbrains.mcpserverplugin.utils.LogCollector

/**
 * Find a file by path.
 * Searches for a file using the specified path, with smart matching if the exact path isn't found.
 */
fun FileSearch.findFile(project: Project, path: String, logs: LogCollector = LogCollector()): SearchResult {
    return findByPath(project, path, false, logs)
}
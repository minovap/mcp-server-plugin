package org.jetbrains.mcpserverplugin.utils.filesearch

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import org.jetbrains.mcpserverplugin.utils.LogCollector

/**
 * Find a directory by path.
 * Searches for a directory using the specified path, with smart matching if the exact path isn't found.
 * Will return NotFound if the directory is excluded from the project, unless it's outside the project directory.
 */
fun FileSearch.findDirectory(project: Project, path: String, logs: LogCollector = LogCollector()): SearchResult {
    val result = findByPath(project, path, true, logs)
    
    // Check if the directory is excluded from the project
    if (result is SearchResult.Found) {
        // Skip the exclusion check for files outside the project directory
        // If the file is explicitly requested and is outside the project, we should allow access to it
        val isInProjectDir = result.isInProject
        
        if (isInProjectDir && !isFileIncludedInProject(result.file, project)) {
            logs.info("Directory is excluded from the project: $path")
            return SearchResult.NotFound("Directory is excluded from the project: $path")
        }
    }
    
    return result
}
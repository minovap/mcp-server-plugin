package org.jetbrains.mcpserverplugin.utils.filesearch

import com.intellij.openapi.project.Project
import org.jetbrains.mcpserverplugin.utils.LogCollector
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Lists contents of a directory with optional pattern filtering.
 */
fun FileSearch.listDirectory(
    project: Project,
    dirPath: String,
    ignorePatterns: List<String>? = null,
    logs: LogCollector = LogCollector()
): Pair<List<DirectoryEntry>, String?> {
    // First find the directory
    val searchResult = findDirectory(project, dirPath, logs)
    
    when (searchResult) {
        is SearchResult.Found -> {
            val dir = searchResult.path
            val shortenedPath = searchResult.shortenedPath
            val correctedPath = if (shortenedPath != dirPath) shortenedPath else null
            
            try {
                // Get directory entries
                val entries = mutableListOf<DirectoryEntry>()
                var ignoredCount = 0
                
                // Use the utility function to list files
                listFilesInDirectory(dir, logs).use { stream ->
                    stream
                        .filter { pathEntry -> 
                            // Apply ignore patterns if specified
                            val fileName = pathEntry.path.fileName.toString() 
                            val shouldIgnore = ignorePatterns?.any { pattern ->
                                matchesGlobPattern(fileName, pattern)
                            } ?: false
                            
                            // Skip if the path is excluded from the project
                            val virtualFile = fileSystem.findFileByNioFile(pathEntry.path)
                            val isExcluded = virtualFile != null && !isFileIncludedInProject(virtualFile, project)
                            
                            if (shouldIgnore || isExcluded) {
                                ignoredCount++
                                false
                            } else {
                                true
                            }
                        }
                        .forEach { pathEntry ->
                            val name = pathEntry.path.fileName.toString()
                            
                            entries.add(DirectoryEntry(
                                // Format directories with trailing slash like ls command
                                if (pathEntry.isDirectory) "$name/" else name, 
                                pathEntry.isDirectory
                            ))
                        }
                }
                
                // Log results
                logs.info("Listed ${entries.size} entries in directory: $dir")
                if (ignoredCount > 0) {
                    logs.info("Ignored $ignoredCount entries based on patterns")
                }
                
                // Return sorted entries (directories first, then files alphabetically)
                return Pair(entries.sortedWith(
                    compareByDescending<DirectoryEntry> { it.isDirectory }
                        .thenBy { it.name }
                ), correctedPath)
                
            } catch (e: Exception) {
                logs.error("Error listing directory: ${e.message}")
                return Pair(emptyList(), correctedPath)
            }
        }
        is SearchResult.Multiple -> {
            logs.error("Multiple matching directories found")
            return Pair(emptyList(), null)
        }
        is SearchResult.NotFound -> {
            logs.info("Directory not found: $dirPath")
            return Pair(emptyList(), null)
        }
    }
}
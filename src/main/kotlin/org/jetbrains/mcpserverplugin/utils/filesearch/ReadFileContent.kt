package org.jetbrains.mcpserverplugin.utils.filesearch

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.toNioPathOrNull
import org.jetbrains.mcpserverplugin.utils.LogCollector
import java.nio.file.Files

/**
 * Read content from a file with optional offset and limit.
 */
fun FileSearch.readFileContent(
    project: Project,
    path: String,
    offset: Int? = null,
    limit: Int? = null,
    maxLineLength: Int = 2000,
    defaultLimit: Int = 1000,
    logs: LogCollector = LogCollector()
): Result<FileContent> {
    // First, find the file
    val searchResult = findFile(project, path, logs)
    
    return when (searchResult) {
        is SearchResult.Found -> {
            val virtualFile = searchResult.file
            val pathCorrected = searchResult.shortenedPath != path
            
            try {
                // Try reading with VirtualFile first
                logs.info("Reading file content from ${virtualFile.path}")
                val allLines = try {
                    virtualFile.inputStream.bufferedReader().use { it.readLines() }
                } catch (e: Exception) {
                    // Fall back to NIO if VirtualFile reading fails
                    logs.warn("VirtualFile reading failed, trying NIO: ${e.message}")
                    val nioPath = virtualFile.toNioPathOrNull()
                    
                    if (nioPath != null && Files.exists(nioPath) && Files.isRegularFile(nioPath)) {
                        Files.readAllLines(nioPath)
                    } else {
                        throw e // Rethrow if NIO path doesn't exist or isn't a file
                    }
                }
                
                // Calculate effective offset and limit
                val startIndex = offset?.coerceAtLeast(0) ?: 0
                val effectiveLimit = limit ?: defaultLimit
                
                // Check if offset is beyond file size before attempting to create subList
                if (startIndex >= allLines.size) {
                    throw IllegalArgumentException("Offset too large: You requested to start reading from line ${startIndex + 1}, but the file only has ${allLines.size} lines")
                }
                
                val endIndex = (startIndex + effectiveLimit).coerceAtMost(allLines.size)
                
                logs.info("Processing lines ${startIndex+1}-${endIndex} of ${allLines.size}")
                
                // Get lines within range and truncate if needed
                val lines = allLines.subList(startIndex, endIndex).map { line ->
                    if (line.length > maxLineLength) {
                        line.substring(0, maxLineLength) + "... (truncated)"
                    } else {
                        line
                    }
                }
                
                Result.success(FileContent(
                    lines = lines,
                    offset = startIndex,
                    limit = effectiveLimit,
                    totalLines = allLines.size,
                    shortenedPath = searchResult.shortenedPath,
                    pathCorrected = pathCorrected
                ))
            } catch (e: Exception) {
                logs.warn("Error reading file content")
                Result.failure(e)
            }
        }
        is SearchResult.Multiple -> {
            logs.warn("Multiple matching files found")
            Result.failure(IllegalStateException(
                "Multiple files found matching '$path':\n${searchResult.shortenedPaths.joinToString("\n")}"
            ))
        }
        is SearchResult.NotFound -> {
            logs.warn("File not found: $path")
            Result.failure(IllegalStateException(searchResult.error))
        }
    }
}
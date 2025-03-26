package org.jetbrains.mcpserverplugin.utils

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.ProjectScope
import java.io.File
import java.nio.file.Paths

/**
 * Utility class for shortening file paths to make them more user-friendly
 */
object PathShortener {
    private val LOG = com.intellij.openapi.diagnostic.logger<PathShortener>()
    
    /**
     * Shortens a file path to the shortest unique representation relative to the project.
     * This function attempts to find the shortest version of the path that either:
     * 1. Is inside the project root (e.g., "src/index.js" instead of "/Users/user/project/src/index.js")
     * 2. Or is uniquely identifiable with the minimum number of parent directories
     *    (e.g., "blabla/hello.java" instead of "com/blabla/hello.java" if it's unique)
     * 
     * @param project The IntelliJ project
     * @param longPath The full path to the file to be shortened
     * @param logCollector Optional log collector for detailed logging of the shortening process
     * @return The shortened path that uniquely identifies the file, or the original path if it can't be shortened
     */
    fun shortenFilePath(project: Project, longPath: String, logCollector: LogCollector = LogCollector()): String {
        // Convert to normalized path using File to handle different separators consistently
        val normalizedPath = File(longPath).path
        // Initialize with the full path as a fallback result
        var result = normalizedPath
        
        try {
            runReadAction {
                // Get project directory
                val projectDir = project.guessProjectDir()?.toNioPathOrNull()
                if (projectDir != null) {
                    // Check if the path is inside the project
                    val filePath = Paths.get(normalizedPath)
                    // Don't handle the case where filePath is exactly the project dir
                    // This prevents returning an empty string
                    if (filePath.startsWith(projectDir) && !filePath.equals(projectDir)) {
                        // If inside project, make it relative to project root
                        result = projectDir.relativize(filePath).toString()
                        return@runReadAction
                    }
                    if (filePath == projectDir) {
                        // If exactly the project dir, we use "./" to make that clear
                        result = "./"
                        return@runReadAction
                    }
                    
                    // Extract the filename
                    val fileName = filePath.fileName.toString()
                    
                    // Use FilenameIndex to find all files with the same name
                    val everythingScope = ProjectScope.getEverythingScope(project)
                    val filesWithName = FilenameIndex.getVirtualFilesByName(fileName, everythingScope)
                    
                    logCollector.info("shortenFilePath: Found ${filesWithName.size} files named '$fileName' using FilenameIndex")
                    
                    // If there are no files with this name, keep the original path
                    if (filesWithName.isEmpty()) {
                        return@runReadAction // Keep the original path
                    }
                    
                    if (filesWithName.size == 1) {
                        // If there's only one file with this name, we'll still keep x segments long
                        // First set the result to just the filename (we'll expand it later if needed)
                        result = fileName
                        // Don't return immediately so we can apply the segment expansion logic below
                    }

                    // Multiple files with the same name exist - try to find a unique short path
                    // Split the path into segments
                    val segments = normalizedPath.replace("\\", "/").split("/")
                    logCollector.info("shortenFilePath: Path segments: ${segments.joinToString(", ")}")

                    // Check if the path contains a '!' character (indicating an archive like JAR)
                    val hasBangCharacter = segments.any { it.contains("!") }
                    val bangSegmentIndex = if (hasBangCharacter) segments.indexOfLast { it.contains("!") } else -1
                    val archiveSegmentIndex = if (bangSegmentIndex >= 0) {
                        segments.indexOfLast { it.contains("!") && !it.startsWith("!") }
                    } else -1

                    if (hasBangCharacter) {
                        logCollector.info("shortenFilePath: Found '!' character in path segment at index $bangSegmentIndex")
                        if (archiveSegmentIndex >= 0) {
                            logCollector.info("shortenFilePath: Found archive segment at index $archiveSegmentIndex")
                        }
                    }

                    // Start with just the filename, then add previous path segments until we have met all criteria:
                    // 1. The path must be unique
                    // 2. It should have at least 3 segments if possible

                    var foundResult = false
                    for (i in 1..segments.size) {
                        // Take the last i segments (including the filename)
                        val shortPath = segments.takeLast(i).joinToString("/")
                        val shortPathSegments = shortPath.split("/")

                        // Check if this shortened path would be unique
                        var isUnique = true
                        var matchesTargetFile = false

                        for (candidateFile in filesWithName) {
                            val candidatePath = candidateFile.path.replace("\\", "/")

                            // Check if this is our target file
                            if (candidatePath == normalizedPath) {
                                matchesTargetFile = true
                                continue
                            }

                            // Check if any other file ends with our shortPath
                            if (candidatePath.endsWith("/" + shortPath) ||
                                candidatePath == shortPath) {
                                isUnique = false
                                break
                            }
                        }

                        // Check if we need to continue for JAR paths
                        val includesArchiveSegment = if (archiveSegmentIndex >= 0) {
                            val segmentWithArchive = segments[archiveSegmentIndex]
                            shortPath.contains(segmentWithArchive)
                        } else true

                        // Check if we have enough segments for readability
                        val hasEnoughSegments = shortPathSegments.size >= 3 || shortPathSegments.size >= segments.size

                        // Track if we found a valid result for logging
                        var reason = ""

                        if (!isUnique) {
                            reason = "not unique"
                        } else if (!matchesTargetFile) {
                            reason = "doesn't match target file"
                        } else if (!includesArchiveSegment) {
                            reason = "missing archive segment"
                        } else if (!hasEnoughSegments && i < segments.size) {
                            reason = "needs more segments for readability"
                        } else {
                            // All criteria met
                            result = shortPath
                            foundResult = true
                            // logCollector.info("shortenFilePath: Found a suitable path: $result (iteration $i)")
                            break
                        }
                    }

                    if (archiveSegmentIndex >= 0) {
                        if (result.endsWith("!")) {
                            result += "/"
                        }
                    }
                    
                    if (!foundResult) {
                        // If no ideal result found, use the original path
                        logCollector.info("shortenFilePath: No ideal path found, using last attempted path: $result")
                    }
                }
            }
        } catch (e: Exception) {
            LOG.error("shortenFilePath: Error shortening file path", e)
            // Return the original path if there's an error
            return normalizedPath
        }

        // Use forward slashes for consistency across platforms
        return result.replace(File.separatorChar, '/')
    }
}
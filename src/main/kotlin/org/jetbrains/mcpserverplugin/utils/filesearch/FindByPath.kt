package org.jetbrains.mcpserverplugin.utils.filesearch

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.*
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import org.jetbrains.mcpserverplugin.utils.LogCollector
import org.jetbrains.mcpserverplugin.utils.PathShortener
import java.nio.file.Paths

/**
 * Find by path implementation for both files and directories.
 * Used internally by findFile and findDirectory.
 */
internal fun FileSearch.findByPath(project: Project, path: String, findDirectories: Boolean, logs: LogCollector): SearchResult {
    return runReadAction {
        try {
            val projectDir = project.guessProjectDir()?.toNioPathOrNull()
                ?: return@runReadAction SearchResult.NotFound("Project directory not found")
            
            val type = if (findDirectories) "directory" else "file"
            logs.info("Looking for $type: $path in project dir: $projectDir")
            
            // Handle special case for directories
            if (findDirectories && (path.isEmpty() || path == "." || path == "./" || path == "/")) {
                val projectVirtualDir = fileSystem.findFileByNioFile(projectDir)
                if (projectVirtualDir != null && projectVirtualDir.isDirectory) {
                    val shortenedPath = PathShortener.shortenFilePath(project, projectVirtualDir.path, logs)
                    return@runReadAction SearchResult.Found(
                        projectVirtualDir,
                        projectDir,
                        shortenedPath,
                        true,
                        true
                    )
                }
            }

            // Trim / at end of path
            val trimmedPath = path.trimEnd('/')

            // Let's check if the path contains !, if so we should handle it in two parts, first find the file (the part before the !) then find the file/dir inside the archive (the part after the !)
            if ("!" in trimmedPath) {
                // Search by name
                val fileName = Paths.get(trimmedPath).fileName?.toString() ?: ""
                val scope = ProjectScope.getEverythingScope(project)
                val candidates = FilenameIndex.getVirtualFilesByName(fileName, scope)
                    .filter { it.isDirectory == findDirectories }

                // Find the best match by comparing paths
                val normalizedSearchPath = trimmedPath.replace("\\", "/").lowercase()
                val bestMatch = candidates.maxByOrNull { candidate ->
                    val candidatePath = candidate.path.replace("\\", "/").lowercase()
                    val similarity = calculatePathSimilarity(normalizedSearchPath, candidatePath)

                    similarity
                }

                if (bestMatch != null) {
                    val parts = bestMatch.path.split("!", limit = 2)
                    val filePath = parts[0]

                    var insidePath = parts.getOrNull(1) ?: ""
                    if (insidePath.length == 0) { insidePath = "/" }

                    val archiveUrl = StandardFileSystems.JAR_PROTOCOL_PREFIX + filePath + "!" + insidePath;
                    val archiveVirtualFile = VirtualFileManager.getInstance().findFileByUrl(archiveUrl)

                    return@runReadAction if (archiveVirtualFile != null) {
                        // Return SearchResult.Found with the appropriate values
                        val resolvedPath = archiveVirtualFile.toNioPathOrNull() ?: Paths.get(filePath + "!" + insidePath)
                        val shortenedPath = PathShortener.shortenFilePath(project, archiveVirtualFile.path, logs)
                        val isInProject = isInProjectDirectory(archiveVirtualFile, projectDir)
                        
                        SearchResult.Found(
                            archiveVirtualFile,
                            resolvedPath,
                            shortenedPath,
                            archiveVirtualFile.isDirectory,
                            isInProject
                        )
                    } else {
                        logs.info("Archive file could not be opened: $filePath")
                        SearchResult.NotFound("Archive file could not be opened: $filePath")
                    }
                }
            }

            // Check for exact path first
            val exactPath = this.resolveRel(projectDir, trimmedPath)
            val exactFile = fileSystem.findFileByNioFile(exactPath)
            
            if (exactFile != null && exactFile.isDirectory == findDirectories) {
                // Check if excluded
                if (!isFileIncludedInProject(exactFile, project)) {
                    return@runReadAction SearchResult.NotFound("$type is excluded from project: $trimmedPath")
                }
                
                // Found exact match
                val shortenedPath = PathShortener.shortenFilePath(project, exactFile.path, logs)
                return@runReadAction SearchResult.Found(
                    exactFile,
                    exactPath,
                    shortenedPath,
                    findDirectories,
                    true
                )
            }

            // Try to find by name
            val fileName = Paths.get(trimmedPath).fileName?.toString() ?: ""
            if (fileName.isEmpty()) {
                return@runReadAction SearchResult.NotFound("Invalid path: $trimmedPath")
            }
            
            // Search by name
            val scope = ProjectScope.getEverythingScope(project)
            val candidates = FilenameIndex.getVirtualFilesByName(fileName, scope)
                .filter { it.isDirectory == findDirectories }
            
            if (candidates.isEmpty()) {
                return@runReadAction SearchResult.NotFound("$type not found: $trimmedPath")
            }
            
            if (candidates.size == 1) {
                val file = candidates.first()
                val resolvedPath = file.toNioPathOrNull() ?: exactPath
                val shortenedPath = PathShortener.shortenFilePath(project, file.path, logs)
                val isInProject = isInProjectDirectory(file, projectDir)
                
                return@runReadAction SearchResult.Found(
                    file,
                    resolvedPath,
                    shortenedPath,
                    findDirectories,
                    isInProject
                )
            }
            
            // Multiple matches, find the best match based on path similarity
            logs.info("Found ${candidates.size} candidates for $trimmedPath, selecting best match")
            
            // Find the best match by comparing paths
            val normalizedSearchPath = trimmedPath.replace("\\", "/").lowercase()
            val bestMatch = candidates.maxByOrNull { candidate ->
                val candidatePath = candidate.path.replace("\\", "/").lowercase()
                val similarity = calculatePathSimilarity(normalizedSearchPath, candidatePath)
                logs.info("Candidate $candidatePath has similarity score: $similarity")
                similarity
            }
            
            if (bestMatch != null) {
                val resolvedPath = bestMatch.toNioPathOrNull() ?: exactPath
                val shortenedPath = PathShortener.shortenFilePath(project, bestMatch.path, logs)
                val isInProject = isInProjectDirectory(bestMatch, projectDir)
                
                logs.info("Selected best match: ${bestMatch.path}")
                return@runReadAction SearchResult.Found(
                    bestMatch,
                    resolvedPath,
                    shortenedPath,
                    findDirectories,
                    isInProject
                )
            } else {
                // This should not happen as candidates is not empty at this point
                val shortenedPaths = candidates.map { PathShortener.shortenFilePath(project, it.path, logs) }
                return@runReadAction SearchResult.Multiple(candidates, shortenedPaths)
            }
            
        } catch (e: Exception) {
            logs.error("Error finding ${if (findDirectories) "directory" else "file"}", e)
            return@runReadAction SearchResult.NotFound("Search error: ${e.message}")
        }
    }
}
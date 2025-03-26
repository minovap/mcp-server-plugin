package org.jetbrains.mcpserverplugin.utils.filesearch

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.mcpserverplugin.utils.LogCollector
import org.jetbrains.mcpserverplugin.utils.PathShortener

/**
 * Search for files matching a glob pattern, optionally limited to a directory path.
 *
 * @param project The IntelliJ project
 * @param pattern The glob pattern to match against files
 * @param path Optional path to restrict search to a directory
 * @param maxResults Maximum number of results to return (default 100)
 * @param logs Logger for collecting diagnostic information
 * @return SearchContentResult containing found files and metadata
 */
fun FileSearch.searchByGlob(
    project: Project,
    pattern: String,
    path: String? = null,
    maxResults: Int = 100,
    logs: LogCollector = LogCollector()
): SearchContentResult {
    // Get project base path for filtering
    val projectBasePath = project.basePath
    val normalizedProjectPath = (projectBasePath ?: "").replace("\\", "/")
    
    // Store path and status info
    var searchPathCorrected = false
    var searchPathValue: String? = null
    var limitReached = false
    
    // Collect project files and external files separately
    val projectFiles = mutableListOf<VirtualFile>()
    val externalFiles = mutableListOf<VirtualFile>()
    
    // Process path if specified
    val searchEverywhere = path == null || path == "/"
    var directoryToSearch: VirtualFile? = null
    
    if (!searchEverywhere) {
        // Find directory to search in
        val dirResult = findDirectory(project, path!!, logs)
        
        when (dirResult) {
            is SearchResult.Found -> {
                directoryToSearch = dirResult.file
                searchPathValue = dirResult.shortenedPath
                searchPathCorrected = dirResult.shortenedPath != path
                logs.info("Using directory for search: ${dirResult.file.path}")
            }
            is SearchResult.Multiple -> {
                logs.warn("Multiple matching directories found for path: $path")
                return SearchContentResult.MultiFile(
                    files = emptyList(),
                    shortenedPaths = dirResult.shortenedPaths,
                    searchPath = path,
                    searchPathCorrected = false,
                    limitReached = false
                )
            }
            is SearchResult.NotFound -> {
                logs.warn("Directory not found: $path")
                return SearchContentResult.MultiFile(
                    files = emptyList(),
                    shortenedPaths = emptyList(),
                    searchPath = path,
                    searchPathCorrected = false,
                    limitReached = false
                )
            }
        }
    } else {
        // Use project root for search
        directoryToSearch = project.guessProjectDir()
    }
    
    if (directoryToSearch == null) {
        logs.error("No valid directory found for search")
        return SearchContentResult.MultiFile(
            files = emptyList(),
            shortenedPaths = emptyList(),
            searchPath = path,
            searchPathCorrected = false,
            limitReached = false
        )
    }
    
    // PHASE 1: Search in project files
    logs.info("Searching project files with pattern: $pattern")
    ReadAction.run<Throwable> {
        val visitor = object : VirtualFileVisitor<Void>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (!file.isDirectory) {
                    // Get path relative to search directory
                    val relativePath = file.path.substring(directoryToSearch.path.length).trimStart('/')
                    
                    // Check if the file matches the pattern
                    if (matchesGlobPattern(relativePath, pattern)) {
                        // Check if it's a project file or external file
                        val filePath = file.path.replace("\\", "/")
                        val isProjectFile = normalizedProjectPath.isNotEmpty() && 
                                           filePath.startsWith(normalizedProjectPath)
                        
                        // Skip excluded files if they're in the project
                        if (isProjectFile) {
                            // Check if file is excluded from the project
                            val isExcluded = !isFileIncludedInProject(file, project)
                            if (isExcluded) {
                                logs.info("Skipping excluded file: ${file.path}")
                                return@visitFile true // Continue to next file
                            }
                            projectFiles.add(file)
                        } else {
                            externalFiles.add(file)
                        }
                        
                        // Check if we've reached the limit (prioritizing project files)
                        val totalUsefulFiles = projectFiles.size + 
                            Math.min(externalFiles.size, Math.max(0, maxResults - projectFiles.size))
                        
                        if (totalUsefulFiles >= maxResults) {
                            limitReached = true
                            return false // Stop visiting
                        }
                    }
                }
                return true // Continue visiting
            }
        }
        
        // Visit all files recursively
        VfsUtilCore.visitChildrenRecursively(directoryToSearch, visitor)
    }
    
    logs.info("Project search completed: found ${projectFiles.size} project files and ${externalFiles.size} external files")
    
    // PHASE 2: If needed, search in libraries using a different approach
    if (projectFiles.size < maxResults && searchEverywhere) {
        logs.info("Searching in libraries with pattern: $pattern")
        
        // Extract a simple name part for more efficient searching if possible
        val simpleNamePart = extractSimpleNamePart(pattern)
        
        if (simpleNamePart != null) {
            // Use FilenameIndex for faster library searching
            ReadAction.run<Throwable> {
                val scope = GlobalSearchScope.allScope(project)
                // Get candidate files to filter
                val candidates = if (simpleNamePart.startsWith(".")) {
                    // If we have an extension, search by extension
                    FilenameIndex.getAllFilesByExt(project, simpleNamePart.substring(1), scope)
                } else if (!simpleNamePart.contains("*") && !simpleNamePart.contains("?") && 
                           !simpleNamePart.contains("{") && !simpleNamePart.contains("}")) {
                    // If simple name with no glob characters, use direct name search
                    FilenameIndex.getVirtualFilesByName(simpleNamePart, scope)
                } else {
                    // For glob patterns, we need to get all files and filter
                    logs.info("Using comprehensive search for glob pattern: $pattern")
                    
                    // For efficiency, try to extract extension if pattern ends with .ext
                    val extension = when {
                        // For patterns like *.js
                        pattern.startsWith("*.") && !pattern.substring(2).contains("*") && 
                        !pattern.substring(2).contains("?") && !pattern.substring(2).contains("{") -> 
                            pattern.substring(2)
                            
                        // For patterns like *.{js,ts}, use the first extension
                        pattern.startsWith("*.") && pattern.contains("{") -> 
                            pattern.substringAfter("{").substringBefore(",").substringBefore("}")
                            
                        // No usable extension pattern
                        else -> null
                    }
                    
                    if (extension != null && extension.isNotEmpty()) {
                        logs.info("Searching by extension: $extension")
                        FilenameIndex.getAllFilesByExt(project, extension, scope)
                    } else {
                        // Get all files in the project (more expensive, but necessary for complex patterns)
                        logs.info("Getting all files in project scope for pattern matching")
                        FilenameIndex.getAllFilesByExt(project, "", scope)
                    }
                }
                
                // Filter candidates by pattern
                for (file in candidates) {
                    if (file.isDirectory) continue
                    
                    // Skip project files that we already checked
                    val filePathStr = file.path.replace("\\", "/")
                    val isInProject = normalizedProjectPath.isNotEmpty() && 
                                     filePathStr.startsWith(normalizedProjectPath)
                    
                    // Skip excluded files if they're in the project
                    if (isInProject) {
                        // Check if file is excluded from the project
                        val isExcluded = !isFileIncludedInProject(file, project)
                        if (isExcluded) {
                            logs.info("Skipping excluded file in library search: ${file.path}")
                        }
                        continue  // Skip project files in this phase
                    }
                    
                    // Match against pattern - check both file name and full path
                    val fileName = file.name
                    val relativePath = file.path.replace("\\", "/").substringAfter(normalizedProjectPath).trimStart('/')
                    
                    // Check if file is in project and excluded
                    val filePathName = file.path.replace("\\", "/")
                    val isInProjectDir = normalizedProjectPath.isNotEmpty() && 
                                        filePathName.startsWith(normalizedProjectPath)
                    
                    if (isInProjectDir) {
                        // Check if file is excluded from the project
                        val isExcluded = !isFileIncludedInProject(file, project)
                        if (isExcluded) {
                            logs.info("Skipping excluded file in index: ${file.path}")
                            continue
                        }
                    }
                    
                    // Try to match against the full pattern
                    if (matchesGlobPattern(fileName, pattern) || matchesGlobPattern(relativePath, pattern)) {
                        if (!externalFiles.contains(file)) {
                            externalFiles.add(file)
                            
                            // Check if we've reached the limit
                            val totalFiles = projectFiles.size + externalFiles.size
                            if (totalFiles >= maxResults) {
                                limitReached = true
                                break
                            }
                        }
                    }
                }
            }
            
            logs.info("Library search completed: found ${externalFiles.size} external files")
        }
    }
    
    // Sort project files by modification time (newest first)
    val sortedProjectFiles = projectFiles.sortedByDescending { file ->
        try { file.timeStamp } catch (e: Exception) { 0L }
    }
    
    // Calculate how many external files we need
    val remainingSlots = maxResults - sortedProjectFiles.size
    
    // Sort and include external files if needed
    val sortedFiles = if (remainingSlots > 0 && externalFiles.isNotEmpty()) {
        // Sort external files by modification time
        val sortedExternalFiles = externalFiles.sortedByDescending { file ->
            try { file.timeStamp } catch (e: Exception) { 0L }
        }
        
        // Combine project files with the needed external files
        sortedProjectFiles + sortedExternalFiles.take(remainingSlots)
    } else {
        // If no external files needed, just use project files
        sortedProjectFiles
    }
    
    // Create shortened paths for the final results
    val finalShortenedPaths = sortedFiles.map { file ->
        PathShortener.shortenFilePath(project, file.path, logs) 
    }
    
    return SearchContentResult.MultiFile(
        files = sortedFiles,
        shortenedPaths = finalShortenedPaths,
        searchPath = searchPathValue,
        searchPathCorrected = searchPathCorrected,
        limitReached = limitReached
    )
}

/**
 * Extract a simple name component from a glob pattern for FilenameIndex search.
 * Can now handle more glob pattern formats by returning partial information that will be
 * filtered later.
 *
 * @return The extracted name part, or null if the pattern is too complex
 */
private fun extractSimpleNamePart(pattern: String): String? {
    // Extract just the filename part if there are directory separators
    val filenamePart = if (pattern.contains("/")) {
        pattern.substringAfterLast("/")
    } else {
        pattern
    }
    
    // Handle common glob pattern formats
    
    // For *.ext pattern, return the extension
    if (filenamePart.startsWith("*.") && !filenamePart.substring(2).contains("*")) {
        return "." + filenamePart.substring(2)
    }
    
    // For patterns with { } extensions, extract the first extension
    if (filenamePart.startsWith("*.") && filenamePart.contains("{") && filenamePart.contains("}")) {
        val extension = filenamePart.substringAfter("{").substringBefore(",").substringBefore("}")
        if (extension.isNotEmpty()) {
            return "." + extension
        }
    }
    
    // If the pattern is a specific filename without wildcards
    if (!filenamePart.contains("*") && !filenamePart.contains("?") && 
        !filenamePart.contains("{") && !filenamePart.contains("}")) {
        return filenamePart
    }
    
    // For glob patterns like app*.js, extract a static prefix if possible
    if (filenamePart.contains("*") && !filenamePart.startsWith("*")) {
        val prefix = filenamePart.substringBefore("*")
        if (prefix.length >= 3) {  // Only use prefix if it's substantial enough
            return prefix
        }
    }
    
    // Return the pattern itself for filtering later, even if it contains wildcards
    // This allows the caller to handle complex patterns by getting files to filter
    return filenamePart
}
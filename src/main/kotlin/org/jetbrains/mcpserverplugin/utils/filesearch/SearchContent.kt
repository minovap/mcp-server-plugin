package org.jetbrains.mcpserverplugin.utils.filesearch

import com.intellij.find.FindModel
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.usages.FindUsagesProcessPresentation
import com.intellij.usages.UsageViewPresentation
import com.intellij.util.Processor
import com.intellij.usageView.UsageInfo
import org.jetbrains.mcpserverplugin.utils.LogCollector
import org.jetbrains.mcpserverplugin.utils.PathShortener

/**
 * Search for text pattern within files.
 * Uses IntelliJ's built-in search APIs for efficient content searching.
 *
 * @param project The IntelliJ project
 * @param pattern The regex pattern to search for within file contents
 * @param path Optional path to limit the search scope. If null or "/", searches everywhere.
 * @param includePattern Optional glob pattern to filter which files to search in (e.g., "*.js")
 * @param maxResults Maximum number of results to return (default 100)
 * @param caseSensitive Whether the search should be case-sensitive (default false)
 * @param logs Logger for collecting diagnostic information
 * @return SearchContentResult containing found files and metadata
 *
 * Usage example:
 * ```kotlin
 * val result = fileSearch.searchContent(
 *     project = project,
 *     pattern = "function\\s+myFunction",
 *     path = "src/main/js",
 *     includePattern = "*.js"
 * )
 * 
 * result.shortenedPaths.forEach { println(it) }
 * ```
 */
fun FileSearch.searchContent(
    project: Project,
    pattern: String,
    path: String? = null,
    includePattern: String? = null,
    maxResults: Int = 100,
    caseSensitive: Boolean = false,
    logs: LogCollector = LogCollector()
): SearchContentResult {
    logs.info("Searching for pattern: $pattern")
    if (path != null) logs.info("In path: $path")
    if (includePattern != null) logs.info("File pattern: $includePattern")
    
    // Configure the FindModel for searching
    val findModel = FindModel()
    findModel.stringToFind = pattern
    findModel.isCaseSensitive = caseSensitive
    findModel.isWholeWordsOnly = false
    findModel.isRegularExpressions = true
    findModel.isWithSubdirectories = true
    
    // Use defaults for both phases of the search
    // We'll modify these parameters in each phase
    
    // Configure the model to search in libraries
    findModel.isProjectScope = false
    findModel.customScope = GlobalSearchScope.allScope(project)
    
    // These settings are required to search through libraries
    findModel.isWithSubdirectories = true
    
    // Store path and status info
    var searchPathCorrected = false
    var searchPathValue: String? = null
    var limitReached = false
    
    // Process path if specified
    val searchEverywhere = path == null || path == "/"
    var searchScope: GlobalSearchScope? = null
    
    if (!searchEverywhere) {
        // Find directory to search in
        val dirResult = findDirectory(project, path!!, logs)
        
        when (dirResult) {
            is SearchResult.Found -> {
                // Store the directory path (used for both search phases)
                val searchDirectory = dirResult.file.path
                findModel.directoryName = searchDirectory
                findModel.isProjectScope = false
                searchPathValue = dirResult.shortenedPath
                searchPathCorrected = dirResult.shortenedPath != path
                
                logs.info("Using directory for search: $searchDirectory")
                
                // Create a directory-specific scope but include libraries
                try {
                    // Using filesWithLibrariesScope to include libraries in this directory
                    searchScope = GlobalSearchScope.filesWithLibrariesScope(project, setOf(dirResult.file))
                } catch (e: Exception) {
                    logs.warn("Failed to create directory scope: ${e.message}")
                    // Fall back to default approach
                    searchScope = null
                }
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
                // Try to fall back to single file search if it's a file instead of a directory
                val fileResult = findFile(project, path, logs)
                if (fileResult is SearchResult.Found) {
                    // We found a single file, just search within this file directly
                    logs.info("Found file instead of directory, performing grep-like search: $path")
                    
                    // Use our readFileContent function to handle edge cases
                    val contentResult = readFileContent(
                        project = project,
                        path = path,
                        // No offset, get all content
                        // Use a large limit to get all content
                        limit = Int.MAX_VALUE,
                        logs = logs
                    )
                    
                    if (contentResult.isSuccess) {
                        val content = contentResult.getOrNull()!!
                        
                        // Create regex pattern for searching
                        val regex = if (caseSensitive) {
                            pattern.toRegex()
                        } else {
                            pattern.toRegex(RegexOption.IGNORE_CASE)
                        }
                        
                        // Find matching lines with line numbers
                        val matchingLines = mutableListOf<String>()
                        val lineNumbers = mutableListOf<Int>()
                        
                        content.lines.forEachIndexed { index, line ->
                            if (regex.containsMatchIn(line)) {
                                matchingLines.add(line)
                                // Line numbers are 1-based for display, so add offset + 1
                                lineNumbers.add(content.offset + index + 1)
                            }
                        }
                        
                        if (matchingLines.isNotEmpty()) {
                            // File has matches, return SingleFile result
                            logs.info("Found ${matchingLines.size} matching lines in file: ${content.shortenedPath}")
                            return SearchContentResult.SingleFile(
                                file = fileResult.file,
                                shortenedPath = content.shortenedPath,
                                matchingLines = matchingLines,
                                lineNumbers = lineNumbers,
                                searchPath = content.shortenedPath,
                                searchPathCorrected = content.pathCorrected
                            )
                        } else {
                            logs.info("No matches found in file: ${content.shortenedPath}")
                            return SearchContentResult.Empty(
                                searchPath = content.shortenedPath,
                                searchPathCorrected = content.pathCorrected
                            )
                        }
                    } else {
                        val error = contentResult.exceptionOrNull()
                        logs.warn("Error reading file content: ${error?.message}")
                        // Fall back to empty results
                        return SearchContentResult.Empty(
                            searchPath = path,
                            searchPathCorrected = false
                        )
                    }
                } else {
                    // We didn't find a file either, so return empty results
                    return SearchContentResult.Empty(
                        searchPath = path,
                        searchPathCorrected = false
                    )
                }
            }
        }
    } else {
        // Search everywhere - create an allScope that includes libraries
        findModel.isProjectScope = false
        
        // Create a scope that explicitly includes libraries
        searchScope = GlobalSearchScope.allScope(project)
        logs.info("Search everywhere - using GlobalSearchScope.allScope including libraries")
    }
    
    // Set the custom scope directly
    if (searchScope != null) {
        findModel.customScope = searchScope
    }
    
    // Get project base path for filtering
    val projectBasePath = project.basePath
    val normalizedProjectPath = (projectBasePath ?: "").replace("\\", "/")
    
    // Create separate collections for project and external files
    val projectFiles = mutableListOf<VirtualFile>() 
    val externalFiles = mutableListOf<VirtualFile>()
    
    // Process the search results
    
    // Process files, classifying them as project or external,
    // but terminate early if we have enough total matches
    val processor = Processor<UsageInfo> { usageInfo ->
        val file = usageInfo.virtualFile ?: return@Processor true
        
        // Apply include pattern filtering if specified
        if (!includePattern.isNullOrBlank()) {
            // When checking against includePattern, we should consider the full path if the pattern has directories
            // First get the file path
            val filePath = file.path.replace("\\", "/")
            
            val relativePath = if (normalizedProjectPath.isNotEmpty() && filePath.startsWith(normalizedProjectPath)) {
                // Get path relative to project for project files
                filePath.substring(normalizedProjectPath.length).trimStart('/')
            } else {
                // For non-project files, just use the file name
                file.name
            }
            
            // Check if the file matches the include pattern
            val matches = matchesGlobPattern(relativePath, includePattern)
            
            if (!matches) {
                // Skip non-matching files
                return@Processor true
            }
        }
        
        // Classify file based on path
        val filePath = file.path.replace("\\", "/")
        val isProjectFile = normalizedProjectPath.isNotEmpty() && filePath.startsWith(normalizedProjectPath)
        
        // If path is specified, check if the file is within that path
        if (!searchEverywhere) {
            // We need to check if the file is actually within the specified directory
            val dirPath = findModel.directoryName?.replace("\\", "/")
            if (dirPath != null && !filePath.startsWith(dirPath)) {
                // File is not in the specified directory path, skip it
                if (projectFiles.size + externalFiles.size < 3) {
                    logs.info("SKIPPING file not in path: ${file.path} (not in ${dirPath})")
                }
                return@Processor true
            } else if (projectFiles.size + externalFiles.size < 3) {
                logs.info("ACCEPTING file in path: ${file.path} (in ${dirPath})")
            }
        }
        
        // Add to appropriate collection if not already there
        if (isProjectFile) {
            if (!projectFiles.contains(file)) {
                projectFiles.add(file)
                // No logging for each file
            }
        } else {
            if (!externalFiles.contains(file)) {
                externalFiles.add(file)
                // No logging for each file
            }
        }
        
        // Early termination when we have enough files to process
        // We'll want to fill the full maxResults with project files first if possible,
        // so we only stop if we have maxResults project files, or if we have some project files 
        // plus enough external files to reach maxResults
        val totalUsefulFiles = projectFiles.size + Math.min(externalFiles.size, Math.max(0, maxResults - projectFiles.size))
        
        if (totalUsefulFiles >= maxResults) {
            // Only mark as limitReached if we specifically hit the external files limit
            if (projectFiles.size < maxResults) {
                limitReached = true
            }
            // Stop searching after hitting the limit
            return@Processor false // Stop processing
        }
        
        true // Continue processing
    }
    
    // Store the original search parameters
    val searchDirectoryPath = findModel.directoryName
    val originalScope = searchScope
    
    // PHASE 1: First search in project content only, respecting path constraint
    logs.info("Searching project files" + 
             (if (path != null) " in path: $path" else ""))
    
    // Set up the model for project search only, but keep the directory constraint
    findModel.isProjectScope = true
    
    // Make sure we're using the correct directory path
    if (searchDirectoryPath != null) {
        findModel.directoryName = searchDirectoryPath
    }
    
    FindInProjectUtil.findUsages(
        findModel,
        project,
        processor,
        FindUsagesProcessPresentation(UsageViewPresentation())
    )
    
    logs.info("Project files search complete: found ${projectFiles.size} files")
    
    // PHASE 2: If needed, search in libraries, still respecting path constraint
    if (projectFiles.size < maxResults) {
        logs.info("Searching external libraries" + 
                 (if (path != null) " in path: $path" else ""))
        
        // Make sure we're keeping path constraints
        if (searchDirectoryPath != null) {
            findModel.directoryName = searchDirectoryPath
        }
        
        // Apply the custom scope if we had one
        if (originalScope != null) {
            findModel.customScope = originalScope
        }
        
        // Set to search external libraries
        findModel.isProjectScope = false
        
        FindInProjectUtil.findUsages(
            findModel,
            project,
            processor,
            FindUsagesProcessPresentation(UsageViewPresentation())
        )
        
        logs.info("External files search complete: found ${externalFiles.size} files")
    } else {
        logs.info("Skipping external search - already found ${projectFiles.size} project files")
    }
    
    // Log total files found
    logs.info("Search completed - found ${projectFiles.size} project files and ${externalFiles.size} external library files")
    
    // Start sorting results
    
    // Sort project files by modification time (newest first)
    val sortedProjectFiles = projectFiles.sortedByDescending { file ->
        try { file.timeStamp } catch (e: Exception) { 0L }
    }
    
    // Sort completed
    
    // Calculate how many external files we need
    val remainingSlots = maxResults - sortedProjectFiles.size
    
    // If we have remaining slots and external files, sort and take what we need
    val limitedSortedFiles = if (remainingSlots > 0 && externalFiles.isNotEmpty()) {
        // Only sort the external files if we need them
        val sortedExternalFiles = externalFiles.sortedByDescending { file ->
            try { file.timeStamp } catch (e: Exception) { 0L }
        }
        
        // Add external files to results
        
        // Combine project files with the needed external files
        sortedProjectFiles + sortedExternalFiles.take(remainingSlots)
    } else {
        // If no external files needed, just use project files
        sortedProjectFiles
    }
    
    // Sorting is complete
    // Sorting complete
    
    // Apply any necessary post-processing
    
    // Create shortened paths for final results
    
    // Create shortened paths for the limited sorted results
    val finalShortenedPaths = limitedSortedFiles.map { file ->
        PathShortener.shortenFilePath(project, file.path, logs) 
    }
    
    return SearchContentResult.MultiFile(
        files = limitedSortedFiles,
        shortenedPaths = finalShortenedPaths,
        searchPath = searchPathValue,
        searchPathCorrected = searchPathCorrected,
        limitReached = limitReached
    )
}
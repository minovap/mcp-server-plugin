package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.LogCollector
import org.jetbrains.mcpserverplugin.utils.filesearch.FileSearch
import org.jetbrains.mcpserverplugin.utils.filesearch.SearchContentResult
import org.jetbrains.mcpserverplugin.utils.filesearch.searchByGlob

/**
 * Tool for fast file pattern matching using glob patterns.
 */
@Serializable
data class GlobToolArgs(
    // Glob pattern to search for files
    val pattern: String,
    
    // Optional path to limit the search scope
    val path: String? = null
)

class ClaudeCodeGlobTool : AbstractMcpTool<GlobToolArgs>() {
    override val name: String = "glob"
    override val isClaudeCodeTool: Boolean = true
    override val description: String = """
        - Fast file pattern matching tool that works with any codebase size
        - Searches everywhere including external libraries and dependencies
        - Supports glob patterns like "**/*.js" or "src/**/*.ts"
        - Prioritizes files in the project directory and sorts by modification time
        - Use this tool when you need to find files by name patterns
        
        glob = ({pattern: string, path?: string}) => string[] | { error: string };
    """.trimIndent()

    companion object {
        // Maximum number of results to return
        private const val MAX_RESULTS = 100
    }

    override fun handle(project: Project, args: GlobToolArgs): Response {
        // Create a log collector for diagnostic information
        val logCollector = LogCollector()
        
        // Validate the pattern parameter
        if (args.pattern.isBlank()) {
            logCollector.error("Pattern parameter is blank")
            return Response(error = "pattern parameter is required and cannot be blank", logs = logCollector.getMessages())
        }
        
        try {
            // Log the search parameters
            logCollector.info("Searching for files matching pattern: ${args.pattern}")
            if (args.path != null) logCollector.info("In path: ${args.path}")
            
            // Use FileSearch to find files matching the pattern
            val fileSearch = FileSearch()
            val results = fileSearch.searchByGlob(
                project = project,
                pattern = args.pattern,
                path = args.path,
                maxResults = MAX_RESULTS,
                logs = logCollector
            )
            
            // Prepare the response
            val finalResults = mutableListOf<String>()
            
            // Handle different result types
            when (results) {
                is SearchContentResult.MultiFile -> {
                    // Add path message if the path was corrected
                    if (results.searchPathCorrected && results.searchPath != null) {
                        finalResults.add("(Searching in corrected path: ${results.searchPath})")
                    }
                    
                    // Add the result paths
                    finalResults.addAll(results.shortenedPaths)
                    
                    // Add truncation message if results were limited
                    if (results.limitReached) {
                        finalResults.add("(Showing $MAX_RESULTS results. Use a more specific pattern or path for better results.)")
                    }
                    
                    // Handle empty results
                    if (results.files.isEmpty() && !finalResults.any { it.startsWith("(Searching") }) {
                        if (args.path != null) {
                            logCollector.info("No files found matching pattern: ${args.pattern} in path: ${args.path}")
                        } else {
                            logCollector.info("No files found matching pattern: ${args.pattern}")
                        }
                        return Response("No matching files found.", logs = logCollector.getMessages())
                    }
                }
                
                is SearchContentResult.SingleFile -> {
                    // Add path message if the path was corrected
                    if (results.searchPathCorrected && results.searchPath != null) {
                        finalResults.add("(Searching in file: ${results.shortenedPath})")
                    } else {
                        finalResults.add("File: ${results.shortenedPath}")
                    }
                }
                
                is SearchContentResult.Empty -> {
                    if (args.path != null) {
                        logCollector.info("No files found matching pattern: ${args.pattern} in path: ${args.path}")
                    } else {
                        logCollector.info("No files found matching pattern: ${args.pattern}")
                    }
                    return Response("No matching files found.", logs = logCollector.getMessages())
                }
            }
            
            // Return the results as a newline-delimited string
            return Response(finalResults.joinToString("\n"), logs = logCollector.getMessages())
            
        } catch (e: Exception) {
            logCollector.error("Error during glob search: ${e.message}")
            logCollector.error(e.stackTraceToString())
            return Response(error = "Error searching files: ${e.message}", logs = logCollector.getMessages())
        }
    }
}
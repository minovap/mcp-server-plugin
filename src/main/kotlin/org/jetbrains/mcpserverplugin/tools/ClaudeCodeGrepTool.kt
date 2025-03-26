package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.LogCollector
import org.jetbrains.mcpserverplugin.utils.filesearch.FileSearch
import org.jetbrains.mcpserverplugin.utils.filesearch.SearchContentResult
import org.jetbrains.mcpserverplugin.utils.filesearch.searchContent

/**
 * Tool for searching file contents using regular expressions.
 */
@Serializable
data class GrepToolArgs(
    // Regex pattern to search for in file contents
    val pattern: String,

    // Optional path to limit the search scope
    var path: String? = null,

    // Optional path to limit the search scope
    val file_path: String? = null,
    
    // Optional glob pattern to filter files by name
    val include: String? = null
)

class ClaudeCodeGrepTool : AbstractMcpTool<GrepToolArgs>() {
    override val name: String = "grep"
    override val isClaudeCodeTool: Boolean = true
    override val description: String = """
        - Fast content search tool that works with any codebase size
        - Searches file contents using regular expressions
        - Supports full regex syntax (eg. "log.*Error", "function\s+\w+", etc.)
        - Filter files by pattern with the include parameter (eg. "*.js", "*.{ts,tsx}")
        - Prioritizes files in the project directory and sorts by modification time
        - Use this tool when you need to find files containing specific patterns
        
        grep = ({pattern: string, path?: string, include?: string}) => string[] | { error: string };
    """.trimIndent()

    companion object {
        // Maximum number of results to return
        private const val MAX_RESULTS = 100
    }

    override fun handle(project: Project, args: GrepToolArgs): Response {
        // Create a log collector for diagnostic information
        val logCollector = LogCollector()

        if (args.file_path != null) {
            args.path = args.file_path;
        }
        
        // Validate the pattern parameter
        if (args.pattern.isBlank()) {
            logCollector.error("Pattern parameter is blank")
            return Response(error = "pattern parameter is required and cannot be blank", logs = logCollector.getMessages())
        }
        
        try {
            // Log the search parameters
            logCollector.info("Searching for pattern: ${args.pattern}")
            if (args.path != null) logCollector.info("In path: ${args.path}")
            if (args.include != null) logCollector.info("With file pattern: ${args.include}")
            
            // Use FileSearch for content searching
            val fileSearch = FileSearch()
            val searchResult = fileSearch.searchContent(
                project = project,
                pattern = args.pattern,
                path = args.path,
                includePattern = args.include,
                maxResults = MAX_RESULTS,
                logs = logCollector
            )
            
            // Prepare the response
            val finalResults = mutableListOf<String>()
            
            // Handle different result types
            when (searchResult) {
                is SearchContentResult.MultiFile -> {
                    // Add path message if the path was corrected
                    if (searchResult.searchPathCorrected && searchResult.searchPath != null) {
                        finalResults.add("(Searching in corrected path: ${searchResult.searchPath})")
                    }
                    
                    // Add the result paths
                    finalResults.addAll(searchResult.shortenedPaths)
                    
                    // Add truncation message if results were limited
                    if (searchResult.limitReached) {
                        finalResults.add("(Showing $MAX_RESULTS results. Use a more specific pattern or path for better results.)")
                    }
                    
                    // Handle empty results
                    if (searchResult.files.isEmpty() && !finalResults.any { it.startsWith("(Searching") }) {
                        if (args.path != null) {
                            logCollector.info("No files found containing pattern: ${args.pattern} in path: ${args.path}")
                        } else {
                            logCollector.info("No files found containing pattern: ${args.pattern}")
                        }
                        return Response("No matching files found.", logs = logCollector.getMessages())
                    }
                }
                
                is SearchContentResult.SingleFile -> {
                    // Add path message if the path was corrected, but don't add a File: header
                    if (searchResult.searchPathCorrected && searchResult.searchPath != null) {
                        finalResults.add("(Searching in corrected path: ${searchResult.searchPath})")
                    }
                    
                    // Format like grep -H -n: filename:line_number:content
                    searchResult.matchingLines.forEachIndexed { index, line ->
                        val lineNumber = searchResult.lineNumbers[index]
                        // Use shortenedPath:lineNumber:content format like grep -H -n
                        finalResults.add("${searchResult.shortenedPath}:$lineNumber:$line")
                    }
                }
                
                is SearchContentResult.Empty -> {
                    if (args.path != null) {
                        logCollector.info("No matches found for pattern: ${args.pattern} in path: ${args.path}")
                    } else {
                        logCollector.info("No matches found for pattern: ${args.pattern}")
                    }
                    return Response("No matching files or content found.", logs = logCollector.getMessages())
                }
            }
            
            // Return the results as a newline-delimited string
            return Response(finalResults.joinToString("\n"), logs = logCollector.getMessages())
            
        } catch (e: Exception) {
            logCollector.error("Error during search: ${e.message}")
            logCollector.error(e.stackTraceToString())
            return Response(error = "Error searching files: ${e.message}", logs = logCollector.getMessages())
        }
    }
}
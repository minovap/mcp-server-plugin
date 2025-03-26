package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.LogCollector
import org.jetbrains.mcpserverplugin.utils.filesearch.DirectoryEntry
import org.jetbrains.mcpserverplugin.utils.filesearch.FileSearch
import org.jetbrains.mcpserverplugin.utils.filesearch.listDirectory

/**
 * Tool for listing files and directories in a given path.
 */
@Serializable
data class LsToolArgs(
    /**
     * Path to the directory to list contents of.
     * Can be an absolute path, relative path from project root, or a partial path.
     * 
     * Examples:
     * - "src/main"                     (relative to project root)
     * - "src"                          (directory name, will search project)
     * - "."                            (project root)
     * - "/"                            (project root)
     * - "/absolute/path/to/directory" (absolute path)
     */
    val path: String,
    
    /**
     * Optional list of glob patterns to ignore.
     * Files/directories matching these patterns will be excluded from results.
     * 
     * Examples:
     * - null            (no files ignored)
     * - ["*.class"]    (ignore all .class files)
     * - ["*.{js,map}"] (ignore both .js and .map files)
     * - [".*"]         (ignore hidden files starting with .)
     * - ["node_modules", "build", "*.class"] (ignore multiple patterns)
     */
    val ignore: List<String>? = null
)

class ClaudeCodeLsTool : AbstractMcpTool<LsToolArgs>() {
    override val name: String = "ls"
    override val isClaudeCodeTool: Boolean = true
    override val description: String = """
        Lists files and directories in a given path.
        You can optionally provide an array of glob patterns to ignore with the ignore parameter. 
        You should generally prefer the Glob and Grep tools, if you know which directories to search.
        
        When the specified path is corrected during lookup, the response will be a JSON object containing 
        both the directory entries and the corrected path: { entries: string, path: string }
        
        ls = ({path: string, ignore?: string[]}) => string | { entries: string, path: string } | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: LsToolArgs): Response {
        // Create a log collector for tracking operations
        val logCollector = LogCollector()
        
        // Use FileSearch to list the directory contents
        val fileSearch = FileSearch()
        val (entries, correctedPath) = fileSearch.listDirectory(
            project = project, 
            dirPath = args.path, 
            ignorePatterns = args.ignore, 
            logs = logCollector
        )
        
        // Handle errors indicated by empty entries list
        if (entries.isEmpty()) {
            // Check log messages for error details
            val errorMessage = logCollector.getMessages().lastOrNull { it.contains("ERROR") }
                ?.substringAfter("ERROR: ")
                ?: "No files found or error listing directory"
            
            return Response(error = errorMessage, logs = logCollector.getMessages())
        }
        
        // Format entries as a simple list of names
        val output = entries.joinToString("\n") { it.name }
        
        // If the path was corrected, return a JSON response with both content and corrected path
        if (correctedPath != null) {
            logCollector.info("Path was corrected from '${args.path}' to '$correctedPath'")
            
            // Create proper JSON using Kotlin's JSON DSL
            val jsonResponse = buildJsonObject {
                put("entries", output)
                put("path", correctedPath)
            }.toString()
            
            return Response(jsonResponse, logs = logCollector.getMessages())
        }
        
        // For paths that weren't corrected, return a simple string response
        return Response(output, logs = logCollector.getMessages())
    }
}
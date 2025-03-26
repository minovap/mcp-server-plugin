package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.LogCollector
import org.jetbrains.mcpserverplugin.utils.filesearch.FileContent
import org.jetbrains.mcpserverplugin.utils.filesearch.FileSearch
import org.jetbrains.mcpserverplugin.utils.filesearch.readFileContent

/**
 * Tool for reading file contents.
 */
@Serializable
data class ViewToolArgs(
    /**
     * Path to the file to be read.
     * Can be an absolute path, relative path from project root, or a partial path.
     * 
     * Examples:
     * - "src/main/kotlin/MyClass.kt"  (relative to project root)
     * - "MyClass.kt"                  (just filename, will search project)
     * - "build.gradle.kts"           (will find in project root)
     * - "/absolute/path/to/file.txt" (absolute path)
     */
    val file_path: String,
    
    /**
     * Line offset to start reading from (0-based).
     * 
     * Examples:
     * - null  (start from the beginning of the file)
     * - 0     (start from the first line)
     * - 100   (start from the 101st line)
     */
    val offset: Int? = null,
    
    /**
     * Maximum number of lines to read.
     * If not specified, defaults to 2000 lines.
     * 
     * Examples:
     * - null  (use default limit of 2000 lines)
     * - 10    (read up to 10 lines)
     * - 5000  (read up to 5000 lines)
     */
    val limit: Int? = null
)

class ClaudeCodeViewTool : AbstractMcpTool<ViewToolArgs>() {
    override val name: String = "view"
    override val isClaudeCodeTool: Boolean = true
    override val description: String = """
        Reads a file from the local filesystem.
        By default, it reads up to 2000 lines starting from the beginning of the file. 
        You can optionally specify a line offset and limit (especially handy for long files), 
        but it's recommended to read the whole file by not providing these parameters. 
        Any lines longer than 2000 characters will be truncated.
        
        When the specified path is corrected during lookup, the response will be a JSON object containing 
        both the file content and the corrected path: { content: string, file_path: string }
        
        view = ({file_path: string, offset?: number, limit?: number}) => string | { content: string, file_path: string } | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: ViewToolArgs): Response {
        // Create a log collector for diagnostic information
        val logCollector = LogCollector()
        
        // Use FileSearch to read the file content
        val fileSearch = FileSearch()
        val result = fileSearch.readFileContent(
            project = project,
            path = args.file_path,
            offset = args.offset,
            limit = args.limit,
            logs = logCollector
        )
        
        // Process the result
        return result.fold(
            onSuccess = { fileContent: FileContent ->
                // Join the lines to get content
                val content = fileContent.lines.joinToString("\n")
                
                // If the path was corrected, return a JSON with both content and filename
                if (fileContent.pathCorrected) {
                    // Use the Kotlin JSON DSL to create proper JSON
                    val jsonResponse = buildJsonObject {
                        put("content", content)
                        put("file_path", fileContent.shortenedPath)
                    }.toString()
                    
                    Response(jsonResponse, logs = logCollector.getMessages())
                } else {
                    // Otherwise, just return the content directly
                    Response(content, logs = logCollector.getMessages())
                }
            },
            onFailure = { error ->
                Response(error = error.message ?: "Unknown error reading file", logs = logCollector.getMessages())
            }
        )
    }
}
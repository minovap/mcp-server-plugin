package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Tool for reading file contents.
 */
@Serializable
data class ViewToolArgs(
    val file_path: String,
    val offset: Int? = null,
    val limit: Int? = null
)

class ClaudeCodeViewTool : AbstractMcpTool<ViewToolArgs>() {
    override val name: String = "view"
    override val isClaudeCodeTool: Boolean = true
    override val description: String = """
        Reads a file from the local filesystem. The file_path parameter must be an absolute path, not a relative path. 
        By default, it reads up to 2000 lines starting from the beginning of the file. 
        You can optionally specify a line offset and limit (especially handy for long files), 
        but it's recommended to read the whole file by not providing these parameters. 
        Any lines longer than 2000 characters will be truncated.
        
        view = ({file_path: string, offset?: number, limit?: number}) => string | { error: string };
    """.trimIndent()

    private val DEFAULT_LINE_LIMIT = 2000
    private val MAX_LINE_LENGTH = 2000

    override fun handle(project: Project, args: ViewToolArgs): Response {
        // Determine the file path
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "project dir not found")
        
        val filePath = try {
            val path = Paths.get(args.file_path)
            if (path.isAbsolute) {
                path
            } else {
                projectDir.resolve(path)
            }
        } catch (e: Exception) {
            return Response(error = "Invalid file path: ${args.file_path}")
        }

        if (!Files.exists(filePath)) {
            return Response(error = "File does not exist: $filePath")
        }

        if (!Files.isRegularFile(filePath)) {
            return Response(error = "Path is not a regular file: $filePath")
        }

        try {
            // Read the file content
            val allLines = Files.readAllLines(filePath)
            
            // Apply offset and limit
            val startIndex = args.offset?.coerceAtLeast(0) ?: 0
            val endIndex = if (args.limit != null) {
                val limit = args.limit.coerceAtLeast(1)
                (startIndex + limit).coerceAtMost(allLines.size)
            } else {
                (startIndex + DEFAULT_LINE_LIMIT).coerceAtMost(allLines.size)
            }
            
            // Get the lines within range and truncate if needed
            val lines = allLines.subList(startIndex, endIndex).map { line ->
                if (line.length > MAX_LINE_LENGTH) {
                    line.substring(0, MAX_LINE_LENGTH) + "... (truncated)"
                } else {
                    line
                }
            }
            
            // Join the lines and return as content
            val content = lines.joinToString("\n")
            return Response(content)
        } catch (e: Exception) {
            return Response(error = "Error reading file: ${e.message}")
        }
    }
}

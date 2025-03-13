package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Tool for writing/replacing file contents.
 */
@Serializable
data class ReplaceToolArgs(
    val file_path: String,
    val content: String
)

class ClaudeCodeReplaceTool : AbstractMcpTool<ReplaceToolArgs>() {
    override val name: String = "replace"
    override val isClaudeCodeTool: Boolean = true
    override val description: String = """
        Write a file to the local filesystem. Overwrites the existing file if there is one.
        
        Before using this tool:
        
        1. Use the View tool to understand the file's contents and context
        
        2. Directory Verification (only applicable when creating new files):
           - Use the LS tool to verify the parent directory exists and is the correct location
         
        replace = ({file_path: string, content: string}) => string | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: ReplaceToolArgs): Response {
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

        try {
            // Create parent directories if they don't exist
            Files.createDirectories(filePath.parent)

            // Write file content
            Files.writeString(
                filePath,
                args.content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )

            return Response("Successfully wrote to file: $filePath")
        } catch (e: Exception) {
            return Response(error = "Error writing file: ${e.message}")
        }
    }
}

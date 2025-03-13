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
 * Tool for editing files with string replacement.
 */
@Serializable
data class EditToolArgs(
    val file_path: String,
    val old_string: String,
    val new_string: String
)

class ClaudeCodeEditTool : AbstractMcpTool<EditToolArgs>() {
    override val name: String = "edit"
    override val isClaudeCodeTool: Boolean = true
    override val description: String = """
        This is a tool for editing files. For moving or renaming files, you should generally use the Bash tool with the 'mv' command instead. 
        Use this tool when replacing lines in one place. When replacing lines at multiple places use the apply_patch tool instead.
        For larger edits, use the Write tool to overwrite files.      
        
        To make a file edit, provide the following:
        1. file_path: The absolute path to the file to modify (must be absolute, not relative)
        2. old_string: The text to replace (must be unique within the file, and must match exactly)
        3. new_string: The edited text to replace the old_string
        
        The tool will replace ONE occurrence of old_string with new_string in the specified file.
        
        edit = ({file_path: string, old_string: string, new_string: string}) => string | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: EditToolArgs): Response {
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

        // Check if we're creating a new file or editing existing one
        val isNewFile = args.old_string.isEmpty() && !Files.exists(filePath)
        
        if (!isNewFile && !Files.exists(filePath)) {
            return Response(error = "File does not exist: $filePath")
        }

        try {
            if (isNewFile) {
                // Create parent directories if they don't exist
                Files.createDirectories(filePath.parent)
                
                // Write new file content
                Files.writeString(
                    filePath, 
                    args.new_string,
                    StandardOpenOption.CREATE, 
                    StandardOpenOption.TRUNCATE_EXISTING
                )
                
                return Response("Created new file: $filePath")
            } else {
                // Read existing file content
                val content = Files.readString(filePath)
                
                // Count matches
                val count = content.split(args.old_string).size - 1
                
                if (count == 0) {
                    return Response(error = "The specified text was not found in the file")
                }
                
                if (count > 1) {
                    return Response(error = "The specified text appears multiple times (${count}) in the file. Please provide more context to uniquely identify the instance.")
                }
                
                // Replace text
                val newContent = content.replace(args.old_string, args.new_string)
                
                // Write back to file
                Files.writeString(
                    filePath, 
                    newContent,
                    StandardOpenOption.CREATE, 
                    StandardOpenOption.TRUNCATE_EXISTING
                )
                
                return Response("Successfully edited file: $filePath")
            }
        } catch (e: Exception) {
            return Response(error = "Error editing file: ${e.message}")
        }
    }
}

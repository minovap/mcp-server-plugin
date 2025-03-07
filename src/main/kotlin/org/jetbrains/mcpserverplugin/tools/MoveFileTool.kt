package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.toNioPathOrNull
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.resolveRel
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.moveTo

@Serializable
data class MoveFileArgs(
    val sourcePath: String,
    val targetPath: String
)

class MoveFileTool : AbstractMcpTool<MoveFileArgs>() {
    override val name: String = "file_move"
    override val description: String = """
        Moves or renames a file or directory.

        <sourcePath> Current path (relative to project root)
        <targetPath> New path (relative to project root)

        file_move = ({sourcePath: string, targetPath: string}) => "ok" | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: MoveFileArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")

        val sourcePath = projectDir.resolveRel(args.sourcePath)
        val targetPath = projectDir.resolveRel(args.targetPath)
        
        return try {
            if (!sourcePath.exists()) {
                return Response(error = "source file not found")
            }
            
            if (targetPath.exists()) {
                return Response(error = "target already exists")
            }
            
            targetPath.parent.createDirectories()
            sourcePath.moveTo(targetPath, overwrite = false)
            
            // Refresh the filesystem to reflect changes in IDE
            LocalFileSystem.getInstance().refresh(true)
            
            Response("ok")
        } catch (e: Exception) {
            Response(error = "failed to move: ${e.message}")
        }
    }
}

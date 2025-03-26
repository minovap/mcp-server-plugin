package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.toNioPathOrNull
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.filesearch.FileSearch
import org.jetbrains.mcpserverplugin.utils.filesearch.resolveRel
import kotlin.io.path.createDirectories
import kotlin.io.path.copyTo
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

@Serializable
data class CopyFileArgs(
    val sourcePath: String,
    val targetPath: String,
    val recursive: Boolean = true
)

class CopyFileTool : AbstractMcpTool<CopyFileArgs>() {
    override val name: String = "file_copy"
    override val description: String = """
        Copies a file or directory to a new location.

        <sourcePath> File/directory to copy (relative to project root)
        <targetPath> Destination path (relative to project root)

        file_copy = ({sourcePath: string, targetPath: string, recursive: boolean}) => "ok" | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: CopyFileArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")

        val fileSearch = FileSearch()
        val sourcePath = fileSearch.resolveRel(projectDir, args.sourcePath)
        val targetPath = fileSearch.resolveRel(projectDir, args.targetPath)
        
        return try {
            if (!sourcePath.exists()) {
                return Response(error = "source file not found")
            }
            
            if (targetPath.exists()) {
                return Response(error = "target already exists")
            }
            
            if (sourcePath.isDirectory()) {
                sourcePath.toFile().copyRecursively(targetPath.toFile())
            } else {
                targetPath.parent.createDirectories()
                sourcePath.copyTo(targetPath)
            }
            
            // Refresh the filesystem to reflect changes in IDE
            LocalFileSystem.getInstance().refresh(true)
            
            Response("ok")
        } catch (e: Exception) {
            Response(error = "failed to copy: ${e.message}")
        }
    }
}
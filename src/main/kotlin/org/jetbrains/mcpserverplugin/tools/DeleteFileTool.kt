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
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

@Serializable
data class DeleteFileArgs(
    val pathInProject: String,
    val recursive: Boolean = false
)

class DeleteFileTool : AbstractMcpTool<DeleteFileArgs>() {
    override val name: String = "file_delete"
    override val description: String = """
        Deletes a file or directory from the project.

        <pathInProject> Path to delete (relative to project root)
        <recursive> Set true to delete directories with contents

        file_delete = ({pathInProject: string, recursive: boolean}) => "ok" | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: DeleteFileArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")

        val fileSearch = FileSearch()
        val path = fileSearch.resolveRel(projectDir, args.pathInProject)

        return try {
            if (!path.exists()) {
                return Response(error = "file not found")
            }

            if (path.isDirectory()) {
                if (args.recursive) {
                    path.toFile().deleteRecursively()
                } else {
                    if (path.listDirectoryEntries().isNotEmpty()) {
                        return Response(error = "directory not empty, use recursive=true to delete with contents")
                    }
                    path.deleteExisting()
                }
            } else {
                path.deleteExisting()
            }

            // Refresh the filesystem to reflect changes in IDE
            LocalFileSystem.getInstance().refresh(true)

            Response("ok")
        } catch (e: Exception) {
            Response(error = "failed to delete: ${e.message}")
        }
    }
}
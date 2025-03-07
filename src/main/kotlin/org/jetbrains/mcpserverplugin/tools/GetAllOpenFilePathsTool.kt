package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.relativizeByProjectDir

class GetAllOpenFilePathsTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_all_open_file_paths"
    override val description: String = """
        Lists all currently open files in the editor.

        get_all_open_file_paths = () => string;
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()

        val fileEditorManager = FileEditorManager.getInstance(project)
        val openFiles = fileEditorManager.openFiles
        val filePaths = openFiles.mapNotNull { it.toNioPath().relativizeByProjectDir(projectDir) }
        return Response(filePaths.joinToString("\n"))
    }
}

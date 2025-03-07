package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.readText
import com.intellij.openapi.vfs.toNioPathOrNull
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.relativizeByProjectDir

class GetAllOpenFileTextsTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_all_open_file_texts"
    override val description: String = """
        Returns text of all currently open files in the editor.

        get_all_open_file_texts = () => Array<{ path: string; text: string }>;
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()

        val fileEditorManager = FileEditorManager.getInstance(project)
        val openFiles = fileEditorManager.openFiles
        val filePaths = openFiles.mapNotNull { """{"path": "${it.toNioPath().relativizeByProjectDir(projectDir)}", "text": "${it.readText()}", """ }
        return Response(filePaths.joinToString(",\n", prefix = "[", postfix = "]"))
    }
}

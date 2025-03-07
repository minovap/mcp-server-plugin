package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.toNioPathOrNull
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.resolveRel

@Serializable
data class OpenFileInEditorArgs(val filePath: String)

class OpenFileInEditorTool : AbstractMcpTool<OpenFileInEditorArgs>() {
    override val name: String = "open_file_in_editor"
    override val description: String = """
        Opens a file in the JetBrains IDE editor.

        <filePath> Path to the file (absolute or relative to project root)

        open_file_in_editor = ({filePath: string}) => "file is opened" | { error: "file doesn't exist or can't be opened" };
    """.trimIndent()

    override fun handle(project: Project, args: OpenFileInEditorArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")

        val file = LocalFileSystem.getInstance().findFileByPath(args.filePath)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectDir.resolveRel(args.filePath))

        return if (file != null && file.exists()) {
            invokeLater {
                FileEditorManager.getInstance(project).openFile(file, true)
            }
            Response("file is opened")
        } else {
            Response("file doesn't exist or can't be opened")
        }
    }
}

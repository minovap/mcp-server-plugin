package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.util.application
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

@Serializable
data class ReplaceCurrentFileTextArgs(val text: String)

class ReplaceCurrentFileTextTool : AbstractMcpTool<ReplaceCurrentFileTextArgs>() {
    override val name: String = "replace_current_file_text"
    override val description: String = """
        Replaces the content of the currently active file.

        <text> New content for the file

        replace_current_file_text = ({text: string}) => "ok" | { error: "no file open" | "unknown error" };
    """

    override fun handle(project: Project, args: ReplaceCurrentFileTextArgs): Response {
        var response: Response? = null
        application.invokeAndWait {
            runWriteCommandAction(project, "Replace File Text", null, {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                val document = editor?.document
                if (document != null) {
                    document.setText(args.text)
                    response = Response("ok")
                } else {
                    response = Response(error = "no file open")
                }
            })
        }
        return response ?: Response(error = "unknown error")
    }
}

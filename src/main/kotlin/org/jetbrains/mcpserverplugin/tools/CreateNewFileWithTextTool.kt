package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.util.io.createParentDirectories
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.resolveRel
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.writeText

@Serializable
data class CreateNewFileWithTextArgs(val pathInProject: String, val text: String)

class CreateNewFileWithTextTool : AbstractMcpTool<CreateNewFileWithTextArgs>() {
    override val name: String = "create_new_file_with_text"
    override val description: String = """
        Creates a new file and populates it with text.

        <pathInProject> Relative path where the file should be created
        <text> Content to write into the new file

        create_new_file_with_text = ({pathInProject: string, text: string}) => "ok" | { error: "can't find project dir" };
    """

    override fun handle(project: Project, args: CreateNewFileWithTextArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")

        val path = projectDir.resolveRel(args.pathInProject)
        if (!path.exists()) {
            path.createParentDirectories().createFile()
        }
        val text = args.text
        path.writeText(text.unescape())
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)

        return Response("ok")
    }
}

private fun String.unescape(): String = removePrefix("<![CDATA[").removeSuffix("]]>")

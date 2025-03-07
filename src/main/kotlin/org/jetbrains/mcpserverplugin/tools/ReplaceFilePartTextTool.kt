package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.toNioPathOrNull
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.resolveRel
import java.nio.file.Files
import kotlin.io.path.exists

@Serializable
data class ReplaceFilePartTextArgs(
    val pathInProject: String,
    val lineFrom: Int,
    val lineTo: Int,
    val text: String
)

class ReplaceFilePartTextTool : AbstractMcpTool<ReplaceFilePartTextArgs>() {
    override val name: String = "replace_file_part_text"
    override val description: String = """
      Replaces lines in a file between specified line numbers with new text,
      and returns the updated content with 10 lines of context before and after the change.
      
      <pathInProject> Path to the file, relative to project root
      <lineFrom> Starting line number (inclusive) to replace
      <lineTo> Ending line number (inclusive) to replace
      <text> New text to insert (can be multiline)
      
      replace_file_part_text = ({pathInProject: string, lineFrom: number, lineTo: number, text: string}) => string | { error: string };
  """.trimIndent()

    override fun handle(project: Project, args: ReplaceFilePartTextArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")
        val filePath = projectDir.resolveRel(args.pathInProject)
        if (!filePath.exists()) {
            return Response(error = "file not found")
        }
        var contextResult: String? = null
        WriteCommandAction.runWriteCommandAction(project) {
            try {
                val originalLines = Files.readAllLines(filePath).toMutableList()
                if (args.lineFrom < 1 || args.lineTo < args.lineFrom || args.lineFrom > originalLines.size) {
                    contextResult = "invalid line range"
                    return@runWriteCommandAction
                }
                val endIndex = if (args.lineTo > originalLines.size) originalLines.size else args.lineTo
                val newLines = args.text.split("\n")
                repeat(endIndex - args.lineFrom + 1) { originalLines.removeAt(args.lineFrom - 1) }
                originalLines.addAll(args.lineFrom - 1, newLines)
                Files.write(filePath, originalLines)
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(filePath)
                val startContext = (args.lineFrom - 1 - 10).coerceAtLeast(0)
                val endContext = ((args.lineFrom - 1) + newLines.size + 10).coerceAtMost(originalLines.size)
                contextResult = originalLines.subList(startContext, endContext).joinToString("\n")
            } catch (e: Exception) {
                contextResult = "error replacing file part: ${e.message}"
            }
        }
        return if (contextResult == null || contextResult!!.startsWith("error") || contextResult == "invalid line range")
            Response(error = contextResult ?: "unknown error")
        else Response(contextResult!!)
    }
}

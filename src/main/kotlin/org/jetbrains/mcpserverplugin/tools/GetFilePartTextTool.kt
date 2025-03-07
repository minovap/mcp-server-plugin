package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.resolveRel
import java.nio.file.Files

@Serializable
data class GetFilePartTextArgs(
    val pathInProject: String,
    val lineFrom: Int,
    val lineTo: Int
)

class GetFilePartTextTool : AbstractMcpTool<GetFilePartTextArgs>() {
    override val name: String = "get_file_part_text"
    override val description: String = """
      Reads a portion of a file's content based on line numbers.
      
      <pathInProject> Path to the file, relative to project root
      <lineFrom> Starting line number (inclusive)
      <lineTo> Ending line number (inclusive)
      
      get_file_part_text = ({pathInProject: string, lineFrom: number, lineTo: number}) => string | { error: string };
  """.trimIndent()

    override fun handle(project: Project, args: GetFilePartTextArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")
        val filePath = projectDir.resolveRel(args.pathInProject)
        if (!Files.exists(filePath)) {
            return Response(error = "file not found")
        }
        return runReadAction {
            try {
                val lines = Files.readAllLines(filePath)
                if (args.lineFrom < 1 || args.lineTo < args.lineFrom || args.lineFrom > lines.size) {
                    return@runReadAction Response(error = "invalid line range")
                }
                val endIndex = if (args.lineTo > lines.size) lines.size else args.lineTo
                val extracted = lines.subList(args.lineFrom - 1, endIndex).joinToString("\n")
                Response(extracted)
            } catch (e: Exception) {
                Response(error = "error reading file: ${e.message}")
            }
        }
    }
}

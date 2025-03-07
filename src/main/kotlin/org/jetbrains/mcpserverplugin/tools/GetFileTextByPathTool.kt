package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.readText
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.resolveRel

@Serializable
data class GetFilesTextByPathArgs(
    val pathsInProject: List<String>,
    val headLines: Int = 0  // default to 0 which means return all content
)

class GetFileTextByPathTool : AbstractMcpTool<GetFilesTextByPathArgs>() {
    override val name: String = "get_file_text_by_path"
    override val description: String = """
        Retrieves the text content of one or multiple files.

        <pathsInProject> List of paths to the files, relative to project root
        <headLines> Optional. Number of lines to return from the beginning of each file.
                   Default is 0, which returns the entire file.

        get_files_text_by_path = ({pathsInProject: string[], headLines?: number}) => {[filePath: string]: string} | { error: string };
    """

    override fun handle(project: Project, args: GetFilesTextByPathArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "project dir not found")

        val result = mutableMapOf<String, String>()
        var hasError = false
        var errorMessage = ""

        for (pathInProject in args.pathsInProject) {
            runReadAction {
                val file = LocalFileSystem.getInstance()
                    .refreshAndFindFileByNioFile(projectDir.resolveRel(pathInProject))

                if (file == null || !GlobalSearchScope.allScope(project).contains(file)) {
                    hasError = true
                    errorMessage = "file not found: $pathInProject"
                    return@runReadAction
                }

                val fileContent = if (args.headLines > 0) {
                    // Read only specified number of lines
                    file.readText().lines().take(args.headLines).joinToString("\n")
                } else {
                    // Read entire file
                    file.readText()
                }

                result[pathInProject] = fileContent
            }

            // Break early if an error is encountered
            if (hasError) break
        }

        return if (hasError) {
            Response(error = errorMessage)
        } else {
            // Convert the map to a JSON string
            val jsonResult = result.entries.joinToString(",\n", prefix = "{", postfix = "}") { (path, content) ->
                "\"$path\": ${content.escapeForJson()}"
            }
            Response(jsonResult)
        }
    }

    // Helper function to properly escape the content for JSON
    private fun String.escapeForJson(): String {
        val escaped = this.replace("\\", "\\\\")
                         .replace("\"", "\\\"")
                         .replace("\n", "\\n")
                         .replace("\r", "\\r")
                         .replace("\t", "\\t")
        return "\"$escaped\""
    }
}

package org.jetbrains.mcpserverplugin.tools

import com.intellij.find.FindManager
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.readText
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.usageView.UsageInfo
import com.intellij.usages.FindUsagesProcessPresentation
import com.intellij.usages.UsageViewPresentation
import com.intellij.util.Processor
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import java.nio.file.Path
import java.nio.file.Paths

@Serializable
data class SearchInFilesArgs(
    val searchText: String,
    val contextBefore: Int = 0,
    val contextAfter: Int = 0
)

class SearchInFilesContentTool : AbstractMcpTool<SearchInFilesArgs>() {
  override val name: String = "search_in_files_content"
  override val description: String = """
      Searches for text within all project files, returning a snippet with context lines.

      <searchText> Text to find in files
      <contextBefore> Number of lines before the matching line to include (default: 0)
      <contextAfter> Number of lines after the matching line to include (default: 0)

      If both contextBefore and contextAfter are 0, only the matching line is returned.

      Hard limit: The total number of snippet rows will not exceed 1000.
      If the limit is reached, a warning is added to the output.

      search_in_files_content = ({searchText: string, contextBefore?: number, contextAfter?: number}) => Array<{ path: string, snippet: string }> | { error: string };
  """.trimIndent()

  // Hard limit on total snippet rows.
  private val HARD_LIMIT_ROWS = 1000

  override fun handle(project: Project, args: SearchInFilesArgs): Response {
    val projectDir = project.guessProjectDir()?.toNioPathOrNull()
      ?: return Response(error = "Project directory not found")
    val searchSubstring = args.searchText
    if (searchSubstring.isBlank()) {
      return Response(error = "searchText parameter is required and cannot be blank")
    }

    // Calculate the number of lines per snippet.
    val snippetLineCount = if (args.contextBefore == 0 && args.contextAfter == 0) 1
                             else 1 + args.contextBefore + args.contextAfter
    // Maximum number of result entries such that total snippet rows <= HARD_LIMIT_ROWS.
    val maxResults = HARD_LIMIT_ROWS / snippetLineCount

    val findModel = FindManager.getInstance(project).findInProjectModel.clone()
    findModel.stringToFind = searchSubstring
    findModel.isCaseSensitive = false
    findModel.isWholeWordsOnly = false
    findModel.isRegularExpressions = false
    findModel.setProjectScope(true)

    val results = mutableSetOf<String>()
    val processor = Processor<UsageInfo> { usageInfo ->
      if (results.size >= maxResults) return@Processor false
      val virtualFile = usageInfo.virtualFile ?: return@Processor true
      val document = FileDocumentManager.getInstance().getDocument(virtualFile)
      val fileText = if (document != null) document.text else virtualFile.readText()
      // Use the PSI element's text range if available.
      val offset = usageInfo.element?.textRange?.startOffset ?: 0
      val lineNumber = if (document != null) {
          document.getLineNumber(offset)
      } else {
          fileText.substring(0, offset.coerceAtMost(fileText.length)).count { it == '\n' }
      }
      val lines = if (document != null) document.text.lines() else fileText.lines()
      val startLine = (lineNumber - args.contextBefore).coerceAtLeast(0)
      val endLine = (lineNumber + args.contextAfter).coerceAtMost(lines.size - 1)
      val snippet = if (args.contextBefore == 0 && args.contextAfter == 0) {
          lines[lineNumber]
      } else {
          lines.subList(startLine, endLine + 1).joinToString("\n")
      }
      try {
          val relativePath = projectDir.relativize(Paths.get(virtualFile.path)).toString()
          results.add("""{"path": "$relativePath", "snippet": ${snippet.escapeForJson()}}""")
      } catch (e: IllegalArgumentException) {
          // Ignore files that cannot be relativized.
      }
      true
    }

    FindInProjectUtil.findUsages(
      findModel,
      project,
      processor,
      FindUsagesProcessPresentation(UsageViewPresentation())
    )

    // Append a warning if we reached the limit.
    val resultList = results.toMutableList()
    if (results.size >= maxResults) {
      resultList.add("""{"warning": "Reached maximum snippet line limit of $HARD_LIMIT_ROWS"}""")
    }
    val jsonResult = resultList.joinToString(",\n", prefix = "[", postfix = "]")
    return Response(jsonResult)
  }

  private fun String.escapeForJson(): String {
    val escaped = this.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    return "\"$escaped\""
  }
}

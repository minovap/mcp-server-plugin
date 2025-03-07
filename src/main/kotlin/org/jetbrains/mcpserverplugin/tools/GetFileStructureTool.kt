package org.jetbrains.mcpserverplugin.tools

import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.resolveRel
import java.nio.file.Path

@Serializable
data class GetFileStructureArgs(val pathInProject: String)

/**
 * A tool that returns the structure of a file using IntelliJ's Structure View API.
 * This approach is language-agnostic and works with any file type that has a structure view provider.
 */
class GetFileStructureTool : AbstractMcpTool<GetFileStructureArgs>() {
    override val name: String = "get_file_structure"
    override val description: String = """
        Returns the structure of a file as seen in IntelliJ's Structure View.

        <pathInProject> Path to the file, relative to project root

        get_file_structure = ({pathInProject: string}) => string | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: GetFileStructureArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "project dir not found")

        return runReadAction {
            try {
                val path = projectDir.resolveRel(args.pathInProject)
                val virtualFile = LocalFileSystem.getInstance()
                    .refreshAndFindFileByNioFile(path)
                    ?: return@runReadAction Response(error = "file not found")

                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                    ?: return@runReadAction Response(error = "couldn't parse file")

                // Use the Structure View API to get the file structure
                val structure = extractStructureFromFile(psiFile)
                Response(structure)
            } catch (e: Exception) {
                Response(error = "Error analyzing file structure: ${e.message}")
            }
        }
    }

    /**
     * Extracts the structure from a file using IntelliJ's Structure View API
     */
    private fun extractStructureFromFile(psiFile: PsiFile): String {
        val builder = LanguageStructureViewBuilder.INSTANCE.getStructureViewBuilder(psiFile)
            ?: return createBasicFileInfo(psiFile)

        if (builder !is TreeBasedStructureViewBuilder) {
            return createBasicFileInfo(psiFile)
        }

        val structureViewModel = builder.createStructureViewModel(null)
        val rootElement = structureViewModel.root

        val result = StringBuilder()
        result.append("{\n")
        result.append("  \"fileName\": \"${psiFile.name}\",\n")
        result.append("  \"fileType\": \"${psiFile.fileType.name}\",\n")
        result.append("  \"language\": \"${psiFile.language.displayName}\",\n")

        // Process the structure tree
        result.append("  \"elements\": ")
        buildStructureTree(rootElement, result, 0)

        result.append("\n}")
        return result.toString()
    }

    /**
     * Recursively builds a JSON representation of the structure tree
     * with added line and column number information
     */
    private fun buildStructureTree(element: TreeElement, result: StringBuilder, level: Int): StringBuilder {
        if (level == 0) {
            result.append("[\n")
        }

        val indent = "    ".repeat(level + 1)
        val children = element.children

        if (element !is PsiTreeElementBase<*>) {
            // For non-PSI elements, just include presentation text
            result.append("$indent{\n")
            result.append("$indent  \"name\": \"${element.presentation.presentableText}\",\n")
            result.append("$indent  \"type\": \"element\"\n")
            result.append("$indent}")
        } else {
            // For PSI elements, include more information
            val value = element.value
            val elementType = getElementType(value)

            result.append("$indent{\n")
            result.append("$indent  \"name\": \"${element.presentation.presentableText}\",\n")
            result.append("$indent  \"type\": \"$elementType\"")

            // Add line and column number information if it's a PsiElement
            if (value is PsiElement) {
                try {
                    val containingFile = value.containingFile
                    val project = containingFile.project
                    val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
                        ?: containingFile.viewProvider.document

                    if (document != null) {
                        val textRange = value.textRange
                        if (textRange != null) {
                            // Start position
                            val startOffset = textRange.startOffset
                            val startLineNumber = document.getLineNumber(startOffset) + 1 // +1 because line numbers are 0-based

                            result.append(",\n$indent  \"startLine\": $startLineNumber")

                            // End position
                            val endOffset = textRange.endOffset
                            val endLineNumber = document.getLineNumber(endOffset) + 1

                            result.append(",\n$indent  \"endLine\": $endLineNumber")
                        }
                    }
                } catch (e: Exception) {
                    // If we can't get position info, just continue without it
                    result.append(",\n$indent  \"positionInfo\": \"unavailable: ${e.message?.escapeJson() ?: "unknown error"}\"")
                }
            }

            // Add any location text or tooltips if available
            element.presentation.locationString?.let {
                if (it.isNotEmpty()) {
                    result.append(",\n$indent  \"detail\": \"${it.escapeJson()}\"")
                }
            }

            if (children.isNotEmpty()) {
                result.append(",\n")
                result.append("$indent  \"children\": [\n")

                children.forEachIndexed { index, child ->
                    buildStructureTree(child, result, level + 1)
                    if (index < children.size - 1) {
                        result.append(",\n")
                    } else {
                        result.append("\n")
                    }
                }

                result.append("$indent  ]")
            }

            result.append("\n$indent}")
        }

        if (level == 0) {
            result.append("\n  ]")
        }

        return result
    }

    /**
     * Attempts to determine a meaningful type for a PSI element
     */
    private fun getElementType(element: Any?): String {
        return when {
            element == null -> "unknown"
            element.javaClass.simpleName.contains("Class", ignoreCase = true) -> "class"
            element.javaClass.simpleName.contains("Method", ignoreCase = true) -> "method"
            element.javaClass.simpleName.contains("Function", ignoreCase = true) -> "function"
            element.javaClass.simpleName.contains("Field", ignoreCase = true) -> "field"
            element.javaClass.simpleName.contains("Property", ignoreCase = true) -> "property"
            element.javaClass.simpleName.contains("Variable", ignoreCase = true) -> "variable"
            element.javaClass.simpleName.contains("Constant", ignoreCase = true) -> "constant"
            element.javaClass.simpleName.contains("Interface", ignoreCase = true) -> "interface"
            element.javaClass.simpleName.contains("Enum", ignoreCase = true) -> "enum"
            element.javaClass.simpleName.contains("Parameter", ignoreCase = true) -> "parameter"
            element.javaClass.simpleName.contains("Companion", ignoreCase = true) -> "companion"
            else -> "element"
        }
    }

    /**
     * Creates basic file information when structure view is not available
     */
    private fun createBasicFileInfo(psiFile: PsiFile): String {
        return """
        {
          "fileName": "${psiFile.name}",
          "fileType": "${psiFile.fileType.name}",
          "language": "${psiFile.language.displayName}",
          "elements": [],
          "note": "No structured view available for this file type"
        }
        """.trimIndent()
    }

    /**
     * Helper function to escape JSON strings
     */
    private fun String.escapeJson(): String {
        return this.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

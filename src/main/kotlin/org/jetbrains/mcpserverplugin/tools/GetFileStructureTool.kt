package org.jetbrains.mcpserverplugin.tools

import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.FileFinderUtils

@Serializable
data class GetFileStructureArgs(val pathInProject: String)

/**
 * A tool that returns the structure of a file using IntelliJ's Structure View API.
 * Extracts important structural elements from the file.
 */
class GetFileStructureTool : AbstractMcpTool<GetFileStructureArgs>() {
    override val name: String = "get_file_structure"
    override val description: String = """
        Returns the structure of a file as seen in IntelliJ's Structure View.

        <pathInProject> Path to the file, relative to project root

        get_file_structure = ({pathInProject: string}) => string | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: GetFileStructureArgs): Response {
        // Use the generic file finder to locate the file
        val findResult = FileFinderUtils.findFileInProject(project, args.pathInProject)
        
        return when (findResult) {
            is FileFinderUtils.FindFileResult.Found -> {
                try {
                    val virtualFile = findResult.virtualFile
                    val resolvedPath = findResult.resolvedPath
                    
                    // Add a note if we found the file by fallback search
                    val fallbackNote = if (!findResult.wasExactMatch) {
                        val projectPath = project.guessProjectDir()?.toNioPathOrNull()
                        val relativePath = projectPath?.relativize(resolvedPath)?.toString() ?: resolvedPath.toString()
                        "\n// Note: File found at alternate location: $relativePath"
                    } else ""
                    
                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                        ?: return Response(error = "couldn't parse file")

                    // Use the Structure View API to get the file structure
                    val structureResult = extractStructureFromFile(project, psiFile, fallbackNote)
                    Response(structureResult)
                } catch (e: Exception) {
                    Response(error = "Error analyzing file structure: ${e.message}")
                }
            }
            is FileFinderUtils.FindFileResult.NotFound -> {
                Response(error = findResult.error)
            }
        }
    }

    /**
     * Extracts the structure from a file using IntelliJ's Structure View API
     * and includes only the essential structural elements
     */
    private fun extractStructureFromFile(project: Project, psiFile: PsiFile, fallbackNote: String = ""): String {
        val builder = LanguageStructureViewBuilder.INSTANCE.getStructureViewBuilder(psiFile)
            ?: return createBasicFileInfo(psiFile, fallbackNote)

        if (builder !is TreeBasedStructureViewBuilder) {
            return createBasicFileInfo(psiFile, fallbackNote)
        }

        // Get the document for extracting content
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return createBasicFileInfo(psiFile, fallbackNote)

        val structureViewModel = builder.createStructureViewModel(null)
        val rootElement = structureViewModel.root

        val result = StringBuilder()
        
        // Add file name as the root element
        result.append("// Structure of ${psiFile.name}\n\n")

        // Process the structure tree
        processStructure(rootElement, result, document)

        // Add any fallback note
        if (fallbackNote.isNotEmpty()) {
            result.append(fallbackNote)
        }

        return result.toString()
    }

    /**
     * Process the structure elements
     */
    private fun processStructure(rootElement: TreeElement, result: StringBuilder, document: com.intellij.openapi.editor.Document) {
        val children = rootElement.children
        
        // Process children at top level
        children.forEach { child ->
            processElement(child, result, 0, document)
        }
    }
    
    /**
     * Process a single element in the structure and extract only its signature
     */
    private fun processElement(element: TreeElement, result: StringBuilder, level: Int, document: com.intellij.openapi.editor.Document) {
        val indent = "  ".repeat(level)
        
        // Handle non-PSI elements
        if (element !is PsiTreeElementBase<*>) {
            return
        }
        
        val presentableText = element.presentation.presentableText ?: "Unknown"
        val children = element.children
        val hasChildren = children.isNotEmpty()
        val value = element.value
        val elementType = getElementType(value)
        
        // Skip parameters, constructors and other less important elements to avoid clutter
        if (elementType in setOf("parameter", "constructor")) {
            return
        }
        
        // Get PSI element for text extraction
        val psiElement = value as? PsiElement ?: return
        
        // Get text range
        val textRange = psiElement.textRange ?: return
        
        // Calculate line numbers for extraction
        val startLine = document.getLineNumber(textRange.startOffset)
        
        // For most element types, we just want to extract the first line (declaration/signature)
        when (elementType) {
            "class", "interface", "enum" -> {
                // Extract just the class declaration line
                val lineStart = document.getLineStartOffset(startLine)
                val lineText = document.getText(
                    com.intellij.openapi.util.TextRange(
                        lineStart, 
                        document.getLineEndOffset(startLine)
                    )
                ).trim()
                
                result.append("$indent$lineText {\n")
                
                // Process children
                if (hasChildren) {
                    children.forEach { child ->
                        processElement(child, result, level + 1, document)
                    }
                }
                
                // Close the block
                result.append("$indent}\n\n")
            }
            "method", "function" -> {
                // Extract just function declaration
                extractFunctionSignature(document, startLine, result, indent)
            }
            "property", "field", "variable" -> {
                // Extract just the property declaration
                val lineStart = document.getLineStartOffset(startLine)
                val lineText = document.getText(
                    com.intellij.openapi.util.TextRange(
                        lineStart, 
                        document.getLineEndOffset(startLine)
                    )
                ).trim()
                
                result.append("$indent$lineText\n")
            }
            "companion" -> {
                // For companion objects
                result.append("${indent}companion object {\n")
                
                // Process children
                if (hasChildren) {
                    children.forEach { child ->
                        processElement(child, result, level + 1, document)
                    }
                }
                
                // Close the block
                result.append("$indent}\n")
            }
            // For other significant elements with children
            else -> {
                if (hasChildren && !presentableText.startsWith("@")) {
                    // Extract name only for container elements
                    result.append("$indent$presentableText {\n")
                    
                    children.forEach { child ->
                        processElement(child, result, level + 1, document)
                    }
                    
                    result.append("$indent}\n")
                }
            }
        }
    }
    
    /**
     * Extract just the function signature without the implementation
     */
    private fun extractFunctionSignature(
        document: com.intellij.openapi.editor.Document,
        startLine: Int,
        result: StringBuilder,
        indent: String
    ) {
        // Start with the first line
        var lineText = document.getText(
            com.intellij.openapi.util.TextRange(
                document.getLineStartOffset(startLine),
                document.getLineEndOffset(startLine)
            )
        ).trim()
        
        // Check if the signature is complete (has opening brace)
        if (!lineText.contains("{")) {
            result.append("$indent$lineText\n")
            return
        }
        
        // For functions with implementation on same line, strip the implementation
        val bracketIndex = lineText.indexOf("{")
        if (bracketIndex > 0) {
            lineText = lineText.substring(0, bracketIndex).trim()
        }
        
        result.append("$indent$lineText\n")
    }

    /**
     * Determines a meaningful type for a PSI element
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
            element.javaClass.simpleName.contains("Constructor", ignoreCase = true) -> "constructor"
            element.javaClass.simpleName.contains("Companion", ignoreCase = true) -> "companion"
            else -> "element"
        }
    }

    /**
     * Creates basic file information when structure view is not available
     */
    private fun createBasicFileInfo(psiFile: PsiFile, fallbackNote: String = ""): String {
        val result = StringBuilder()
        result.append("// Structure of ${psiFile.name} (${psiFile.fileType.name})\n")
        result.append("// No detailed structure available for this file type\n")
        
        if (fallbackNote.isNotEmpty()) {
            result.append(fallbackNote)
        }
        
        return result.toString()
    }
}
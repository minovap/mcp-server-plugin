package org.jetbrains.mcpserverplugin.tools

import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.TextRange
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
 * It filters the file to keep important structural elements while omitting implementation details.
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
                    
                    // Get the document to extract the file content
                    val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                        ?: return Response(error = "Could not get document for file: ${virtualFile.path}")
                    
                    // Get all lines from the document
                    val allLines = (0 until document.lineCount).map { lineNumber ->
                        document.getText(TextRange(
                            document.getLineStartOffset(lineNumber),
                            document.getLineEndOffset(lineNumber)
                        ))
                    }
                    
                    // Use the Structure View API to identify important lines
                    val structureBuilder = LanguageStructureViewBuilder.INSTANCE.getStructureViewBuilder(psiFile)
                        ?: return Response("// Structure of ${psiFile.name}\n// No structure view available for this file type" + fallbackNote)
                    
                    if (structureBuilder !is TreeBasedStructureViewBuilder) {
                        return Response("// Structure of ${psiFile.name}\n// No tree-based structure view available for this file type" + fallbackNote)
                    }
                    
                    // Create structure view model
                    val structureViewModel = structureBuilder.createStructureViewModel(null)
                    val rootElement = structureViewModel.root
                    
                    // Process structure to identify important lines using only metadata
                    val linesToKeep = identifyImportantLinesFromStructure(rootElement, document, psiFile)
                    
                    // Get the filtered lines
                    // Make sure we include the package and import statements
                    val filteredLines = mutableListOf<String>()
                    for (index in linesToKeep.sorted()) {
                        if (index < allLines.size) {
                            filteredLines.add(allLines[index])
                        }
                    }
                    
                    // Include the first line of any multiline structures
                    
                    structureViewModel.dispose()
                    
                    Response(filteredLines.joinToString("\n") + fallbackNote)
                } catch (e: Exception) {
                    Response(error = "Error getting file structure: ${e.message}")
                }
            }
            is FileFinderUtils.FindFileResult.NotFound -> {
                Response(error = findResult.error)
            }
        }
    }
    
    /**
     * Identifies important lines based solely on the structure view metadata
     */
    private fun identifyImportantLinesFromStructure(
        rootElement: TreeElement,
        document: com.intellij.openapi.editor.Document,
        psiFile: PsiFile
    ): Set<Int> {
        val linesToKeep = mutableSetOf<Int>()
        
        // We need to handle package and import statements specially since they might not be in structure view
        // but are important structural elements. Use PSI navigation instead of string matching.
        psiFile.acceptChildren(object : com.intellij.psi.PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                try {
                    // Check the element's type using class name to avoid dependencies
                    val elementClassName = element.javaClass.simpleName
                    
                    // Check if this is a package or import statement based on class name
                    // This uses PSI structure metadata, not string matching of content
                    if (elementClassName.contains("Package") || 
                        elementClassName.contains("Import") ||
                        elementClassName == "KtPackageDirective" || 
                        elementClassName == "KtImportDirective") {
                        
                        val lineNumber = document.getLineNumber(element.textRange.startOffset)
                        linesToKeep.add(lineNumber)
                    }
                    
                    // Continue visiting children
                    element.acceptChildren(this)
                } catch (e: Exception) {
                    // Skip elements that can't be processed
                }
            }
        })
        
        // Process structure elements
        processStructureElement(rootElement, document, linesToKeep)
        return linesToKeep
    }
    
    /**
     * Recursively processes structure elements to identify important lines using only metadata
     */
    private fun processStructureElement(
        element: TreeElement,
        document: com.intellij.openapi.editor.Document,
        linesToKeep: MutableSet<Int>
    ) {
        try {
            // Extract location from the element
            val location = when (element) {
                is PsiTreeElementBase<*> -> {
                    // For PSI-based elements, use the element's value to get location
                    (element.value as? PsiElement)?.textRange
                }
                else -> {
                    // For other types of elements, try to get presentation
                    val presentation = element.presentation
                    // Some structure view elements have navigation targets
                    // We'd use those to get the location, but for simplicity,
                    // we'll use the element's children if we have them
                    null
                }
            }
            
            // If we found a location, add the relevant lines
            if (location != null) {
                try {
                    val startLine = document.getLineNumber(location.startOffset)
                    linesToKeep.add(startLine)
                
                    // For class/interface declarations, check if they span multiple lines
                    // and include the opening line with braces if present
                    val endLine = document.getLineNumber(location.endOffset)
                    if (endLine > startLine && element.presentation?.presentableText?.contains("class") == true) {
                        // Include the opening brace line for classes and interfaces
                        linesToKeep.add(endLine)
                    }
                }
                catch (e: Exception) { /* Ignore errors in line calculation */ }
            }
        } catch (e: Exception) {
            // Skip elements that can't be processed
        }
        
        // Process children recursively regardless of whether we processed this element
        for (child in element.children) {
            processStructureElement(child, document, linesToKeep)
        }
    }
}

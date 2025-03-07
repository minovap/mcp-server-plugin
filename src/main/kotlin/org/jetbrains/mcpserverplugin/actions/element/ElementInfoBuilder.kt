package org.jetbrains.mcpserverplugin.actions.element

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import kotlin.math.max
import kotlin.math.min

/**
 * Utility for building information about code elements
 */
object ElementInfoBuilder {
    
    /**
     * Gets formatting element information including file path, hierarchy, and line number
     */
    fun getElementInfo(element: PsiElement, file: PsiFile): String {
        // Calculate line number
        val document = file.viewProvider.document
        val lineNumber = document?.getLineNumber(element.textOffset)?.plus(1) ?: -1
        val lineNumberText = if (lineNumber > 0) "Line: $lineNumber" else ""

        // Get the element hierarchy with descriptive types
        val hierarchyInfo = buildElementHierarchy(element, file)

        // Get relative file path
        val filePath = file.virtualFile?.path ?: file.name
        val basePath = file.project.basePath

        // Convert to relative path with a leading "./"
        val relativePath = if (basePath != null && filePath.startsWith(basePath)) {
            "./" + filePath.substring(basePath.length).removePrefix("/")
        } else {
            "./" + filePath.split("/").last() // Just the filename with "./" prefix
        }

        val fileInfo = "File: $relativePath"

        return """
$fileInfo
$hierarchyInfo
$lineNumberText
        """.trimMargin()
    }
    
    /**
     * Gets the surrounding code for the element (up to 'lineCount' lines above and below)
     */
    fun getSurroundingCode(element: PsiElement, file: PsiFile, editor: Editor?, lineCount: Int): String {
        val document = file.viewProvider.document ?: return ""
        
        // Get the element's line numbers
        val startOffset = element.textRange.startOffset
        val endOffset = element.textRange.endOffset
        val startLine = document.getLineNumber(startOffset)
        val endLine = document.getLineNumber(endOffset)
        
        // Calculate the range of lines to include
        val totalLines = document.lineCount
        val fromLine = max(0, startLine - lineCount)
        val toLine = min(totalLines - 1, endLine + lineCount)
        
        // Extract the text for these lines
        val fromOffset = document.getLineStartOffset(fromLine)
        val toOffset = document.getLineEndOffset(toLine)
        
        return document.getText(TextRange(fromOffset, toOffset))
    }

    /**
     * Builds a complete element hierarchy with descriptive types
     * Each level of hierarchy is on a new line
     * Example: 
     * Class: OuterClass
     * Function: methodName
     */
    private fun buildElementHierarchy(element: PsiElement, file: PsiFile): String {
        val hierarchy = mutableListOf<Pair<String, String>>()
        
        // Add the current element if it has a type and name
        val currentType = getElementType(element)
        val currentName = getElementName(element)
        if (currentType.isNotBlank() && !currentName.isNullOrBlank()) {
            hierarchy.add(Pair(currentType, currentName))
        }
        
        // Walk up the parent chain to build the hierarchy
        var parent = element.parent
        while (parent != null && parent != file) {
            val parentType = getElementType(parent)
            val parentName = getElementName(parent)
            
            if (parentType.isNotBlank() && !parentName.isNullOrBlank()) {
                // Only add unique entries to avoid repetition
                val entry = Pair(parentType, parentName)
                if (!hierarchy.contains(entry)) {
                    hierarchy.add(entry)
                }
            }
            
            parent = parent.parent
        }
        
        // Reverse the list to get outer-most to inner-most (top to bottom)
        hierarchy.reverse()
        
        // Format each entry and join with new lines
        return hierarchy.joinToString("\n") { (type, name) -> "$type: $name" }
    }
    
    /**
     * Determines the type of a PsiElement
     */
    private fun getElementType(element: PsiElement): String {
        val className = element.javaClass.simpleName
        
        return when {
            className.contains("Class") -> "Class"
            className.contains("Method") || className.contains("Function") -> "Function"
            className.contains("Field") || className.contains("Property") -> "Property"
            className.contains("Parameter") -> "Parameter"
            className.contains("Variable") -> "Variable"
            className.contains("Interface") -> "Interface"
            className.contains("Enum") -> "Enum"
            else -> ""
        }
    }

    /**
     * Attempts to extract the name from a PsiElement
     * Works with various types of elements from different languages
     */
    private fun getElementName(element: PsiElement): String? {
        // Try standard getName method first
        val nameMethod = element.javaClass.methods.find { it.name == "getName" }
        val name = nameMethod?.invoke(element)?.toString()

        if (!name.isNullOrBlank() && name != "null") {
            return name
        }

        // If getName doesn't work, try other common methods
        val possibleNameMethods = listOf(
            "getQualifiedName",  // For classes with packages
            "getIdentifyingElement", // For some structural elements
            "getNameIdentifier"  // Common in various languages
        )

        for (methodName in possibleNameMethods) {
            try {
                val method = element.javaClass.methods.find { it.name == methodName }
                val result = method?.invoke(element)

                if (result != null) {
                    // If the result is another PsiElement, try to get its text
                    if (result is PsiElement) {
                        val text = result.text
                        if (text.isNotBlank()) {
                            return text
                        }
                    } else {
                        // Otherwise use toString
                        val text = result.toString()
                        if (text.isNotBlank() && text != "null") {
                            return text
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore exceptions and try the next method
            }
        }

        return null
    }
}

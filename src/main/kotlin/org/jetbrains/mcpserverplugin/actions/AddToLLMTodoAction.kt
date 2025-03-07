package org.jetbrains.mcpserverplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilBase
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.Nullable
import org.jetbrains.mcpserverplugin.llmtodo.LLMTodoService
import org.jetbrains.mcpserverplugin.settings.PluginSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import kotlin.math.max
import kotlin.math.min

/**
 * Action that adds selected code to a LLM task and opens a scratch file
 * This action is available in the editor context menu
 */
class AddToLLMTodoAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = PsiUtilBase.getPsiFileInEditor(editor, project) ?: return
        
        // Get the selected code or try to find the current element under cursor
        val selectedElement = findElementAtCursor(editor, file)
        if (selectedElement == null) {
            Messages.showErrorDialog(
                project,
                "No suitable code element found at cursor position.", 
                "Use with LLM"
            )
            return
        }
        
        // Show dialog to get user input
        val dialog = LLMTodoDialog(project)
        if (dialog.showAndGet()) {
            val userInput = dialog.getUserInput()
            
            // Get element info and surrounding code for the todo
            val elementInfo = getElementInfo(selectedElement, file)
            val surroundingCode = getSurroundingCode(selectedElement, file, editor, 50)
            
            // Create the todo content
            val todoContent = createTodoContent(elementInfo, surroundingCode, userInput)
            
            // Copy to clipboard
            copyToClipboard(todoContent)
            
            // Open a scratch file with the content
            createScratchFile(project, todoContent)
        }
    }

    override fun update(e: AnActionEvent) {
        // Only enable the action if we're in an editor with a file
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        
        e.presentation.isEnabledAndVisible = project != null && editor != null
    }
    
    private fun findElementAtCursor(editor: Editor, file: PsiFile): PsiElement? {
        val selectionModel = editor.selectionModel
        val elementAtCursor: PsiElement?
        
        if (selectionModel.hasSelection()) {
            // If there's a selection, get it
            val startOffset = selectionModel.selectionStart
            elementAtCursor = file.findElementAt(startOffset)
        } else {
            // If there's no selection, get the element at cursor
            val offset = editor.caretModel.offset
            elementAtCursor = file.findElementAt(offset)
        }
        
        if (elementAtCursor == null) return null
        
        // Try to find a meaningful parent element (method, class, etc.)
        return findMeaningfulParent(elementAtCursor)
    }
    
    @Suppress("UNCHECKED_CAST")
    private fun findMeaningfulParent(element: PsiElement): PsiElement {
        // This looks for containing structural elements like methods, classes, fields, etc.
        // We use reflection to avoid direct dependencies on language-specific PSI elements
        
        // Try to find parents of various types - we need to use reflection to avoid
        // direct dependencies which might not be available at runtime
        val possibleParentTypes = listOf(
            "com.intellij.psi.PsiMethod",
            "com.intellij.psi.PsiClass",
            "com.intellij.psi.PsiField",
            "com.intellij.psi.PsiVariable", 
            "org.jetbrains.kotlin.psi.KtFunction",
            "org.jetbrains.kotlin.psi.KtClass",
            "org.jetbrains.kotlin.psi.KtProperty",
            "org.jetbrains.kotlin.psi.KtParameter"
        )
        
        // Try each type and return the first match
        for (typeName in possibleParentTypes) {
            try {
                val clazz = Class.forName(typeName)
                // Use as with @Suppress to silence the unchecked cast warning
                val parent = PsiTreeUtil.getParentOfType(element, clazz as Class<PsiElement>)
                if (parent != null) {
                    return parent
                }
            } catch (e: ClassNotFoundException) {
                // Type not available, try the next one
            } catch (e: ClassCastException) {
                // Shouldn't happen, but just in case
            }
        }
        
        // If no specific parent is found, try to get a reasonable container
        val container = PsiTreeUtil.getParentOfType(
            element,
            PsiElement::class.java,
            /* strict */ true
        )
        
        // Return the container, or if all else fails, the original element
        return container ?: element
    }
    
    private fun getElementInfo(element: PsiElement, file: PsiFile): String {
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
    private fun getSurroundingCode(element: PsiElement, file: PsiFile, editor: Editor, lineCount: Int): String {
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
        
        return document.getText(com.intellij.openapi.util.TextRange(fromOffset, toOffset))
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

    private fun createTodoContent(elementInfo: String, surroundingCode: String, userInput: String): String {
        // Get the template from settings
        val settings = service<PluginSettings>()
        val template = settings.llmPromptTemplate ?: DEFAULT_TEMPLATE
        
        // Replace placeholders with actual content
        return template
            .replace("{{TASK}}", userInput)
            .replace("{{CONTEXT}}", elementInfo)
            .replace("{{CODE}}", surroundingCode)
    }
    
    private fun copyToClipboard(content: String) {
        val selection = StringSelection(content)
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(selection, selection)
    }
    
    private fun createScratchFile(project: Project, content: String) {
        try {
            // Use the LLMTodoService to create a scratch file
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val fileName = "LLM_Scratchfile_$timestamp.md"
            
            val fileService = LLMTodoService.getInstance(project)
            val file = fileService.createScratchFile(fileName, content)
            
            if (file != null) {
                // Open the file in the editor
                FileEditorManager.getInstance(project).openFile(file, true)
                // Removed the confirmation dialog
            } else {
                Messages.showWarningDialog(
                    project,
                    "Could not create scratch file, but content has been copied to clipboard.",
                    "LLM Task Creation"
                )
            }
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                "Error creating scratch file: ${e.message}\nContent has been copied to clipboard.",
                "LLM Task Error"
            )
        }
    }
    
    /**
     * Dialog to get user input for the LLM Todo
     */
    private class LLMTodoDialog(project: Project) : DialogWrapper(project) {
        private val textArea = JBTextArea(8, 50)
        
        init {
            title = "Use with LLM"
            init()
            
            // Configure the input map to handle Shift+Enter for newlines
            textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-break")
            // Make Enter submit the dialog
            textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "enter-action")
            textArea.actionMap.put("enter-action", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    doOKAction()
                }
            })
        }
        
        fun getUserInput(): String {
            return textArea.text
        }
        
        @Nullable
        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout(0, 10))
            panel.border = JBUI.Borders.empty(10)
            
            val label = JBLabel("What would you like to do with this code?")
            panel.add(label, BorderLayout.NORTH)
            
            textArea.lineWrap = true
            textArea.wrapStyleWord = true
            
            // Using system defaults for text color instead of hard-coded colors
            textArea.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(textArea.foreground.darker(), 1),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)
            )
            
            // Add scrollbars
            val scrollPane = JBScrollPane(textArea)
            scrollPane.preferredSize = Dimension(500, 200)
            panel.add(scrollPane, BorderLayout.CENTER)
            
            // Update the note about keyboard shortcuts
            val noteLabel = JBLabel("Press Enter to submit, Shift+Enter for newlines")
            noteLabel.border = JBUI.Borders.emptyTop(5)
            panel.add(noteLabel, BorderLayout.SOUTH)
            
            return panel
        }
        
        override fun getPreferredFocusedComponent(): JComponent? {
            return textArea
        }
        
        override fun doValidate(): ValidationInfo? {
            return if (textArea.text.isBlank()) {
                ValidationInfo("Please enter a task description", textArea)
            } else {
                null
            }
        }
    }
    
    companion object {
        const val DEFAULT_TEMPLATE = """
Please analyze the code element referenced below and complete this task:

{{TASK}}

# Context
{{CONTEXT}}

```
{{CODE}}
```
        """
    }
}
package org.jetbrains.mcpserverplugin.actions.todo

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import org.jetbrains.mcpserverplugin.llmtodo.LLMTodoService
import org.jetbrains.mcpserverplugin.settings.PluginSettings
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Utility for creating LLM Todo content and handling operations like copying and file creation
 */
object LLMTodoContentCreator {
    
    /**
     * Creates the formatted content for the LLM task using the template from settings
     */
    fun createTodoContent(elementInfo: String, surroundingCode: String, userInput: String): String {
        // Get the template from settings
        val settings = service<PluginSettings>()
        val template = settings.llmPromptTemplate ?: DEFAULT_TEMPLATE
        
        // Replace placeholders with actual content
        return template
            .replace("{{TASK}}", userInput)
            .replace("{{CONTEXT}}", elementInfo)
            .replace("{{CODE}}", surroundingCode)
    }
    
    /**
     * Copies the content to the system clipboard
     */
    fun copyToClipboard(content: String) {
        val selection = StringSelection(content)
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(selection, selection)
    }
    
    /**
     * Creates a scratch file with the given content and opens it in the editor
     */
    fun createScratchFile(project: Project, content: String) {
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
     * Default template for LLM tasks
     */
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

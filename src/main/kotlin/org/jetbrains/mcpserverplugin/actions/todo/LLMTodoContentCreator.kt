package org.jetbrains.mcpserverplugin.actions.todo

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import org.jetbrains.mcpserverplugin.llmtodo.LLMTodoService
import org.jetbrains.mcpserverplugin.settings.PluginSettings
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Utility for creating LLM Todo content and handling operations like copying and file creation
 */
object LLMTodoContentCreator {
    
    /**
     * Creates the formatted content for the LLM task using the template from .llm/prompt-context.md
     */
    fun createTodoContent(elementInfo: String, surroundingCode: String, userInput: String, project: Project? = null): String {
        // Get the template from .llm/prompt-context.md file or use default
        val template = getPromptTemplate(project)
        
        // Replace placeholders with actual content
        return template
            .replace("{{TASK}}", userInput.trim())
            .replace("{{CONTEXT}}", elementInfo)
            .replace("{{CODE}}", surroundingCode)
    }
    
    /**
     * Gets the prompt template from .llm/prompt-context.md file or uses the default template
     */
    private fun getPromptTemplate(project: Project?): String {
        project?.let {
            val projectDir = project.guessProjectDir()
            val templatePath = projectDir?.path?.let { path -> Paths.get(path, ".llm", "prompt-context.md") }
            if (templatePath != null && Files.exists(templatePath)) {
                return Files.readString(templatePath)
            }
        }
        return DEFAULT_TEMPLATE
    }
    
    /**
     * Legacy method to support the old format - will be removed in future
     */
    @Deprecated("Use the createTodoContent with project parameter", ReplaceWith("createTodoContent(elementInfo, surroundingCode, userInput, null)"))
    fun createTodoContent(elementInfo: String, surroundingCode: String, userInput: String): String {
        return createTodoContent(elementInfo, surroundingCode, userInput, null)
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

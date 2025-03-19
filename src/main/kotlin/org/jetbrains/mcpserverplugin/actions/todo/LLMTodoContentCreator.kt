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

// Importing IntelliJ's markdown parsing and HTML generation packages
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * Utility for creating LLM Todo content and handling operations like copying and file creation.
 * 
 * Provides methods for creating formatted content, converting markdown to HTML or plain text,
 * and managing clipboard operations.
 */
object LLMTodoContentCreator {
    
    /**
     * Creates the formatted content for the LLM task using the selected template
     * 
     * @param elementInfo Information about the code element
     * @param surroundingCode The surrounding code context
     * @param userInput User's task description
     * @param project The project (used for resolving template paths)
     * @param templateName Optional template name to use
     */
    fun createTodoContent(elementInfo: String, surroundingCode: String, userInput: String, project: Project? = null, templateName: String? = null): String {
        // Get the template based on the provided template name
        val template = getPromptTemplate(project, templateName)
        
        // Build the context section, including code if provided
        val contextContent = if (surroundingCode.isNotEmpty()) {
            // Include the element info followed by a code block with the selected code
            "$elementInfo\n\nSelected code:\n```\n$surroundingCode\n```"
        } else {
            // Just include the element info without code
            elementInfo
        }
        
        // Replace placeholders with actual content
        return template
            .replace("{{TASK}}", userInput.trim())
            .replace("{{CONTEXT}}", contextContent)
            .replace("{{CODE}}", "") // Empty string for backward compatibility
    }
    
    /**
     * Gets the prompt template from the specified file or directory, or uses the default template
     * @param project The project for resolving template paths
     * @param templateName Optional template name; if specified, looks for a file with this name in the prompt-contexts directory
     */
    fun getPromptTemplate(project: Project?, templateName: String? = null): String {
        project?.let {
            val projectDir = project.guessProjectDir()
            projectDir?.path?.let { projectPath -> 
                // If a specific template is requested, look in prompt-contexts directory
                if (templateName != null && templateName.isNotEmpty()) {
                    val templatePath = Paths.get(projectPath, ".llm", "prompt-contexts", "$templateName.md")
                    if (Files.exists(templatePath)) {
                        return Files.readString(templatePath)
                    }
                }
                
                // Default to prompt-context.md if it exists
                val defaultTemplatePath = Paths.get(projectPath, ".llm", "prompt-context.md")
                if (Files.exists(defaultTemplatePath)) {
                    return Files.readString(defaultTemplatePath)
                }
            }
        }
        return DEFAULT_TEMPLATE
    }
    
    /**
     * Lists available prompt templates in the project
     * @param project The project for resolving template paths
     * @return A map of template names to their paths
     */
    fun listAvailableTemplates(project: Project?): Map<String, String> {
        val templates = mutableMapOf<String, String>()
        templates["Default"] = "default" // Always include the default template
        
        project?.let {
            val projectDir = project.guessProjectDir()
            projectDir?.path?.let { projectPath -> 
                // Add the default prompt-context.md if it exists
                val defaultTemplatePath = Paths.get(projectPath, ".llm", "prompt-context.md")
                if (Files.exists(defaultTemplatePath)) {
                    templates["prompt-context"] = "prompt-context"
                }
                
                // Look for templates in the prompt-contexts directory
                val promptContextsDir = Paths.get(projectPath, ".llm", "prompt-contexts")
                if (Files.exists(promptContextsDir) && Files.isDirectory(promptContextsDir)) {
                    try {
                        Files.list(promptContextsDir).forEach { path ->
                            if (Files.isRegularFile(path) && path.toString().endsWith(".md")) {
                                val fileName = path.fileName.toString()
                                val templateName = fileName.removeSuffix(".md")
                                templates[templateName] = templateName
                            }
                        }
                    } catch (e: Exception) {
                        // Log or handle exception as appropriate
                        println("Error listing templates: ${e.message}")
                    }
                }
            }
        }
        
        return templates
    }
    
    /**
     * Legacy method to support the old format - will be removed in future
     */
    @Deprecated("Use the createTodoContent with project parameter", ReplaceWith("createTodoContent(elementInfo, surroundingCode, userInput, null, null)"))
    fun createTodoContent(elementInfo: String, surroundingCode: String, userInput: String): String {
        return createTodoContent(elementInfo, surroundingCode, userInput, null, null)
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
     * Converts markdown content to HTML using IntelliJ's markdown parser.
     * 
     * @param markdown The markdown content to convert
     * @return The HTML representation of the markdown content with improved formatting
     */
    fun markdownToHtml(markdown: String): String {
        // Create a parser with the CommonMark flavor
        val flavour = CommonMarkFlavourDescriptor()
        val parser = MarkdownParser(flavour)
        
        // Parse the markdown into an AST
        val parsedTree = parser.buildMarkdownTreeFromString(markdown)
        
        // Generate HTML from the AST without wrapping it
        val htmlGenerator = HtmlGenerator(markdown, parsedTree, flavour, false)
        val htmlContent = htmlGenerator.generateHtml()
        
        // Strip body tags and improve formatting
        return cleanHtml(htmlContent)
    }
    
    /**
     * Cleans up the generated HTML by removing body tags and improving formatting.
     * Converts headers to bold paragraphs according to the desired format.
     *
     * @param html The raw HTML content to clean
     * @return The cleaned HTML content
     */
    private fun cleanHtml(html: String): String {
        return html
            // Remove body tags
            .replace("<body>", "")
            .replace("</body>", "")
            // Convert headers to bold paragraphs
            .replace("<h1>([^<]+)</h1>".toRegex()) { matchResult -> 
                "<p></p><p><b>${matchResult.groupValues[1]}</b></p><p></p>"
            }
            .replace("<h2>([^<]+)</h2>".toRegex()) { matchResult -> 
                "<p></p><p><b>${matchResult.groupValues[1]}</b></p><p></p>"
            }
            .replace("<h3>([^<]+)</h3>".toRegex()) { matchResult -> 
                "<p></p><p><b>${matchResult.groupValues[1]}</b></p><p></p>"
            }
            .replace("<h4>([^<]+)</h4>".toRegex()) { matchResult -> 
                "<p></p><p><b>${matchResult.groupValues[1]}</b></p><p></p>"
            }
            .replace("<h5>([^<]+)</h5>".toRegex()) { matchResult -> 
                "<p></p><p><b>${matchResult.groupValues[1]}</b></p><p></p>"
            }
            .replace("<h6>([^<]+)</h6>".toRegex()) { matchResult -> 
                "<p></p><p><b>${matchResult.groupValues[1]}</b></p><p></p>"
            }
            // Fix extra whitespace in code blocks
            .replace("<pre><code>\n\n", "<pre><code>")
            .replace("\n\n</code></pre>", "\n</code></pre>")
            // Add some spacing for readability
            .replace("</p>", "</p>\n")
            .replace("<hr />", "<hr />\n")
            .replace("</pre>", "</pre>\n")
            // Clean up any resulting multiple newlines
            .replace("\n{3,}".toRegex(), "\n\n")
            .trim()
    }
    
    /**
     * Removes markdown formatting and returns plain text.
     * 
     * @param markdown The markdown content to convert
     * @return The plain text version of the markdown content
     */
    fun markdownToPlainText(markdown: String): String {
        // Simple Markdown stripping - could be enhanced for more complex markdown
        return markdown
            // Remove headings
            .replace(Regex("^#{1,6}\\s+(.+)$", RegexOption.MULTILINE), "$1")
            // Remove bold/italic markers
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("\\*(.+?)\\*"), "$1")
            .replace(Regex("__(.+?)__"), "$1")
            .replace(Regex("_(.+?)_"), "$1")
            // Remove code blocks
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("`(.+?)`"), "$1")
            // Remove blockquotes
            .replace(Regex("^>\\s+(.+)$", RegexOption.MULTILINE), "$1")
            // Remove horizontal rules
            .replace(Regex("^---+$", RegexOption.MULTILINE), "")
            .replace(Regex("^\\*\\*\\*+$", RegexOption.MULTILINE), "")
            // Remove links but keep the link text
            .replace(Regex("\\[(.+?)\\]\\(.+?\\)"), "$1")
            // Clean up any extra blank lines
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
    
    /**
     * Creates HTML content for a LLM todo task.
     * This is a convenience method that first creates the content using the template
     * and then converts it to HTML.
     * 
     * @param elementInfo Information about the code element
     * @param surroundingCode The code surrounding the element
     * @param userInput User's task description
     * @param project Optional project reference for template resolution
     * @param templateName Optional template name to use
     * @return HTML representation of the LLM task
     */
    fun createHtmlTodoContent(elementInfo: String, surroundingCode: String, userInput: String, project: Project? = null, templateName: String? = null): String {
        val markdownContent = createTodoContent(elementInfo, surroundingCode, userInput, project, templateName)
        return markdownToHtml(markdownContent)
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
    """
}

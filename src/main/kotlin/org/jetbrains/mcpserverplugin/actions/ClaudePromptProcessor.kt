package org.jetbrains.mcpserverplugin.actions

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.mcpserverplugin.MCPWebSocketService
import org.jetbrains.mcpserverplugin.actions.todo.LLMTodoContentCreator
import org.jetbrains.mcpserverplugin.actions.ui.LLMTodoDialog
import org.jetbrains.mcpserverplugin.settings.PluginSettings

/**
 * Processes and formats prompts to be sent to Claude
 * This centralizes the logic for showing the dialog, processing user input, and sending content
 */
class ClaudePromptProcessor {
    companion object {
        private val json = Json { prettyPrint = true }
        
        /**
         * Process the LLM context and show dialog
         * 
         * @param project The current project
         * @param elementInfo Information about the context (code element or file)
         * @param surroundingCode The code context or empty string
         * @param dialog The configured dialog to show
         * @param preselectedTemplate Optional template to preselect
         */
        fun processContext(
            project: Project,
            elementInfo: String,
            surroundingCode: String,
            dialog: LLMTodoDialog
        ) {
            if (dialog.showAndGet()) {
                val userInput = dialog.getUserInput()
                val selectedTemplate = dialog.getSelectedTemplateName()
                
                // Create the todo content in HTML format
                val todoContent = LLMTodoContentCreator.createHtmlTodoContent(
                    elementInfo = elementInfo,
                    surroundingCode = surroundingCode,
                    userInput = userInput,
                    project = project,
                    templateName = selectedTemplate
                )
                
                // Get plugin settings
                val settings = service<PluginSettings>()
                
                // Copy to clipboard if enabled in settings
                if (settings.copyToClipboard) {
                    LLMTodoContentCreator.copyToClipboard(todoContent)
                }
                
                // Check if auto-send WebSocket message is enabled in settings
                if (settings.autoSendWebSocketMessage) {
                    // Send the content to WebSocket clients
                    sendToWebSocketClients(todoContent)
                }
            }
        }
        
        /**
         * Sends the task content to all connected WebSocket clients
         */
        private fun sendToWebSocketClients(content: String) {
            try {
                // Create a simple JSON object manually to ensure proper format
                val jsonMessage = """
                {
                    "type": "new-chat",
                    "content": ${json.encodeToString(content)}
                }
                """.trimIndent()
                
                // Send to all clients
                MCPWebSocketService.getInstance().sendMessageToAllClients(jsonMessage)
            } catch (e: Exception) {
                // Log error but don't block user flow
                println("Error sending message to WebSocket clients: ${e.message}")
            }
        }
    }
}
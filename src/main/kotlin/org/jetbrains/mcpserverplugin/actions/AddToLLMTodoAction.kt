package org.jetbrains.mcpserverplugin.actions
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.intellij.psi.util.PsiUtilBase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.mcpserverplugin.MCPWebSocketService
import org.jetbrains.mcpserverplugin.NewChatMessage
import org.jetbrains.mcpserverplugin.actions.element.CodeElementFinder
import org.jetbrains.mcpserverplugin.actions.element.ElementInfoBuilder
import org.jetbrains.mcpserverplugin.actions.todo.LLMTodoContentCreator
import org.jetbrains.mcpserverplugin.actions.ui.LLMTodoDialog
import org.jetbrains.mcpserverplugin.settings.PluginSettings
/**
 * Action that adds selected code to a LLM task and copies it to clipboard
 * This action is available in the editor context menu and copies the todo content
 * directly to the clipboard without creating a scratch file
 */
class AddToLLMTodoAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = PsiUtilBase.getPsiFileInEditor(editor, project) ?: return
        
        // Get the selected code or try to find the current element under cursor
        val selectedElement = CodeElementFinder.findElementAtCursor(editor, file)
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
            val elementInfo = ElementInfoBuilder.getElementInfo(selectedElement, file)
            val surroundingCode = ElementInfoBuilder.getSurroundingCode(selectedElement, file, editor, 50)
            
            // Create the todo content as HTML by default
            val todoContent = LLMTodoContentCreator.createHtmlTodoContent(elementInfo, surroundingCode, userInput, project)
            
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
    
    override fun update(e: AnActionEvent) {
        // Only enable the action if we're in an editor with a file
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        
        e.presentation.isEnabledAndVisible = project != null && editor != null
    }
    
    companion object {
        private val json = Json { prettyPrint = true }
        
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
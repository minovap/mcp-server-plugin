package org.jetbrains.mcpserverplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.mcpserverplugin.MCPWebSocketService
import org.jetbrains.mcpserverplugin.NewChatMessage
import org.jetbrains.mcpserverplugin.actions.todo.LLMTodoContentCreator
import org.jetbrains.mcpserverplugin.actions.ui.LLMTodoDialog
import org.jetbrains.mcpserverplugin.settings.PluginSettings
import java.io.File

/**
 * Action that adds a file or folder content to an LLM task and copies it to clipboard
 * This action is available in the project view context menu
 */
class AddFileToLLMAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return
        
        if (selectedFiles.isEmpty()) {
            Messages.showErrorDialog(
                project,
                "No files selected.",
                "Use with LLM"
            )
            return
        }
        
        // Show dialog to get user input
        val dialog = LLMTodoDialog(project)
        if (dialog.showAndGet()) {
            val userInput = dialog.getUserInput()
            
            // Process selected files
            val fileContents = buildFileContents(selectedFiles, project)
            
            // Create the todo content
            val todoContent = LLMTodoContentCreator.createTodoContent(
                elementInfo = buildElementInfo(fileContents),
                surroundingCode = buildSurroundingCode(fileContents),
                userInput = userInput,
                project = project
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
    
    override fun update(e: AnActionEvent) {
        // Enable the action as long as there's a project
        val project = e.project
        
        // Always enable the action when in a project
        e.presentation.isEnabledAndVisible = project != null
    }
    
    private fun buildFileContents(files: Array<VirtualFile>, project: Project): List<FileContent> {
        val result = mutableListOf<FileContent>()
        val projectRootPath = project.basePath
        
        for (file in files) {
            val absolutePath = file.path
            val relativePath = if (projectRootPath != null && absolutePath.startsWith(projectRootPath)) {
                absolutePath.substring(projectRootPath.length).removePrefix("/")
            } else {
                // If not in project or no project root, use absolute path
                absolutePath
            }
            
            if (file.isDirectory) {
                // For directories, we include a summary but not their content
                result.add(FileContent(relativePath, "Directory", isDirectory = true))
            } else {
                try {
                    // For regular files, include their content if they're not too large
                    if (file.length > MAX_FILE_SIZE) {
                        result.add(FileContent(relativePath, "File too large to include", isTooLarge = true))
                    } else {
                        val content = String(file.contentsToByteArray())
                        // Limit to first 50 lines
                        val limitedContent = content.lines().take(50).joinToString("\n")
                        result.add(FileContent(relativePath, limitedContent))
                    }
                } catch (e: Exception) {
                    result.add(FileContent(relativePath, "Could not read file: ${e.message}"))
                }
            }
        }
        
        return result
    }
    
    private fun buildElementInfo(fileContents: List<FileContent>): String {
        return fileContents.joinToString("\n") { 
            when {
                it.isDirectory -> "Directory: ${it.path}"
                it.isTooLarge -> "Large File: ${it.path}"
                else -> "File: ${it.path}"
            }
        }
    }
    
    private fun buildSurroundingCode(fileContents: List<FileContent>): String {
        return fileContents.filter { !it.isDirectory && !it.isTooLarge }
            .joinToString("\n\n") { 
                "# File: ${it.path}\n${it.content}" 
            }
    }
    
    companion object {
        private const val MAX_FILE_SIZE = 1024 * 1024 // 1MB max file size
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
    
    data class FileContent(
        val path: String,
        val content: String,
        val isDirectory: Boolean = false,
        val isTooLarge: Boolean = false
    )
}
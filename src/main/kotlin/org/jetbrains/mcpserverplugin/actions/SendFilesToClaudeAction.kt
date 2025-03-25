package org.jetbrains.mcpserverplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.mcpserverplugin.actions.todo.LLMTodoContentCreator
import org.jetbrains.mcpserverplugin.actions.ui.LLMTodoDialog
import java.io.File

/**
 * Action that sends file or folder references to Claude
 * This action is available in the project view context menu and sends
 * file information to Claude via WebSocket and/or copies to clipboard
 */
class SendFilesToClaudeAction(
    private val actionId: String = "org.jetbrains.mcpserverplugin.actions.SendFilesToClaudeAction",
    private val displayName: String = "Send Files to Claude",
    private val preselectedTemplate: String? = null,
    private val isNewChat: Boolean = true
) : AnAction(displayName), DumbAware {
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
        
        // Show dialog to get user input with selected files
        val dialog = LLMTodoDialog(
            project, 
            selectedFiles = selectedFiles, 
            includeCode = false,
            preselectedTemplate = preselectedTemplate
        )
        
        // Process selected files - only include file paths without content
        val fileContents = buildFileContents(selectedFiles, project, includeContent = false)
        
        // Build element info
        val elementInfo = buildElementInfo(fileContents)
        
        // Delegate to common handler
        ClaudePromptProcessor.processContext(project, elementInfo, "", dialog, isNewChat)
    }
    
    override fun update(e: AnActionEvent) {
        // Enable the action as long as there's a project
        val project = e.project
        
        // Always enable the action when in a project
        e.presentation.isEnabledAndVisible = project != null
    }
    
    private fun buildFileContents(
        files: Array<VirtualFile>, 
        project: Project, 
        maxLines: Int = 50, 
        includeContent: Boolean = true
    ): List<FileContent> {
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
            } else if (!includeContent) {
                // If we're only including file paths, add with empty content
                result.add(FileContent(relativePath, ""))
            } else {
                try {
                    // For regular files, include their content if they're not too large
                    if (file.length > MAX_FILE_SIZE) {
                        result.add(FileContent(relativePath, "File too large to include", isTooLarge = true))
                    } else {
                        val content = String(file.contentsToByteArray())
                        // Limit to specified number of lines
                        val limitedContent = content.lines().take(maxLines).joinToString("\n")
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
        const val MAX_FILE_SIZE = 1024 * 1024 // 1MB max file size
    }
    
    data class FileContent(
        val path: String,
        val content: String,
        val isDirectory: Boolean = false,
        val isTooLarge: Boolean = false
    )
}
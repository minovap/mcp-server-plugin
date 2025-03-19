package org.jetbrains.mcpserverplugin.llmtodo

import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Service to handle LLM todo file operations
 */
@Service(Service.Level.PROJECT)
class LLMTodoService(private val project: Project) {
    
    companion object {
        const val LLM_TODO_FILENAME = ".llmtodo"
        
        /**
         * Get the LLMTodoService instance for a project
         */
        fun getInstance(project: Project): LLMTodoService = project.service()
    }
    
    /**
     * Create a scratch file with the given content
     * 
     * @param fileName The name to give the scratch file
     * @param content The content to write to the file
     * @return The created VirtualFile, or null if creation failed
     */
    fun createScratchFile(fileName: String, content: String): VirtualFile? {
        // Determine if content looks like HTML
        val isHtml = content.trim().startsWith("<") && content.contains("</")
        
        // Create a scratch file using IntelliJ's ScratchFileService
        return ApplicationManager.getApplication().runWriteAction<VirtualFile> {
            try {
                // Find the appropriate language based on content
                val language = if (isHtml) {
                    Language.findLanguageByID("HTML") ?: Language.findLanguageByID("Markdown")
                } else {
                    Language.findLanguageByID("Markdown")
                }
                
                // Create the scratch file with the appropriate extension
                val finalFileName = if (isHtml && language?.id == "HTML") {
                    fileName.replace(".md", ".html")
                } else {
                    fileName
                }
                
                val scratchFile = ScratchRootType.getInstance().createScratchFile(
                    project,
                    finalFileName,
                    language,
                    content
                )
                
                return@runWriteAction scratchFile
            } catch (e: Exception) {
                // If the scratch file creation fails, fall back to creating a temp file
                return@runWriteAction createTempFile(fileName, content)
            }
        }
    }
    
    /**
     * Create a temporary file as a fallback if scratch file creation fails
     */
    private fun createTempFile(fileName: String, content: String): VirtualFile? {
        try {
            // Create a temp file
            val tempFile = FileUtil.createTempFile(
                "llm_task_",
                "." + fileName.substringAfterLast('.', "md"),
                true
            )
            
            // Write content to the file
            FileUtil.writeToFile(tempFile, content)
            
            // Refresh the VFS to make the file visible
            return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tempFile)
        } catch (e: IOException) {
            return null
        }
    }
    
    /**
     * Append content to the .llmtodo file
     * Returns true if successful, false otherwise
     */
    fun appendToLLMTodoFile(content: String): Boolean {
        val basePath = project.basePath ?: return false
        val todoFile = File(basePath, LLM_TODO_FILENAME)
        val fileExists = todoFile.exists()
        
        try {
            // Add timestamp and separator if needed
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val separator = if (fileExists) "\n\n---\n\n" else ""
            val timestampedContent = "$separator## Added: $timestamp\n\n$content"
            
            // Append content to file
            todoFile.appendText(timestampedContent)
            
            // Refresh VFS to make sure the file is visible in the IDE
            refreshVirtualFile(todoFile)
            
            return true
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * Get the .llmtodo file for the project
     * Returns null if the file doesn't exist or the project has no base path
     */
    fun getLLMTodoFile(): VirtualFile? {
        val basePath = project.basePath ?: return null
        val todoFile = File(basePath, LLM_TODO_FILENAME)
        if (!todoFile.exists()) {
            return null
        }
        
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(todoFile)
    }
    
    /**
     * Create the .llmtodo file if it doesn't exist
     * Returns the virtual file or null if creation failed
     */
    fun createLLMTodoFileIfNeeded(): VirtualFile? {
        val basePath = project.basePath ?: return null
        val todoFile = File(basePath, LLM_TODO_FILENAME)
        
        if (!todoFile.exists()) {
            try {
                todoFile.createNewFile()
                todoFile.writeText("# LLM Todo List\n\nThis file contains code elements and tasks for LLM processing.\n\n")
            } catch (e: Exception) {
                return null
            }
        }
        
        return refreshVirtualFile(todoFile)
    }
    
    /**
     * Refresh the virtual file in the IDE
     */
    private fun refreshVirtualFile(file: File): VirtualFile? {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        virtualFile?.refresh(false, false)
        return virtualFile
    }
}

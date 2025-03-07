package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.llmtodo.LLMTodoService

@Serializable
data class ManageLLMTodoArgs(
    val action: String, // "read", "append", "create", "scratch"
    val content: String? = null, // Content to append, if action is "append" or "scratch"
    val fileName: String? = null // Optional file name for scratch files
)

/**
 * Tool to interact with LLM tasks
 */
class ManageLLMTodoTool : AbstractMcpTool<ManageLLMTodoArgs>() {
    override val name: String = "manage_llm_todo"
    override val description: String = """
        Manages LLM todo tasks and scratch files.
        
        Actions:
        - "read": Reads the content of the .llmtodo file
        - "append": Appends content to the .llmtodo file
        - "create": Creates the .llmtodo file if it doesn't exist
        - "scratch": Creates a new scratch file with the given content
        
        <action> The action to perform: "read", "append", "create", or "scratch"
        <content> The content to add (required for "append" and "scratch" actions)
        <fileName> Optional file name for scratch files (only used with "scratch" action)
        
        manage_llm_todo = ({action: string, content?: string, fileName?: string}) => string | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: ManageLLMTodoArgs): Response {
        val todoService = LLMTodoService.getInstance(project)
        
        when (args.action.lowercase()) {
            "read" -> {
                val todoFile = todoService.getLLMTodoFile()
                    ?: return Response(error = "LLM Todo file not found")
                
                return try {
                    val content = String(todoFile.contentsToByteArray())
                    Response(content)
                } catch (e: Exception) {
                    Response(error = "Failed to read LLM Todo file: ${e.message}")
                }
            }
            
            "append" -> {
                if (args.content.isNullOrBlank()) {
                    return Response(error = "Content to append cannot be empty")
                }
                
                return if (todoService.appendToLLMTodoFile(args.content)) {
                    Response("Content appended to LLM Todo file")
                } else {
                    Response(error = "Failed to append to LLM Todo file")
                }
            }
            
            "create" -> {
                val file = todoService.createLLMTodoFileIfNeeded()
                return if (file != null) {
                    Response("LLM Todo file created or already exists")
                } else {
                    Response(error = "Failed to create LLM Todo file")
                }
            }
            
            "scratch" -> {
                if (args.content.isNullOrBlank()) {
                    return Response(error = "Content for scratch file cannot be empty")
                }
                
                val fileName = args.fileName ?: "LLM_Task_${System.currentTimeMillis()}.md"
                val file = todoService.createScratchFile(fileName, args.content)
                
                return if (file != null) {
                    Response("Scratch file created: ${file.name}")
                } else {
                    Response(error = "Failed to create scratch file")
                }
            }
            
            else -> {
                return Response(error = "Unknown action: ${args.action}. Supported actions: read, append, create, scratch")
            }
        }
    }
}

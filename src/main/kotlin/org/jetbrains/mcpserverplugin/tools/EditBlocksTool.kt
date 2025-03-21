package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Result of a single edit operation
 */
enum class EditResult {
    SUCCESS,
    FAILURE
}

/**
 * Represents a search-replace pair for file editing
 */
@Serializable
data class SearchReplace(
    val search: String,
    val replace: String
)

/**
 * Tool arguments for batch editing multiple files with multiple edits per file
 */
@Serializable
data class EditBlocksToolArgs(
    val edits: Map<String, List<SearchReplace>>
)

/**
 * Result type returned to the client
 */
@Serializable
data class EditBlocksResult(
    val results: Map<String, List<String>>
)

/**
 * Tool for batch editing files with advanced search-replace functionality.
 */
class EditBlocksTool : AbstractMcpTool<EditBlocksToolArgs>() {
    override val name: String = "edit_blocks"
    override val isClaudeCodeTool: Boolean = true
    override val description: String = """
        Apply surgical text replacements to files using edit blocks. Best for small changes (<20% of file size).
        Multiple blocks can be used for separate changes across multiple files.
        
        Example input format:
        {
            "path/to/file1.txt": [
                {"search": "text to find", "replace": "replacement text"},
                {"search": "another text", "replace": "another replacement"}
            ],
            "path/to/file2.txt": [
                {"search": "old code", "replace": "new code"}
            ]
        }
        
        Returns a map with the same structure where each edit is marked as "replace #[index] successful" or "replace #[index] failed"
    """.trimIndent()

    override fun handle(project: Project, args: EditBlocksToolArgs): Response {
        // Get project directory
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "Project directory not found")
        
        // Results map with same structure as input
        val results = mutableMapOf<String, List<String>>()
        
        // Process each file
        for ((filePath, edits) in args.edits) {
            val editResults = processFileEdits(projectDir, filePath, edits)
            results[filePath] = editResults
        }
        
        // Use kotlinx.serialization to convert to JSON string
        val result = EditBlocksResult(results)
        val json = Json { prettyPrint = true }
        val jsonString = json.encodeToString(result)
        
        return Response(jsonString)
    }
    
    private fun processFileEdits(projectDir: java.nio.file.Path, filePath: String, edits: List<SearchReplace>): List<String> {
        // Determine the file path
        val resolvedPath = try {
            val path = Paths.get(filePath)
            if (path.isAbsolute) {
                path
            } else {
                projectDir.resolve(path)
            }
        } catch (e: Exception) {
            return edits.mapIndexed { index, _ -> "replace #$index failed: Invalid file path" }
        }
        
        // Check if file exists
        if (!Files.exists(resolvedPath)) {
            return edits.mapIndexed { index, _ -> "replace #$index failed: File does not exist" }
        }
        
        // Process each edit in sequence
        val results = mutableListOf<String>()
        var currentContent = try {
            Files.readString(resolvedPath)
        } catch (e: Exception) {
            return edits.mapIndexed { index, _ -> "replace #$index failed: Error reading file - ${e.message}" }
        }
        
        // Apply each edit in sequence
        for ((index, edit) in edits.withIndex()) {
            try {
                // Count occurrences of search string
                val count = currentContent.split(edit.search).size - 1
                
                if (count == 0) {
                    results.add("replace #$index failed: Search text not found")
                    continue
                }
                
                if (count > 1) {
                    results.add("replace #$index failed: Search text appears $count times - must be unique")
                    continue
                }
                
                // Apply the edit
                currentContent = currentContent.replace(edit.search, edit.replace)
                results.add("replace #$index successful")
            } catch (e: Exception) {
                results.add("replace #$index failed: ${e.message}")
            }
        }
        
        // Write the final content back to the file
        try {
            Files.writeString(
                resolvedPath,
                currentContent,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
        } catch (e: Exception) {
            // If writing fails, mark remaining edits as failed
            val lastSuccessIndex = results.indexOfLast { it.contains("successful") }
            for (i in (lastSuccessIndex + 1) until edits.size) {
                results[i] = "replace #$i failed: Error writing to file - ${e.message}"
            }
        }
        
        return results
    }
}

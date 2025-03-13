package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.Pattern

/**
 * Tool for searching file contents using regular expressions.
 * 
 * This tool can handle codebases of any size.
 * It supports standard regex patterns like "log.*Error", "function\\s+\\w+", etc.
 * Results are sorted by modification time (most recent first).
 */
@Serializable
data class GrepToolArgs(
    val pattern: String,
    val path: String? = null,
    val include: String? = null
)

class ClaudeCodeGrepTool : AbstractMcpTool<GrepToolArgs>() {
    override val name: String = "grep"
    override val isClaudeCodeTool: Boolean = true
    override val description: String = """
        - Fast content search tool that works with any codebase size
        - Searches file contents using regular expressions
        - Supports full regex syntax (eg. "log.*Error", "function\s+\w+", etc.)
        - Filter files by pattern with the include parameter (eg. "*.js", "*.{ts,tsx}")
        - Returns matching file paths sorted by modification time
        - Use this tool when you need to find files containing specific patterns
        
        grep = ({pattern: string, path?: string, include?: string}) => string[] | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: GrepToolArgs): Response {
        // Determine the base path
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "project dir not found")
        
        val basePath = if (args.path.isNullOrBlank()) {
            projectDir
        } else {
            val relativePath = Paths.get(args.path)
            if (relativePath.isAbsolute) {
                relativePath
            } else {
                projectDir.resolve(relativePath)
            }
        }

        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            return Response(error = "Path does not exist or is not a directory: $basePath")
        }

        try {
            // Compile the regex pattern
            val regex = Pattern.compile(args.pattern)
            
            // Create a PathMatcher for the include pattern if specified
            val includePattern = if (args.include != null) {
                FileSystems.getDefault().getPathMatcher("glob:${args.include}")
            } else {
                null
            }
            
            // Walk the file tree and collect matching files with their modification times
            val matchingFiles = mutableListOf<Pair<Path, Long>>()
            
            Files.walk(basePath).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .forEach { path ->
                        // Get relative path from the base directory
                        val relativePath = basePath.relativize(path)
                        
                        // Check if the file matches the include pattern (if any)
                        if (includePattern == null || includePattern.matches(relativePath)) {
                            // Check if file content matches the regex pattern
                            try {
                                val content = Files.readString(path)
                                if (regex.matcher(content).find()) {
                                    // Get file attributes to determine modification time
                                    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                                    matchingFiles.add(Pair(relativePath, attrs.lastModifiedTime().toMillis()))
                                }
                            } catch (e: Exception) {
                                // Skip files that can't be read
                            }
                        }
                    }
            }
            
            // Sort by modification time (newest first)
            matchingFiles.sortByDescending { it.second }
            
            // Limit results to 100 files
            val truncated = matchingFiles.size > 100
            val limitedMatchingFiles = if (truncated) matchingFiles.take(100) else matchingFiles
            
            // Convert to relative paths as strings
            val result = limitedMatchingFiles.map { it.first.toString() }.toMutableList()
            if (truncated) {
                result.add("(Results are truncated. Consider using a more specific pattern or path.)")
            }
            
            // Create a JSON array response
            val jsonArray = result.joinToString(",", prefix = "[", postfix = "]") { "\"$it\"" }
            return Response(jsonArray)
        } catch (e: Exception) {
            return Response(error = "Error searching files: ${e.message}")
        }
    }
}

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

/**
 * Tool for fast file pattern matching using glob patterns.
 * 
 * This tool can handle codebases of any size by leveraging Java's Path Matcher.
 * It supports standard glob patterns like * * / *.js or src/ * * / *.ts.
 * Results are sorted by modification time (most recent first).
 */
@Serializable
data class GlobToolArgs(
    val pattern: String,
    val path: String? = null
)

class ClaudeCodeGlobTool : AbstractMcpTool<GlobToolArgs>() {
    override val name: String = "glob"
    override val isClaudeCodeTool: Boolean = true
    override val description: String = """
        - Fast file pattern matching tool that works with any codebase size
        - Supports glob patterns like "**/*.js" or "src/**/*.ts"
        - Returns matching file paths sorted by modification time
        - Use this tool when you need to find files by name patterns
        
        glob = ({pattern: string, path?: string}) => string[] | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: GlobToolArgs): Response {
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
            // Create a PathMatcher with the given glob pattern
            val matcher = FileSystems.getDefault().getPathMatcher("glob:${args.pattern}")
            
            // Walk the file tree and collect matching files with their modification times
            val matchingFiles = mutableListOf<Pair<Path, Long>>()
            
            Files.walk(basePath).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .forEach { path ->
                        // Match against the relative path from the base directory
                        val relativePath = basePath.relativize(path)
                        if (matcher.matches(relativePath)) {
                            // Get file attributes to determine modification time
                            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                            matchingFiles.add(Pair(relativePath, attrs.lastModifiedTime().toMillis()))
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
                result.add("(Results are truncated. Consider using a more specific path or pattern.)")
            }
            
            // Create a JSON array response
            val jsonArray = result.joinToString(",", prefix = "[", postfix = "]") { "\"$it\"" }
            return Response(jsonArray)
        } catch (e: Exception) {
            return Response(error = "Error searching files: ${e.message}")
        }
    }
}

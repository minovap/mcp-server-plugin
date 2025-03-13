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
 * Tool for listing files and directories in a given path.
 */
@Serializable
data class LsToolArgs(
    val path: String,
    val ignore: List<String>? = null
)

class ClaudeCodeLsTool : AbstractMcpTool<LsToolArgs>() {
    override val name: String = "ls"
    override val isClaudeCodeTool: Boolean = true
    override val description: String = """
        Lists files and directories in a given path. The path parameter must be an absolute path, not a relative path. 
        You can optionally provide an array of glob patterns to ignore with the ignore parameter. 
        You should generally prefer the Glob and Grep tools, if you know which directories to search.
        
        ls = ({path: string, ignore?: string[]}) => string[] | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: LsToolArgs): Response {
        // Determine the base path
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "project dir not found")
        
        val basePath = try {
            val path = Paths.get(args.path)
            if (path.isAbsolute) {
                path
            } else {
                projectDir.resolve(path)
            }
        } catch (e: Exception) {
            return Response(error = "Invalid path: ${args.path}")
        }

        if (!Files.exists(basePath)) {
            return Response(error = "Path does not exist: $basePath")
        }

        if (!Files.isDirectory(basePath)) {
            return Response(error = "Path is not a directory: $basePath")
        }

        try {
            // Create PathMatchers for ignore patterns
            val ignoreMatchers = args.ignore?.map { pattern ->
                FileSystems.getDefault().getPathMatcher("glob:$pattern")
            } ?: emptyList()
            
            // List all files and directories in the path
            val entries = Files.list(basePath).use { stream ->
                stream.filter { path -> 
                    // Skip if path matches any ignore pattern
                    ignoreMatchers.none { matcher -> 
                        matcher.matches(basePath.relativize(path))
                    }
                }.map { path ->
                    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
                    val isDir = attributes.isDirectory
                    val name = path.fileName.toString()
                    val size = if (isDir) -1 else attributes.size()
                    val lastModified = attributes.lastModifiedTime().toMillis()
                    
                    // Format: name, isDirectory, size, lastModified
                    "\"$name\": {\"isDirectory\": $isDir, \"size\": $size, \"lastModified\": $lastModified}"
                }.toList()
            }
            
            // Create a JSON object response
            val jsonObject = entries.joinToString(",", prefix = "{", postfix = "}")
            return Response(jsonObject)
        } catch (e: Exception) {
            return Response(error = "Error listing directory: ${e.message}")
        }
    }
}

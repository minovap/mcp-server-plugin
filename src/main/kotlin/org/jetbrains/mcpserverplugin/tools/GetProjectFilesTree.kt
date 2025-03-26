package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.packageDependencies.DependencyValidationManager
import com.intellij.psi.search.scope.packageSet.NamedScope
import com.intellij.psi.search.scope.packageSet.NamedScopeManager
import com.intellij.psi.search.scope.packageSet.PackageSetBase
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.LogCollector
import org.jetbrains.ide.mcp.NoArgs

/**
 * Tool for listing files in a project scope
 */

class GetProjectFilesTree : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_project_files_tree"
    override val isClaudeCodeTool: Boolean = true
    override val description: String = """
        Primary initialization tool - call immediately when working with a blank/new context window. 
        Retrieves hierarchical file structure from IntelliJ "llm" scope containing only critical project files. 
        If scope missing, advise user to create "llm" scope in IntelliJ project settings and add essential files.
        This tool prevents unnecessary exploration of dependencies/build files and ensures relevance of all code suggestions. 
        Use as first operation for any project analysis.
        
        get_project_files_tree = () => {
            type: "scope_status", 
            found: boolean,
            files?: Array<{name: string, type: "file" | "directory", children?: Array<...>}>
        } | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        // Create log collector
        val logCollector = LogCollector()
        // Get project root directory
        val projectDir = project.guessProjectDir() ?: return Response(error = "project dir not found")
        logCollector.info("Project directory: ${projectDir.path}")
        
        // Find the "llm" scope
        val localScopesManager = NamedScopeManager.getInstance(project)
        val sharedScopesManager = DependencyValidationManager.getInstance(project)
        
        // Look for "llm" scope in both local and shared scopes
        val llmScope = findScope(localScopesManager.editableScopes, sharedScopesManager.editableScopes)
        
        if (llmScope == null) {
            // No "llm" scope found
            return Response("""
                {
                    "type": "scope_status",
                    "found": false
                }
            """.trimIndent())
        }
        
        // Get the package set (scope definition)
        val packageSet = llmScope.value as? PackageSetBase 
            ?: return Response(error = "Invalid scope definition for ${llmScope.scopeId}")
        
        try {
            // Collect files in the scope
            val rootNode = TreeNode(name = projectDir.name, path = projectDir.path, isDirectory = true)
            collectFilesInScope(project, projectDir, rootNode, packageSet)
            
            // Count files and directories
            val (fileCount, dirCount) = countFilesAndDirs(rootNode, 0, 0)
            logCollector.info("Found $fileCount files and $dirCount directories in scope")
            
            // Convert to JSON and return
            return Response("""
                {
                    "type": "scope_status",
                    "found": true,
                    "files": ${rootNode.toJson()}
                }
            """.trimIndent(), logs = logCollector.getMessages())
        } catch (e: Exception) {
            logCollector.error("Exception during scope processing: ${e.message}")
            logCollector.error("${e.stackTraceToString()}")
            return Response(error = "Error processing scope: ${e.message}", logs = logCollector.getMessages())
        }
    }
    
    /**
     * Find a scope named "llm" (or variants) in the available scopes
     */
    private fun findScope(vararg scopeLists: Array<out NamedScope>): NamedScope? {
        // Try exact match for "llm" first
        for (scopes in scopeLists) {
            scopes.find { it.scopeId.lowercase() == "llm" }?.let { return it }
        }
        
        // Try case-insensitive "llm" variations like "LLM", "Llm", etc.
        for (scopes in scopeLists) {
            scopes.find { it.scopeId.equals("llm", ignoreCase = true) }?.let { return it }
        }
        
        // Try scopes containing "llm" like "my-llm-scope"
        for (scopes in scopeLists) {
            scopes.find { it.scopeId.contains("llm", ignoreCase = true) }?.let { return it }
        }
        
        return null
    }
    
    /**
     * Recursively collect files that are in the specified scope.
     * This method needs to be wrapped in a ReadAction because it accesses project indexes
     * and model data structures, which require read access for thread safety.
     */
    private fun collectFilesInScope(project: Project, root: VirtualFile, parentNode: TreeNode, packageSet: PackageSetBase) {
        // Wrap the entire process in a read action to ensure thread safety when accessing project model
        com.intellij.openapi.application.ReadAction.compute<Unit, RuntimeException> {
        VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Void>() {
            override fun visitFile(file: VirtualFile): Boolean {
                // Check if the file is inside or outside the project directory
                val projectRootDir = root
                val isInProject = isFileInProject(file, projectRootDir)
                
                if (isInProject) {
                    // Only check isInContent for files inside the project directory
                    val fileIndex = ProjectRootManager.getInstance(project).fileIndex
                    if (!fileIndex.isInContent(file)) {
                        return false // Skip excluded files inside the project
                    }
                } else {
                    // For files outside the project, we don't do the exclusion check
                    // but we generally don't want to include them unless explicitly requested
                    return false
                }
                
                // Check if the file is in the scope - this is safe because we're already in a ReadAction context
                val isInScope = packageSet.contains(file, project, null)
                
                if (isInScope) {
                    // Handle directories separately - we want to add directories even if they're not
                    // explicitly in the scope, as long as they contain files that are in the scope
                    if (file.isDirectory) {
                        // Create directory in tree if it contains files in scope
                        val relativePath = VfsUtilCore.getRelativePath(file, root, '/')
                        if (relativePath != null) {
                            addDirectoryPath(parentNode, root.path, relativePath, file.path)
                        }
                        return true // Continue traversal
                    } else {
                        // Add file to the appropriate directory node
                        val parentPath = file.parent ?: return true
                        val relativeDirPath = VfsUtilCore.getRelativePath(parentPath, root, '/')
                        
                        if (relativeDirPath != null) {
                            // First ensure the directory path exists
                            val dirNode = addDirectoryPath(parentNode, root.path, relativeDirPath, parentPath.path)
                            
                            // Then add the file
                            dirNode.children.add(TreeNode(name = file.name, path = file.path, isDirectory = false))
                        } else {
                            // File is directly under root
                            parentNode.children.add(TreeNode(name = file.name, path = file.path, isDirectory = false))
                        }
                    }
                }
                
                // Continue traversal even if this file wasn't in scope
                return true
            }
        })
        } // End of ReadAction.compute
    }
    
    /**
     * Add a directory path to the tree, creating intermediate directories as needed
     * @return The leaf directory node
     */
    private fun addDirectoryPath(parentNode: TreeNode, rootPath: String, relativePath: String, @Suppress("UNUSED_PARAMETER") fullPath: String): TreeNode {
        if (relativePath.isEmpty()) {
            return parentNode
        }
        
        var currentNode = parentNode
        val pathParts = relativePath.split('/')
        
        for (i in pathParts.indices) {
            val part = pathParts[i]
            val pathSoFar = pathParts.take(i + 1).joinToString("/")
            val currentPath = if (rootPath.endsWith("/")) "$rootPath$pathSoFar" else "$rootPath/$pathSoFar"
            
            val existingChild = currentNode.children.find { it.name == part && it.isDirectory }
            if (existingChild != null) {
                currentNode = existingChild
            } else {
                val newNode = TreeNode(name = part, path = currentPath, isDirectory = true)
                currentNode.children.add(newNode)
                currentNode = newNode
            }
        }
        
        return currentNode
    }
    
    /**
     * Helper class to represent the file tree
     */
    private class TreeNode(val name: String, val path: String, val isDirectory: Boolean) {
        val children = mutableListOf<TreeNode>()
        
        fun toJson(): String {
            val escapedName = name.replace("\"", "\\\"").replace("\n", "\\n")
            
            val childrenJson = if (children.isEmpty()) "" else 
                ", \"children\": [${children.joinToString(", ") { it.toJson() }}]"
            
            return "{\"name\": \"$escapedName\", \"type\": \"${if (isDirectory) "directory" else "file"}\"$childrenJson}"
        }
    }
    
    /**
     * Helper method to count files and directories in the tree
     */
    /**
     * Check if a file is within the project directory
     */
    private fun isFileInProject(file: VirtualFile, projectDir: VirtualFile): Boolean {
        val filePath = file.path.replace("\\", "/")
        val projectPath = projectDir.path.replace("\\", "/")
        return filePath.startsWith(projectPath)
    }
    
    private fun countFilesAndDirs(node: TreeNode, fileCount: Int, dirCount: Int): Pair<Int, Int> {
        var files = fileCount
        var dirs = dirCount
        
        if (node.isDirectory) {
            dirs++
            for (child in node.children) {
                val (f, d) = countFilesAndDirs(child, 0, 0)
                files += f
                dirs += d
            }
        } else {
            files++
        }
        
        return Pair(files, dirs)
    }
}
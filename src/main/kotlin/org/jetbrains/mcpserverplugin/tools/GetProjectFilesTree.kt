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

/**
 * Tool for listing files in a project scope
 */
@Serializable
data class GetProjectFilesTreeArgs(
    val dummy: String = "" // Needed because Kotlin serialization doesn't support empty data classes
)

class GetProjectFilesTree : AbstractMcpTool<GetProjectFilesTreeArgs>() {
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

    override fun handle(project: Project, args: GetProjectFilesTreeArgs): Response {
        // Get project root directory
        val projectDir = project.guessProjectDir() ?: return Response(error = "project dir not found")
        
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
            
            // Convert to JSON and return
            return Response("""
                {
                    "type": "scope_status",
                    "found": true,
                    "files": ${rootNode.toJson()}
                }
            """.trimIndent())
        } catch (e: Exception) {
            return Response(error = "Error processing scope: ${e.message}")
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
     * Recursively collect files that are in the specified scope
     */
    private fun collectFilesInScope(project: Project, root: VirtualFile, parentNode: TreeNode, packageSet: PackageSetBase) {
        VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Void>() {
            override fun visitFile(file: VirtualFile): Boolean {
                // Skip non-content files
                val fileIndex = ProjectRootManager.getInstance(project).fileIndex
                if (!fileIndex.isInContent(file)) {
                    return false
                }
                
                // Check if the file is in the scope
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
}

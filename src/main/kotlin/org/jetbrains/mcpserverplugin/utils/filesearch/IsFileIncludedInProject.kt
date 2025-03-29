package org.jetbrains.mcpserverplugin.utils.filesearch

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Check if a file is included in the project (not excluded).
 */
internal fun FileSearch.isFileIncludedInProject(file: VirtualFile, project: Project): Boolean {
    // Always return true for node_modules directory inside the project directory
    val filePath = file.path.replace("\\", "/")
    val projectBasePath = project.basePath?.replace("\\", "/")
    
    if (projectBasePath != null && filePath.startsWith(projectBasePath) && filePath.contains("/node_modules")) {
        return true
    }
    
    return runReadAction {
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        fileIndex.isInContent(file)
    }
}
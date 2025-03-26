package org.jetbrains.mcpserverplugin.utils.filesearch

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Check if a file is included in the project (not excluded).
 */
internal fun FileSearch.isFileIncludedInProject(file: VirtualFile, project: Project): Boolean {
    return runReadAction {
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        fileIndex.isInContent(file)
    }
}
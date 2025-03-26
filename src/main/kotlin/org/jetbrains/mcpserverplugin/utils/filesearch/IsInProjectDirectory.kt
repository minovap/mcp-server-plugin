package org.jetbrains.mcpserverplugin.utils.filesearch

import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Check if a file is inside the project directory.
 */
internal fun FileSearch.isInProjectDirectory(file: VirtualFile, projectDir: Path): Boolean {
    val filePath = file.path.replace("\\", "/")
    val projectPath = projectDir.toString().replace("\\", "/")
    
    // Make sure projectPath ends with a slash to prevent partial directory matches
    // This prevents matching something like /User/project/foo with /User/project_backup/
    
    // Check if the file path starts with the project path
    return filePath.startsWith(projectPath)
}
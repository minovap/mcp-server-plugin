package org.jetbrains.mcpserverplugin.utils

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Finds a file in the project by name, with smart fallback to search throughout the project
 * if the file is not found at the exact specified path.
 *
 * @param project The IntelliJ project
 * @param pathInProject Path to the file relative to project root
 * @return A Result object containing either the found file or an error message
 */
object FileFinderUtils {
    private val LOG = logger<FileFinderUtils>()

    sealed class FindFileResult {
        data class Found(val virtualFile: VirtualFile, val resolvedPath: Path, val wasExactMatch: Boolean) : FindFileResult()
        data class NotFound(val error: String) : FindFileResult()
    }

    fun findFileInProject(project: Project, pathInProject: String): FindFileResult {
        return runReadAction {
            try {
                val projectDir = project.guessProjectDir()?.toNioPathOrNull()
                    ?: return@runReadAction FindFileResult.NotFound("Project directory not found")

                LOG.info("Looking for file: $pathInProject in project dir: $projectDir")
                
                // First check if the file exists at the exact path
                val exactPath = projectDir.resolveRel(pathInProject)
                LOG.info("Exact path to check: $exactPath")
                
                // Check if the file physically exists
                val fileExists = Files.exists(exactPath)
                LOG.info("File exists at path: $fileExists")
                
                val fileSystem = LocalFileSystem.getInstance()
                fileSystem.refresh(false, true)  // Force refresh the file system
                
                val exactFile = fileSystem.refreshAndFindFileByNioFile(exactPath)
                if (exactFile != null) {
                    LOG.info("File found at exact path")
                    return@runReadAction FindFileResult.Found(exactFile, exactPath, true)
                }
                
                LOG.info("File not found at exact path, trying fallback search")
                
                // Extract the file name from the path
                val fileName = Paths.get(pathInProject).fileName?.toString() ?: ""
                LOG.info("Extracted filename: $fileName")
                
                // Skip the fallback search if fileName is empty or the path seems to be pointing to a directory
                if (fileName.isEmpty() || pathInProject.endsWith("/")) {
                    return@runReadAction FindFileResult.NotFound("File not found: $pathInProject")
                }

                // Search files with the same name in the project
                val candidateFiles = findFilesByName(project, projectDir, fileName)
                LOG.info("Found ${candidateFiles.size} candidate files with name: $fileName")
                
                when {
                    candidateFiles.isEmpty() -> {
                        FindFileResult.NotFound("File not found: $pathInProject")
                    }
                    candidateFiles.size == 1 -> {
                        // If only one file with that name exists, use it
                        val file = candidateFiles.first()
                        val vPath = file.path
                        LOG.info("Using single candidate file found at: $vPath")
                        
                        val resolvedPath = file.toNioPathOrNull() ?: exactPath
                        FindFileResult.Found(file, resolvedPath, false)
                    }
                    else -> {
                        // Multiple files found - this is ambiguous
                        val paths = candidateFiles.map { it.path }.joinToString(", ")
                        LOG.info("Multiple files with name $fileName found: $paths")
                        
                        FindFileResult.NotFound(
                            "Multiple files named '$fileName' found in project: $paths. " +
                            "Please specify the exact path."
                        )
                    }
                }
            } catch (e: Exception) {
                LOG.error("Error finding file", e)
                FindFileResult.NotFound("Error finding file: ${e.message}")
            }
        }
    }

    /**
     * Finds all files with the given name in the project directory and its subdirectories.
     */
    private fun findFilesByName(project: Project, projectDir: Path, fileName: String): List<VirtualFile> {
        val fileSystem = LocalFileSystem.getInstance()
        val projectVirtualDir = fileSystem.findFileByNioFile(projectDir)
        
        if (projectVirtualDir == null) {
            LOG.warn("Could not find virtual directory for project path: $projectDir")
            return emptyList()
        }
        
        // Ensure the virtual directory is up to date
        VfsUtil.markDirtyAndRefresh(false, true, true, projectVirtualDir)
        
        val result = mutableListOf<VirtualFile>()
        findFilesRecursively(projectVirtualDir, fileName, result)
        
        LOG.info("Found ${result.size} files with name '$fileName' in the project")
        return result
    }

    /**
     * Recursively searches for files with the specified name.
     */
    private fun findFilesRecursively(dir: VirtualFile, fileName: String, result: MutableList<VirtualFile>) {
        for (child in dir.children) {
            if (child.isDirectory) {
                findFilesRecursively(child, fileName, result)
            } else if (child.name == fileName) {
                LOG.info("Found matching file: ${child.path}")
                result.add(child)
            }
        }
    }
}

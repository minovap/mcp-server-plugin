package org.jetbrains.mcpserverplugin.utils.filesearch

// No need for com.intellij.openapi.diagnostic.logger anymore
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.ProjectScope
import org.jetbrains.mcpserverplugin.utils.LogCollector
import org.jetbrains.mcpserverplugin.utils.PathShortener
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Stream

// Using LogCollector for all logging

/**
 * Data class to hold a path and whether it is a directory.
 */
data class PathEntry(val path: Path, val isDirectory: Boolean)

/**
 * List files in a directory.
 * Returns a stream of PathEntry objects representing the entries in the directory.
 * Each PathEntry contains the path and a boolean indicating whether it's a directory.
 * Note: This does not filter out excluded files/directories. That should be done by the caller.
 */
internal fun FileSearch.listFilesInDirectory(dir: Path, logs: LogCollector = LogCollector()): Stream<PathEntry> {
    // Standard directory listing
    return try {
        val path = dir.toAbsolutePath().toString()
        if ("!" in path) {
            val parts = path.split("!", limit = 2)
            val filePath = parts[0]
            val insidePath = parts.getOrNull(1) ?: ""

            val archiveUrl = StandardFileSystems.JAR_PROTOCOL_PREFIX + filePath + "!" + insidePath;
            val archiveVirtualFile = VirtualFileManager.getInstance().findFileByUrl(archiveUrl)

            if (archiveVirtualFile != null) {
                if (archiveVirtualFile.isDirectory) {
                    // Convert VirtualFile children to PathEntry objects
                    return archiveVirtualFile.children.map { childFile ->
                        // Construct the path for each child
                        val childEntryPath = if (insidePath.isEmpty()) {
                            childFile.name
                        } else {
                            "$insidePath/${childFile.name}"
                        }
                        // Format: filePath!/entryPath/childName
                        val p = Path.of(filePath + JarFileSystem.JAR_SEPARATOR + childEntryPath);
                        val isDir = childFile.isDirectory

                        PathEntry(p, isDir)
                    }.stream()
                } else {
                    logs.error("Path does not point to a directory in archive: $insidePath")
                    return Stream.empty<PathEntry>()
                }
            }
        }

        // Convert stream of Paths to stream of PathEntries with directory info
        Files.list(dir).map { filesListPath ->
            val isDir = try {
                Files.isDirectory(filesListPath)
            } catch (e: Exception) {
                logs.warn("Could not determine if path is directory: $filesListPath")
                false
            }
            PathEntry(filesListPath, isDir)
        }
    } catch (e: Exception) {
        logs.error("Could not list files in directory: $dir", e)
        // If we can't list the directory, return empty stream
        Stream.empty<PathEntry>()
    }
}

/**
 * List files in a virtual file directory.
 * This utility function handles listing children of a VirtualFile.
 */
fun FileSearch.listFilesInVirtualDirectory(dir: VirtualFile): List<VirtualFile> {
    return if (dir.isDirectory) {
        dir.children.toList()
    } else {
        emptyList()
    }
}
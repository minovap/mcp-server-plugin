package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.resolveRel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

@Serializable
data class ListFilesInFolderArgs(
    val pathInProject: String,
    val recursive: Boolean = false,
    val maxFiles: Int = 100
)

data class StructureResult(val json: String, val warningAdded: Boolean)

class ListFilesInFolderTool : AbstractMcpTool<ListFilesInFolderArgs>() {
    override val name: String = "list_files_in_folder"
    override val description: String = """
      Lists all files and directories in a project folder, helping you explore the project structure.

      <pathInProject> Path relative to project root (use "/" for project root)
      <recursive> Whether to list files and directories recursively (default: false)
      <maxFiles> Maximum number of files to return (default: 100)

      list_files_in_folder = ({pathInProject: string, recursive?: boolean, maxFiles?: number}) => Array<{ name: string; type: "f" | "d"; path: string; lineCount?: number }> | { error: string };
  """.trimIndent()

    // Define blacklisted folder names.
    private val blacklistedFolders = setOf("node_modules")

    override fun handle(project: Project, args: ListFilesInFolderArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")

        return runReadAction {
            try {
                val targetDir = projectDir.resolveRel(args.pathInProject)
                if (!Files.exists(targetDir)) {
                    return@runReadAction Response(error = "directory not found")
                }
                // Global counter to limit the number of files (and directories) processed.
                val count = AtomicInteger(0)
                val resultJson = if (args.recursive) {
                    buildNestedStructure(targetDir, projectDir, args.maxFiles, count).json
                } else {
                    // Non-recursive flat listing.
                    val entries = targetDir.listDirectoryEntries()
                        .filter { entry ->
                            if (Files.isDirectory(entry)) {
                                val entryName = entry.name
                                !entryName.startsWith(".") && entryName !in blacklistedFolders
                            } else true
                        }
                        .mapNotNull { entry ->
                            if (count.get() >= args.maxFiles) null else {
                                val type = if (Files.isDirectory(entry)) "d" else "f"
                                val relativePath = projectDir.relativize(entry).toString()
                                var extra = ""
                                if (type == "f") {
                                    extra = getLineCountField(entry)
                                }
                                count.incrementAndGet()
                                """{"name": "${entry.name}", "type": "$type", "path": "$relativePath"$extra}"""
                            }
                        }
                    // If not all entries were processed in this folder, add a warning here.
                    val allEntries = targetDir.listDirectoryEntries().filter { entry ->
                        if (Files.isDirectory(entry)) {
                            val entryName = entry.name
                            !entryName.startsWith(".") && entryName !in blacklistedFolders
                        } else true
                    }.size
                    val resultList = if (count.get() >= args.maxFiles && entries.size < allEntries) {
                        entries.toMutableList().apply {
                            add("""{"warning": "Reached maximum file count limit of ${args.maxFiles} while listing the files in this folder"}""")
                        }
                    } else {
                        entries
                    }
                    resultList.joinToString(",\n", prefix = "[", postfix = "]")
                }
                Response(resultJson)
            } catch (e: Exception) {
                Response(error = "Error listing directory: ${e.message}")
            }
        }
    }

    private fun buildNestedStructure(dir: Path, projectDir: Path, maxFiles: Int, count: AtomicInteger): StructureResult {
        // If already reached the global limit, return empty list with no warning at this level.
        if (count.get() >= maxFiles) return StructureResult("[]", false)
        val result = StringBuilder()
        result.append("[")
        val filteredEntries = try {
            dir.listDirectoryEntries().filter { entry ->
                if (Files.isDirectory(entry)) {
                    val entryName = entry.name
                    !entryName.startsWith(".") && entryName !in blacklistedFolders
                } else true
            }
        } catch (e: Exception) {
            return StructureResult("""[{"error": "Error reading directory: ${e.message}"}]""", false)
        }
        var isFirst = true
        var truncatedLocal = false
        var warningAddedInChild = false
        for (entry in filteredEntries) {
            if (count.get() >= maxFiles) {
                truncatedLocal = true
                break
            }
            if (!isFirst) result.append(",\n") else isFirst = false

            val name = entry.name
            val type = if (Files.isDirectory(entry)) "d" else "f"
            result.append("""{"name": "$name", "type": "$type"""")

            if (type == "f") {
                val extra = getLineCountField(entry)
                result.append(extra)
                count.incrementAndGet()
            } else {
                // For a directory, count it first.
                count.incrementAndGet()
                // Only traverse further if the global limit has not been reached.
                val childResult = buildNestedStructure(entry, projectDir, maxFiles, count)
                // Append children if any.
                if (childResult.json != "[]" && childResult.json.isNotBlank()) {
                    result.append(""", "children": ${childResult.json}""")
                }
                // If a warning was added in the child's subtree, record that.
                if (childResult.warningAdded) {
                    warningAddedInChild = true
                }
            }
            result.append("}")
        }
        // Only add a warning at this level if we truncated locally and no warning was added deeper.
        if (truncatedLocal && !warningAddedInChild) {
            if (!isFirst) result.append(",\n")
            result.append(
                """{"warning": "Reached maximum file count limit of $maxFiles while listing the files in this folder"}"""
            )
            return StructureResult(result.append("]").toString(), true)
        }
        result.append("]")
        return StructureResult(result.toString(), warningAddedInChild)
    }

    private fun getLineCountField(file: Path): String {
        // Only count lines for files under 1MB that appear to be text.
        val maxSize = 1024 * 1024L
        return try {
            val fileSize = Files.size(file)
            if (fileSize <= maxSize && isTextFile(file)) {
                val lineCount = Files.lines(file).use { it.count() }
                """, "lineCount": $lineCount"""
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun isTextFile(file: Path): Boolean {
        // Heuristic: check first 1024 bytes for null characters.
        return try {
            Files.newInputStream(file).use { stream ->
                val buffer = ByteArray(1024)
                val bytesRead = stream.read(buffer)
                if (bytesRead == -1) return true
                for (i in 0 until bytesRead) {
                    if (buffer[i] == 0.toByte()) return false
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}

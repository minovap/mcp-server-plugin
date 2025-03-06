package org.jetbrains.mcpserverplugin
import com.intellij.openapi.diagnostic.logger

import com.intellij.execution.ProgramRunnerUtil.executeConfiguration
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance
import com.intellij.find.FindManager
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManager.getInstance
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readText
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.usages.FindUsagesProcessPresentation
import com.intellij.usages.UsageViewPresentation
import com.intellij.util.Processor
import com.intellij.util.application
import org.jetbrains.mcpserverplugin.settings.PluginSettings
import com.intellij.util.io.createParentDirectories
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import java.nio.file.Path
import java.nio.file.Files
import kotlin.io.path.*
import java.util.concurrent.atomic.AtomicInteger
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import java.io.BufferedReader
import java.io.InputStreamReader

import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiElement
import java.io.File
import java.nio.file.Paths
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key

// At the top of your file
private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(SafeTerminalCommandExecute::class.java)

fun Path.resolveRel(pathInProject: String): Path {
    return when (pathInProject) {
        "/" -> this
        else -> resolve(pathInProject.removePrefix("/"))
    }
}

fun Path.relativizeByProjectDir(projDir: Path?): String =
    projDir?.relativize(this)?.pathString ?: this.absolutePathString()

class GetCurrentFileTextTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_open_in_editor_file_text"
    override val description: String = """
        Retrieves the text content of the currently active file.

        get_open_in_editor_file_text = () => string;
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val text = runReadAction<String?> {
            getInstance(project).selectedTextEditor?.document?.text
        }
        return Response(text ?: "")
    }
}


class SafeTerminalCommandExecute : AbstractMcpTool<SafeTerminalCommandArgs>() {
    override val name: String = "safe_terminal_command_execute"
    override val description: String = """
        Safely executes a terminal command in a gitpod/workspace-full Docker container.
        This provides a permissionless and sandboxed environment for running commands safely.
        
        <command> Shell command to execute in the container
        
        safe_terminal_command_execute = ({command: string}) => string | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: SafeTerminalCommandArgs): Response {
        // Get project root directory
        val projectDir = runReadAction<String?> {
            project.guessProjectDir()?.toNioPathOrNull()?.toString()
        } ?: return Response(error = "Could not determine project root directory")
        
        // Get docker image from settings
        val dockerImage = com.intellij.openapi.components.service<PluginSettings>().state.dockerImage ?: "gitpod/workspace-full"

        // Find docker executable path
        val dockerPath = findDockerExecutable()
            ?: return Response(error = "Docker executable not found. Make sure Docker is installed and in PATH.")

        // Build the Docker command with gitpod/workspace-full image
        val dockerCommand = listOf(
            dockerPath,
            "run",
            "--platform",
            "linux/amd64",
            "--user",
            "root",
            "--rm",
            "-v", "$projectDir:$projectDir",
            "-w", projectDir,
            dockerImage,
            "bash", "-c", args.command
        )

        // Create the full docker command string for output
        val dockerCommandString = dockerCommand.joinToString(" ") {
            if (it.contains(" ") || it.contains("\"") || it.contains("'")) "\"$it\"" else it
        }

        return try {
            // Execute the command
            val process = ProcessBuilder(dockerCommand)
                .redirectErrorStream(true)
                .start()

            // Read the output
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            // Wait for the process to complete
            val exitCode = process.waitFor()

            // Format the output as requested
            val formattedOutput = buildString {
                append(dockerCommandString)
                append("\n----\n")
                append(output)
                if (exitCode != 0) {
                    append("\nExit code: ").append(exitCode)
                }
            }

            Response(formattedOutput)
        } catch (e: Exception) {
            Response(error = "Error executing Docker command: ${e.message}")
        }
    }

    /**
     * Attempts to find the Docker executable in common locations and PATH
     * @return Full path to docker executable or null if not found
     */
    private fun findDockerExecutable(): String? {
        // Check common Docker installation paths
        val commonPaths = listOf(
            "/usr/bin/docker",
            "/usr/local/bin/docker",
            "/opt/homebrew/bin/docker",  // macOS Homebrew on Apple Silicon
            "/usr/local/Cellar/docker",  // Other Homebrew location
            "C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe"  // Windows
        )

        for (path in commonPaths) {
            if (File(path).exists()) {
                return path
            }
        }

        // Check PATH environment
        val pathEnv = System.getenv("PATH") ?: return null
        val pathDirs = pathEnv.split(File.pathSeparator)

        for (dir in pathDirs) {
            val dockerPath = Paths.get(dir, "docker").toString()
            val dockerExePath = Paths.get(dir, "docker.exe").toString()

            if (File(dockerPath).exists()) {
                return dockerPath
            }
            if (File(dockerExePath).exists()) {
                return dockerExePath
            }
        }

        // Try using "which docker" or "where docker" to find Docker
        try {
            val command = if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                listOf("where", "docker")
            } else {
                listOf("which", "docker")
            }

            // Create a command line with console environment
            val commandLine = GeneralCommandLine(command)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

            // Create a process handler
            val processHandler = OSProcessHandler(commandLine)
            val output = StringBuilder()

            // Add a process listener to capture output
            processHandler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (outputType === ProcessOutputTypes.STDOUT) {
                        output.append(event.text)
                    }
                }
            })

            // Start and wait for process
            processHandler.startNotify()
            if (processHandler.waitFor(5000)) {
                val exitCode = processHandler.exitCode ?: -1
                val outputString = output.toString().trim()

                if (exitCode == 0 && outputString.isNotEmpty()) {
                    return outputString
                } else {
                    LOG.warn("Docker not found. Output: ${outputString.ifEmpty { "null" }}, Exit code: $exitCode")
                    return null
                }
            } else {
                LOG.warn("Process timed out while searching for Docker")
                processHandler.destroyProcess()
                return null
            }
        } catch (e: Exception) {
            // Ignore exceptions from which/where commands
        }

        return null
    }
}

@Serializable
data class SafeTerminalCommandArgs(
    val command: String
)

class GetCurrentFilePathTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_open_in_editor_file_path"
    override val description: String = """
        Retrieves the path of the currently active file.

        get_open_in_editor_file_path = () => string;
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val path = runReadAction<String?> {
            getInstance(project).selectedTextEditor?.virtualFile?.path
        }
        return Response(path ?: "")
    }
}

class GetProjectRootPathTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_project_root_path"
    override val description: String = """
        Retrieves the project's root directory path.

        get_project_root_path = () => string;
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val path = runReadAction<String?> {
            project.guessProjectDir()?.path
        }
        return Response(path ?: "error: could not determine root")
    }
}

class GetAllOpenFileTextsTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_all_open_file_texts"
    override val description: String = """
        Returns text of all currently open files in the editor.

        get_all_open_file_texts = () => Array<{ path: string; text: string }>;
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()

        val fileEditorManager = FileEditorManager.getInstance(project)
        val openFiles = fileEditorManager.openFiles
        val filePaths = openFiles.mapNotNull { """{"path": "${it.toNioPath().relativizeByProjectDir(projectDir)}", "text": "${it.readText()}", """ }
        return Response(filePaths.joinToString(",\n", prefix = "[", postfix = "]"))
    }
}

class GetAllOpenFilePathsTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_all_open_file_paths"
    override val description: String = """
        Lists all currently open files in the editor.

        get_all_open_file_paths = () => string;
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()

        val fileEditorManager = FileEditorManager.getInstance(project)
        val openFiles = fileEditorManager.openFiles
        val filePaths = openFiles.mapNotNull { it.toNioPath().relativizeByProjectDir(projectDir) }
        return Response(filePaths.joinToString("\n"))
    }
}

@Serializable
data class OpenFileInEditorArgs(val filePath: String)

class OpenFileInEditorTool : AbstractMcpTool<OpenFileInEditorArgs>() {
    override val name: String = "open_file_in_editor"
    override val description: String = """
        Opens a file in the JetBrains IDE editor.

        <filePath> Path to the file (absolute or relative to project root)

        open_file_in_editor = ({filePath: string}) => "file is opened" | { error: "file doesn't exist or can't be opened" };
    """.trimIndent()

    override fun handle(project: Project, args: OpenFileInEditorArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")

        val file = LocalFileSystem.getInstance().findFileByPath(args.filePath)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectDir.resolveRel(args.filePath))

        return if (file != null && file.exists()) {
            invokeLater {
                FileEditorManager.getInstance(project).openFile(file, true)
            }
            Response("file is opened")
        } else {
            Response("file doesn't exist or can't be opened")
        }
    }
}
class GetSelectedTextTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_selected_in_editor_text"
    override val description: String = """
        Retrieves the currently selected text from the active editor.

        get_selected_in_editor_text = () => string;
    """

    override fun handle(project: Project, args: NoArgs): Response {
        val text = runReadAction<String?> {
            getInstance(project).selectedTextEditor?.selectionModel?.selectedText
        }
        return Response(text ?: "")
    }
}

@Serializable
data class ReplaceSelectedTextArgs(val text: String)

class ReplaceSelectedTextTool : AbstractMcpTool<ReplaceSelectedTextArgs>() {
    override val name: String = "replace_selected_text"
    override val description: String = """
        Replaces the currently selected text in the active editor.

        <text> Replacement content

        replace_selected_text = ({text: string}) => "ok" | { error: "no text selected" | "unknown error" };
    """.trimIndent()

    override fun handle(project: Project, args: ReplaceSelectedTextArgs): Response {
        var response: Response? = null

        application.invokeAndWait {
            runWriteCommandAction(project, "Replace Selected Text", null, {
                val editor = getInstance(project).selectedTextEditor
                val document = editor?.document
                val selectionModel = editor?.selectionModel
                if (document != null && selectionModel != null && selectionModel.hasSelection()) {
                    document.replaceString(selectionModel.selectionStart, selectionModel.selectionEnd, args.text)
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                    response = Response("ok")
                } else {
                    response = Response(error = "no text selected")
                }
            })
        }

        return response ?: Response(error = "unknown error")
    }}

@Serializable
data class ReplaceCurrentFileTextArgs(val text: String)

class ReplaceCurrentFileTextTool : AbstractMcpTool<ReplaceCurrentFileTextArgs>() {
    override val name: String = "replace_current_file_text"
    override val description: String = """
        Replaces the content of the currently active file.

        <text> New content for the file

        replace_current_file_text = ({text: string}) => "ok" | { error: "no file open" | "unknown error" };
    """

    override fun handle(project: Project, args: ReplaceCurrentFileTextArgs): Response {
        var response: Response? = null
        application.invokeAndWait {
            runWriteCommandAction(project, "Replace File Text", null, {
                val editor = getInstance(project).selectedTextEditor
                val document = editor?.document
                if (document != null) {
                    document.setText(args.text)
                    response = Response("ok")
                } else {
                    response = Response(error = "no file open")
                }
            })
        }
        return response ?: Response(error = "unknown error")
    }
}

@Serializable
data class CreateNewFileWithTextArgs(val pathInProject: String, val text: String)

class CreateNewFileWithTextTool : AbstractMcpTool<CreateNewFileWithTextArgs>() {
    override val name: String = "create_new_file_with_text"
    override val description: String = """
        Creates a new file and populates it with text.

        <pathInProject> Relative path where the file should be created
        <text> Content to write into the new file

        create_new_file_with_text = ({pathInProject: string, text: string}) => "ok" | { error: "can't find project dir" };
    """

    override fun handle(project: Project, args: CreateNewFileWithTextArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")

        val path = projectDir.resolveRel(args.pathInProject)
        if (!path.exists()) {
            path.createParentDirectories().createFile()
        }
        val text = args.text
        path.writeText(text.unescape())
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)

        return Response("ok")
    }
}

private fun String.unescape(): String = removePrefix("<![CDATA[").removeSuffix("]]>")

@Serializable
data class Query(val nameSubstring: String)

class FindFilesByNameSubstring : AbstractMcpTool<Query>() {
    override val name: String = "find_files_by_name_substring"
    override val description: String = """
        Searches for files by name substring (case-insensitive).

        <nameSubstring> Search term to match in filenames

        find_files_by_name_substring = ({nameSubstring: string}) => Array<{ path: string; name: string }> | { error: string };
    """

    override fun handle(project: Project, args: Query): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "project dir not found")

        val searchSubstring = args.nameSubstring.toLowerCase()
        return runReadAction {
            Response(
                FilenameIndex.getAllFilenames(project)
                    .filter { it.toLowerCase().contains(searchSubstring) }
                    .flatMap {
                        FilenameIndex.getVirtualFilesByName(it, GlobalSearchScope.projectScope(project))
                    }
                    .filter { file ->
                        try {
                            projectDir.relativize(Path(file.path))
                            true
                        } catch (e: IllegalArgumentException) {
                            false
                        }
                    }
                    .map { file ->
                        val relativePath = projectDir.relativize(Path(file.path)).toString()
                        """{"path": "$relativePath", "name": "${file.name}"}"""
                    }
                    .joinToString(",\n", prefix = "[", postfix = "]")
            )
        }
    }
}


@Serializable
data class GetFilesTextByPathArgs(
    val pathsInProject: List<String>,
    val headLines: Int = 0  // default to 0 which means return all content
)

class GetFileTextByPathTool : AbstractMcpTool<GetFilesTextByPathArgs>() {
    override val name: String = "get_file_text_by_path"
    override val description: String = """
        Retrieves the text content of one or multiple files.

        <pathsInProject> List of paths to the files, relative to project root
        <headLines> Optional. Number of lines to return from the beginning of each file.
                   Default is 0, which returns the entire file.

        get_files_text_by_path = ({pathsInProject: string[], headLines?: number}) => {[filePath: string]: string} | { error: string };
    """

    override fun handle(project: Project, args: GetFilesTextByPathArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "project dir not found")

        val result = mutableMapOf<String, String>()
        var hasError = false
        var errorMessage = ""

        for (pathInProject in args.pathsInProject) {
            runReadAction {
                val file = LocalFileSystem.getInstance()
                    .refreshAndFindFileByNioFile(projectDir.resolveRel(pathInProject))

                if (file == null || !GlobalSearchScope.allScope(project).contains(file)) {
                    hasError = true
                    errorMessage = "file not found: $pathInProject"
                    return@runReadAction
                }

                val fileContent = if (args.headLines > 0) {
                    // Read only specified number of lines
                    file.readText().lines().take(args.headLines).joinToString("\n")
                } else {
                    // Read entire file
                    file.readText()
                }

                result[pathInProject] = fileContent
            }

            // Break early if an error is encountered
            if (hasError) break
        }

        return if (hasError) {
            Response(error = errorMessage)
        } else {
            // Convert the map to a JSON string
            val jsonResult = result.entries.joinToString(",\n", prefix = "{", postfix = "}") { (path, content) ->
                "\"$path\": ${content.escapeForJson()}"
            }
            Response(jsonResult)
        }
    }

    // Helper function to properly escape the content for JSON
    private fun String.escapeForJson(): String {
        val escaped = this.replace("\\", "\\\\")
                         .replace("\"", "\\\"")
                         .replace("\n", "\\n")
                         .replace("\r", "\\r")
                         .replace("\t", "\\t")
        return "\"$escaped\""
    }
}

@Serializable
data class ReplaceTextByPathToolArgs(val pathInProject: String, val text: String)

class ReplaceTextByPathTool : AbstractMcpTool<ReplaceTextByPathToolArgs>() {
    override val name: String = "replace_file_text_by_path"
    override val description: String = """
        Replaces the entire content of a file with new text.

        <pathInProject> Path to the target file, relative to project root
        <text> New content to write to the file

        replace_file_text_by_path = ({pathInProject: string, text: string}) => "ok" | { error: "project dir not found" | "file not found" | "could not get document" };
    """

    override fun handle(project: Project, args: ReplaceTextByPathToolArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "project dir not found")

        var document: Document? = null

        val readResult = runReadAction {
            var file: VirtualFile = LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(projectDir.resolveRel(args.pathInProject))
                ?: return@runReadAction "file not found"

            if (!GlobalSearchScope.allScope(project).contains(file)) {
                return@runReadAction "file not found"
            }

            document = FileDocumentManager.getInstance().getDocument(file)
            if (document == null) {
                return@runReadAction "could not get document"
            }

            return@runReadAction "ok"
        }

        if (readResult != "ok") {
            return Response(error = readResult)
        }

        WriteCommandAction.runWriteCommandAction(project) {
            document!!.setText(args.text)
            FileDocumentManager.getInstance().saveDocument(document!!)
        }

        return Response("ok")
    }
}

/*
@Serializable
data class ListFilesInFolderArgs(val pathInProject: String)

class ListFilesInFolderTool : AbstractMcpTool<ListFilesInFolderArgs>() {
    override val name: String = "list_files_in_folder"
    override val description: String = """
         Lists all files and directories in a project folder, helping you explore the project structure.

         <pathInProject> Path relative to project root (use "/" for project root)

         list_files_in_folder = ({pathInProject: string}) => Array<{ name: string; type: "file" | "directory"; path: string }> | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: ListFilesInFolderArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")

        return runReadAction {
            try {
                val targetDir = projectDir.resolveRel(args.pathInProject)

                if (!targetDir.exists()) {
                    return@runReadAction Response(error = "directory not found")
                }

                val entries = targetDir.listDirectoryEntries().map { entry ->
                    val type = if (entry.isDirectory()) "directory" else "file"
                    val relativePath = projectDir.relativize(entry).toString()
                    """{"name": "${entry.name}", "type": "$type", "path": "$relativePath"}"""
                }

                Response(entries.joinToString(",\n", prefix = "[", postfix = "]"))
            } catch (e: Exception) {
                Response(error = "Error listing directory: ${e.message}")
            }
        }
    }
}
*/


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
                                val entryName = entry.fileName.toString()
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
                                """{"name": "${entry.fileName}", "type": "$type", "path": "$relativePath"$extra}"""
                            }
                        }
                    // If not all entries were processed in this folder, add a warning here.
                    val allEntries = targetDir.listDirectoryEntries().filter { entry ->
                        if (Files.isDirectory(entry)) {
                            val entryName = entry.fileName.toString()
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
                    val entryName = entry.fileName.toString()
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

            val name = entry.fileName.toString()
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
@Serializable
data class SearchInFilesArgs(
    val searchText: String,
    val contextBefore: Int = 0,
    val contextAfter: Int = 0
)

class SearchInFilesContentTool : AbstractMcpTool<SearchInFilesArgs>() {
  override val name: String = "search_in_files_content"
  override val description: String = """
      Searches for text within all project files, returning a snippet with context lines.

      <searchText> Text to find in files
      <contextBefore> Number of lines before the matching line to include (default: 0)
      <contextAfter> Number of lines after the matching line to include (default: 0)

      If both contextBefore and contextAfter are 0, only the matching line is returned.

      Hard limit: The total number of snippet rows will not exceed 1000.
      If the limit is reached, a warning is added to the output.

      search_in_files_content = ({searchText: string, contextBefore?: number, contextAfter?: number}) => Array<{ path: string, snippet: string }> | { error: string };
  """.trimIndent()

  // Hard limit on total snippet rows.
  private val HARD_LIMIT_ROWS = 1000

  override fun handle(project: Project, args: SearchInFilesArgs): Response {
    val projectDir = project.guessProjectDir()?.toNioPathOrNull()
      ?: return Response(error = "Project directory not found")
    val searchSubstring = args.searchText
    if (searchSubstring.isBlank()) {
      return Response(error = "searchText parameter is required and cannot be blank")
    }

    // Calculate the number of lines per snippet.
    val snippetLineCount = if (args.contextBefore == 0 && args.contextAfter == 0) 1
                             else 1 + args.contextBefore + args.contextAfter
    // Maximum number of result entries such that total snippet rows <= HARD_LIMIT_ROWS.
    val maxResults = HARD_LIMIT_ROWS / snippetLineCount

    val findModel = FindManager.getInstance(project).findInProjectModel.clone()
    findModel.stringToFind = searchSubstring
    findModel.isCaseSensitive = false
    findModel.isWholeWordsOnly = false
    findModel.isRegularExpressions = false
    findModel.setProjectScope(true)

    val results = mutableSetOf<String>()
    val processor = Processor<UsageInfo> { usageInfo ->
      if (results.size >= maxResults) return@Processor false
      val virtualFile = usageInfo.virtualFile ?: return@Processor true
      val document = FileDocumentManager.getInstance().getDocument(virtualFile)
      val fileText = if (document != null) document.text else virtualFile.readText()
      // Use the PSI element's text range if available.
      val offset = usageInfo.element?.textRange?.startOffset ?: 0
      val lineNumber = if (document != null) {
          document.getLineNumber(offset)
      } else {
          fileText.substring(0, offset.coerceAtMost(fileText.length)).count { it == '\n' }
      }
      val lines = if (document != null) document.text.lines() else fileText.lines()
      val startLine = (lineNumber - args.contextBefore).coerceAtLeast(0)
      val endLine = (lineNumber + args.contextAfter).coerceAtMost(lines.size - 1)
      val snippet = if (args.contextBefore == 0 && args.contextAfter == 0) {
          lines[lineNumber]
      } else {
          lines.subList(startLine, endLine + 1).joinToString("\n")
      }
      try {
          val relativePath = projectDir.relativize(Path(virtualFile.path)).toString()
          results.add("""{"path": "$relativePath", "snippet": ${snippet.escapeForJson()}}""")
      } catch (e: IllegalArgumentException) {
          // Ignore files that cannot be relativized.
      }
      true
    }

    FindInProjectUtil.findUsages(
      findModel,
      project,
      processor,
      FindUsagesProcessPresentation(UsageViewPresentation())
    )

    // Append a warning if we reached the limit.
    val resultList = results.toMutableList()
    if (results.size >= maxResults) {
      resultList.add("""{"warning": "Reached maximum snippet line limit of $HARD_LIMIT_ROWS"}""")
    }
    val jsonResult = resultList.joinToString(",\n", prefix = "[", postfix = "]")
    return Response(jsonResult)
  }

  private fun String.escapeForJson(): String {
    val escaped = this.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    return "\"$escaped\""
  }
}

/*
@Serializable
data class SearchInFilesArgs(val searchText: String)

class SearchInFilesContentTool : AbstractMcpTool<SearchInFilesArgs>() {
    override val name: String = "search_in_files_content"
    override val description: String = """
        Searches for text within all project files.

        <searchText> Text to find in files

        search_in_files_content = ({searchText: string}) => Array<{ path: string }> | { error: string };
    """

    override fun handle(project: Project, args: SearchInFilesArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "Project directory not found")

        val searchSubstring = args.searchText
        if (searchSubstring.isNullOrBlank()) {
            return Response(error = "contentSubstring parameter is required and cannot be blank")
        }

        val findModel = FindManager.getInstance(project).findInProjectModel.clone()
        findModel.stringToFind = searchSubstring
        findModel.isCaseSensitive = false
        findModel.isWholeWordsOnly = false
        findModel.isRegularExpressions = false
        findModel.setProjectScope(true)

        val results = mutableSetOf<String>()

        val processor = Processor<UsageInfo> { usageInfo ->
            val virtualFile = usageInfo.virtualFile ?: return@Processor true
            try {
                val relativePath = projectDir.relativize(Path(virtualFile.path)).toString()
                results.add("""{"path": "$relativePath", "name": "${virtualFile.name}"}""")
            } catch (e: IllegalArgumentException) {
            }
            true
        }
        FindInProjectUtil.findUsages(
            findModel,
            project,
            processor,
            FindUsagesProcessPresentation(UsageViewPresentation())
        )

        val jsonResult = results.joinToString(",\n", prefix = "[", postfix = "]")
        return Response(jsonResult)
    }
}
*/

class GetRunConfigurationsTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_run_configurations"
    override val description: String = """
        Returns available run configurations for the current project.

        get_run_configurations = () => Array<string>;
    """

    override fun handle(project: Project, args: NoArgs): Response {
        val runManager = RunManager.getInstance(project)

        val configurations = runManager.allSettings.map { it.name }.joinToString(
            prefix = "[",
            postfix = "]",
            separator = ","
        ) { "\"$it\"" }

        return Response(configurations)
    }
}

@Serializable
data class RunConfigArgs(val configName: String)

class RunConfigurationTool : AbstractMcpTool<RunConfigArgs>() {
    override val name: String = "run_configuration"
    override val description: String = """
        Runs a specific run configuration.

        <configName> Name of the run configuration to execute

        run_configuration = ({configName: string}) => "ok" | { error: string };
    """

    override fun handle(project: Project, args: RunConfigArgs): Response {
        val runManager = RunManager.getInstance(project)
        val settings = runManager.allSettings.find { it.name == args.configName }
        val executor = getRunExecutorInstance()
        if (settings != null) {
            executeConfiguration(settings, executor)
        } else {
            println("Run configuration with name '${args.configName}' not found.")
        }
        return Response("ok")
    }
}

class GetProjectModulesTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_project_modules"
    override val description: String = """
        Retrieves all modules in the project.

        get_project_modules = () => Array<string>;
    """

    override fun handle(project: Project, args: NoArgs): Response {
        val moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project)
        val modules = moduleManager.modules.map { it.name }
        return Response(modules.joinToString(",\n", prefix = "[", postfix = "]"))
    }
}

class GetProjectDependenciesTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_project_dependencies"
    override val description: String = "Get list of all dependencies defined in the project. Returns JSON list of dependency names."

    override fun handle(project: Project, args: NoArgs): Response {
        val moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project)
        val dependencies = moduleManager.modules.flatMap { module ->
            OrderEnumerator.orderEntries(module).librariesOnly().classes().roots.map { root ->
                """{"name": "${root.name}", "type": "library"}"""
            }
        }.toHashSet()

        return Response(dependencies.joinToString(",\n", prefix = "[", postfix = "]"))
    }
}

@Serializable
data class DeleteFileArgs(
    val pathInProject: String,
    val recursive: Boolean = false
)

class DeleteFileTool : AbstractMcpTool<DeleteFileArgs>() {
    override val name: String = "file_delete"
    override val description: String = """
        Deletes a file or directory from the project.

        <pathInProject> Path to delete (relative to project root)
        <recursive> Set true to delete directories with contents

        file_delete = ({pathInProject: string, recursive: boolean}) => "ok" | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: DeleteFileArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")

        val path = projectDir.resolveRel(args.pathInProject)

        return try {
            if (!path.exists()) {
                return Response(error = "file not found")
            }

            if (path.isDirectory()) {
                if (args.recursive) {
                    path.toFile().deleteRecursively()
                } else {
                    if (path.listDirectoryEntries().isNotEmpty()) {
                        return Response(error = "directory not empty, use recursive=true to delete with contents")
                    }
                    path.deleteExisting()
                }
            } else {
                path.deleteExisting()
            }

            // Refresh the filesystem to reflect changes in IDE
            LocalFileSystem.getInstance().refresh(true)

            Response("ok")
        } catch (e: Exception) {
            Response(error = "failed to delete: ${e.message}")
        }
    }
}

@Serializable
data class CopyFileArgs(
    val sourcePath: String,
    val targetPath: String,
    val recursive: Boolean = true
)

class CopyFileTool : AbstractMcpTool<CopyFileArgs>() {
    override val name: String = "file_copy"
    override val description: String = """
        Copies a file or directory to a new location.

        <sourcePath> File/directory to copy (relative to project root)
        <targetPath> Destination path (relative to project root)

        file_copy = ({sourcePath: string, targetPath: string, recursive: boolean}) => "ok" | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: CopyFileArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")

        val sourcePath = projectDir.resolveRel(args.sourcePath)
        val targetPath = projectDir.resolveRel(args.targetPath)
        
        return try {
            if (!sourcePath.exists()) {
                return Response(error = "source file not found")
            }
            
            if (targetPath.exists()) {
                return Response(error = "target already exists")
            }
            
            if (sourcePath.isDirectory()) {
                sourcePath.toFile().copyRecursively(targetPath.toFile())
            } else {
                targetPath.parent.createDirectories()
                sourcePath.copyTo(targetPath)
            }
            
            // Refresh the filesystem to reflect changes in IDE
            LocalFileSystem.getInstance().refresh(true)
            
            Response("ok")
        } catch (e: Exception) {
            Response(error = "failed to copy: ${e.message}")
        }
    }
}

@Serializable
data class MoveFileArgs(
    val sourcePath: String,
    val targetPath: String
)

class MoveFileTool : AbstractMcpTool<MoveFileArgs>() {
    override val name: String = "file_move"
    override val description: String = """
        Moves or renames a file or directory.

        <sourcePath> Current path (relative to project root)
        <targetPath> New path (relative to project root)

        file_move = ({sourcePath: string, targetPath: string}) => "ok" | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: MoveFileArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")

        val sourcePath = projectDir.resolveRel(args.sourcePath)
        val targetPath = projectDir.resolveRel(args.targetPath)
        
        return try {
            if (!sourcePath.exists()) {
                return Response(error = "source file not found")
            }
            
            if (targetPath.exists()) {
                return Response(error = "target already exists")
            }
            
            targetPath.parent.createDirectories()
            sourcePath.moveTo(targetPath, overwrite = false)
            
            // Refresh the filesystem to reflect changes in IDE
            LocalFileSystem.getInstance().refresh(true)
            
            Response("ok")
        } catch (e: Exception) {
            Response(error = "failed to move: ${e.message}")
        }
    }
}

@Serializable
data class GetFileStructureArgs(val pathInProject: String)

/**
 * A tool that returns the structure of a file using IntelliJ's Structure View API.
 * This approach is language-agnostic and works with any file type that has a structure view provider.
 */
class GetFileStructureTool : AbstractMcpTool<GetFileStructureArgs>() {
    override val name: String = "get_file_structure"
    override val description: String = """
        Returns the structure of a file as seen in IntelliJ's Structure View.

        <pathInProject> Path to the file, relative to project root

        get_file_structure = ({pathInProject: string}) => string | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: GetFileStructureArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "project dir not found")

        return runReadAction {
            try {
                val path = projectDir.resolveRel(args.pathInProject)
                val virtualFile = LocalFileSystem.getInstance()
                    .refreshAndFindFileByNioFile(path)
                    ?: return@runReadAction Response(error = "file not found")

                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                    ?: return@runReadAction Response(error = "couldn't parse file")

                // Use the Structure View API to get the file structure
                val structure = extractStructureFromFile(psiFile)
                Response(structure)
            } catch (e: Exception) {
                Response(error = "Error analyzing file structure: ${e.message}")
            }
        }
    }

    /**
     * Extracts the structure from a file using IntelliJ's Structure View API
     */
    private fun extractStructureFromFile(psiFile: PsiFile): String {
        val builder = LanguageStructureViewBuilder.INSTANCE.getStructureViewBuilder(psiFile)
            ?: return createBasicFileInfo(psiFile)

        if (builder !is TreeBasedStructureViewBuilder) {
            return createBasicFileInfo(psiFile)
        }

        val structureViewModel = builder.createStructureViewModel(null)
        val rootElement = structureViewModel.root

        val result = StringBuilder()
        result.append("{\n")
        result.append("  \"fileName\": \"${psiFile.name}\",\n")
        result.append("  \"fileType\": \"${psiFile.fileType.name}\",\n")
        result.append("  \"language\": \"${psiFile.language.displayName}\",\n")

        // Process the structure tree
        result.append("  \"elements\": ")
        buildStructureTree(rootElement, result, 0)

        result.append("\n}")
        return result.toString()
    }

    /**
     * Recursively builds a JSON representation of the structure tree
     * with added line and column number information
     */
    private fun buildStructureTree(element: TreeElement, result: StringBuilder, level: Int): StringBuilder {
        if (level == 0) {
            result.append("[\n")
        }

        val indent = "    ".repeat(level + 1)
        val children = element.children

        if (element !is PsiTreeElementBase<*>) {
            // For non-PSI elements, just include presentation text
            result.append("$indent{\n")
            result.append("$indent  \"name\": \"${element.presentation.presentableText}\",\n")
            result.append("$indent  \"type\": \"element\"\n")
            result.append("$indent}")
        } else {
            // For PSI elements, include more information
            val value = element.value
            val elementType = getElementType(value)

            result.append("$indent{\n")
            result.append("$indent  \"name\": \"${element.presentation.presentableText}\",\n")
            result.append("$indent  \"type\": \"$elementType\"")

            // Add line and column number information if it's a PsiElement
            if (value is PsiElement) {
                try {
                    val containingFile = value.containingFile
                    val project = containingFile.project
                    val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
                        ?: containingFile.viewProvider.document

                    if (document != null) {
                        val textRange = value.textRange
                        if (textRange != null) {
                            // Start position
                            val startOffset = textRange.startOffset
                            val startLineNumber = document.getLineNumber(startOffset) + 1 // +1 because line numbers are 0-based

                            result.append(",\n$indent  \"startLine\": $startLineNumber")

                            // End position
                            val endOffset = textRange.endOffset
                            val endLineNumber = document.getLineNumber(endOffset) + 1

                            result.append(",\n$indent  \"endLine\": $endLineNumber")
                        }
                    }
                } catch (e: Exception) {
                    // If we can't get position info, just continue without it
                    result.append(",\n$indent  \"positionInfo\": \"unavailable: ${e.message?.escapeJson() ?: "unknown error"}\"")
                }
            }

            // Add any location text or tooltips if available
            element.presentation.locationString?.let {
                if (it.isNotEmpty()) {
                    result.append(",\n$indent  \"detail\": \"${it.escapeJson()}\"")
                }
            }

            if (children.isNotEmpty()) {
                result.append(",\n")
                result.append("$indent  \"children\": [\n")

                children.forEachIndexed { index, child ->
                    buildStructureTree(child, result, level + 1)
                    if (index < children.size - 1) {
                        result.append(",\n")
                    } else {
                        result.append("\n")
                    }
                }

                result.append("$indent  ]")
            }

            result.append("\n$indent}")
        }

        if (level == 0) {
            result.append("\n  ]")
        }

        return result
    }

    /**
     * Attempts to determine a meaningful type for a PSI element
     */
    private fun getElementType(element: Any?): String {
        return when {
            element == null -> "unknown"
            element.javaClass.simpleName.contains("Class", ignoreCase = true) -> "class"
            element.javaClass.simpleName.contains("Method", ignoreCase = true) -> "method"
            element.javaClass.simpleName.contains("Function", ignoreCase = true) -> "function"
            element.javaClass.simpleName.contains("Field", ignoreCase = true) -> "field"
            element.javaClass.simpleName.contains("Property", ignoreCase = true) -> "property"
            element.javaClass.simpleName.contains("Variable", ignoreCase = true) -> "variable"
            element.javaClass.simpleName.contains("Constant", ignoreCase = true) -> "constant"
            element.javaClass.simpleName.contains("Interface", ignoreCase = true) -> "interface"
            element.javaClass.simpleName.contains("Enum", ignoreCase = true) -> "enum"
            element.javaClass.simpleName.contains("Parameter", ignoreCase = true) -> "parameter"
            element.javaClass.simpleName.contains("Companion", ignoreCase = true) -> "companion"
            else -> "element"
        }
    }

    /**
     * Creates basic file information when structure view is not available
     */
    private fun createBasicFileInfo(psiFile: PsiFile): String {
        return """
        {
          "fileName": "${psiFile.name}",
          "fileType": "${psiFile.fileType.name}",
          "language": "${psiFile.language.displayName}",
          "elements": [],
          "note": "No structured view available for this file type"
        }
        """.trimIndent()
    }

    /**
     * Helper function to escape JSON strings
     */
    private fun String.escapeJson(): String {
        return this.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * Helper function to resolve relative paths
     */
    private fun Path.resolveRel(pathInProject: String): Path {
        return when (pathInProject) {
            "/" -> this
            else -> resolve(pathInProject.removePrefix("/"))
        }
    }
}

@Serializable
data class GetFilePartTextArgs(
    val pathInProject: String,
    val lineFrom: Int,
    val lineTo: Int
)

class GetFilePartTextTool : AbstractMcpTool<GetFilePartTextArgs>() {
    override val name: String = "get_file_part_text"
    override val description: String = """
      Reads a portion of a file's content based on line numbers.
      
      <pathInProject> Path to the file, relative to project root
      <lineFrom> Starting line number (inclusive)
      <lineTo> Ending line number (inclusive)
      
      get_file_part_text = ({pathInProject: string, lineFrom: number, lineTo: number}) => string | { error: string };
  """.trimIndent()

    override fun handle(project: Project, args: GetFilePartTextArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")
        val filePath = projectDir.resolveRel(args.pathInProject)
        if (!Files.exists(filePath)) {
            return Response(error = "file not found")
        }
        return runReadAction {
            try {
                val lines = Files.readAllLines(filePath)
                if (args.lineFrom < 1 || args.lineTo < args.lineFrom || args.lineFrom > lines.size) {
                    return@runReadAction Response(error = "invalid line range")
                }
                val endIndex = if (args.lineTo > lines.size) lines.size else args.lineTo
                val extracted = lines.subList(args.lineFrom - 1, endIndex).joinToString("\n")
                Response(extracted)
            } catch (e: Exception) {
                Response(error = "error reading file: ${e.message}")
            }
        }
    }
}

@Serializable
data class ReplaceFilePartTextArgs(
    val pathInProject: String,
    val lineFrom: Int,
    val lineTo: Int,
    val text: String
)

class ReplaceFilePartTextTool : AbstractMcpTool<ReplaceFilePartTextArgs>() {
    override val name: String = "replace_file_part_text"
    override val description: String = """
      Replaces lines in a file between specified line numbers with new text,
      and returns the updated content with 10 lines of context before and after the change.
      
      <pathInProject> Path to the file, relative to project root
      <lineFrom> Starting line number (inclusive) to replace
      <lineTo> Ending line number (inclusive) to replace
      <text> New text to insert (can be multiline)
      
      replace_file_part_text = ({pathInProject: string, lineFrom: number, lineTo: number, text: string}) => string | { error: string };
  """.trimIndent()

    override fun handle(project: Project, args: ReplaceFilePartTextArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")
        val filePath = projectDir.resolveRel(args.pathInProject)
        if (!Files.exists(filePath)) {
            return Response(error = "file not found")
        }
        var contextResult: String? = null
        WriteCommandAction.runWriteCommandAction(project) {
            try {
                val originalLines = Files.readAllLines(filePath).toMutableList()
                if (args.lineFrom < 1 || args.lineTo < args.lineFrom || args.lineFrom > originalLines.size) {
                    contextResult = "invalid line range"
                    return@runWriteCommandAction
                }
                val endIndex = if (args.lineTo > originalLines.size) originalLines.size else args.lineTo
                val newLines = args.text.split("\n")
                repeat(endIndex - args.lineFrom + 1) { originalLines.removeAt(args.lineFrom - 1) }
                originalLines.addAll(args.lineFrom - 1, newLines)
                Files.write(filePath, originalLines)
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(filePath)
                val startContext = (args.lineFrom - 1 - 10).coerceAtLeast(0)
                val endContext = ((args.lineFrom - 1) + newLines.size + 10).coerceAtMost(originalLines.size)
                contextResult = originalLines.subList(startContext, endContext).joinToString("\n")
            } catch (e: Exception) {
                contextResult = "error replacing file part: ${e.message}"
            }
        }
        return if (contextResult == null || contextResult!!.startsWith("error") || contextResult == "invalid line range")
            Response(error = contextResult ?: "unknown error")
        else Response(contextResult!!)
    }
}


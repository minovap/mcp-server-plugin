package org.jetbrains.mcpserverplugin

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager.getInstance
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.readText
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.io.createParentDirectories
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import java.util.concurrent.CountDownLatch
import kotlin.io.path.Path
import kotlin.io.path.createFile
import kotlin.io.path.writeText

// tools

class GetCurrentFileTextTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_open_in_editor_file_text"
    override val description: String = """
        Retrieves the complete text content of the currently active file in the JetBrains IDE editor.
        Use this tool to access and analyze the file's contents for tasks such as code review, content inspection, or text processing.
        Returns empty string if no file is currently open.
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val text = runReadAction<String?> {
            getInstance(project).selectedTextEditor?.document?.text
        }
        return Response(text ?: "")
    }
}

class GetCurrentFilePathTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_open_in_editor_file_path"
    override val description: String = """
        Retrieves the absolute path of the currently active file in the JetBrains IDE editor.
        Use this tool to get the file location for tasks requiring file path information.
        Returns an empty string if no file is currently open.
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val path = runReadAction<String?> {
            getInstance(project).selectedTextEditor?.virtualFile?.path
        }
        return Response(path ?: "")
    }
}

class GetSelectedTextTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_selected_in_editor_text"
    override val description: String = """
        Retrieves the currently selected text from the active editor in JetBrains IDE.
        Use this tool when you need to access and analyze text that has been highlighted/selected by the user.
        Returns an empty string if no text is selected or no editor is open.
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
        Replaces the currently selected text in the active editor with specified new text.
        Use this tool to modify code or content by replacing the user's text selection.
        Requires a text parameter containing the replacement content.
        Returns one of three possible responses:
            - "ok" if the text was successfully replaced
            - "no text selected" if no text is selected or no editor is open
            - "unknown error" if the operation fails
    """.trimIndent()

    override fun handle(project: Project, args: ReplaceSelectedTextArgs): Response {
        var response: Response? = null
        val lock = CountDownLatch(1)
        runInEdt {
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
                lock.countDown()
            })
        }
        lock.await()
        return response ?: Response(error = "unknown error")
    }
}

@Serializable
data class ReplaceCurrentFileTextArgs(val text: String)

class ReplaceCurrentFileTextTool : AbstractMcpTool<ReplaceCurrentFileTextArgs>() {
    override val name: String = "replace_current_file_text"
    override val description: String = """
        Replaces the entire content of the currently active file in the JetBrains IDE with specified new text.
        Use this tool when you need to completely overwrite the current file's content.
        Requires a text parameter containing the new content.
        Returns one of three possible responses:
        - "ok" if the file content was successfully replaced
        - "no file open" if no editor is active
        - "unknown error" if the operation fails
    """

    override fun handle(project: Project, args: ReplaceCurrentFileTextArgs): Response {
        var response: Response? = null
        val lock = CountDownLatch(1)
        runInEdt {
            runWriteCommandAction(project, "Replace File Text", null, {
                val editor = getInstance(project).selectedTextEditor
                val document = editor?.document
                if(document != null) {
                    document.setText(args.text)
                    response = Response("ok")
                } else{
                    response = Response(error = "no file open")
                }
            })
        }
        lock.await()
        return response ?: Response(error = "unknown error")
    }
}

@Serializable
data class CreateNewFileWithTextArgs(val pathInProject: String, val text: String)

class CreateNewFileWithTextTool : AbstractMcpTool<CreateNewFileWithTextArgs>() {
    override val name: String = "create_new_file_with_text"
    override val description: String = """
        Creates a new file at the specified path within the project directory and populates it with the provided text.
        Use this tool to generate new files in your project structure.
        Requires two parameters:
            - pathInProject: The relative path where the file should be created
            - text: The content to write into the new file
        Returns one of two possible responses:
            - "ok" if the file was successfully created and populated
            - "can't find project dir" if the project directory cannot be determined
        Note: Creates any necessary parent directories automatically
    """

    override fun handle(project: Project, args: CreateNewFileWithTextArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")

        val path = Path(args.pathInProject)
        projectDir.resolve(path).createParentDirectories().createFile().writeText(args.text)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)

        return Response("ok")
    }
}

@Serializable
data class Query(val nameSubstring: String)

class FindFilesByNameSubstring: AbstractMcpTool<Query>() {
    override val name: String = "find_files_by_name_substring"
    override val description: String = """
        Searches for all files in the project whose names contain the specified substring (case-insensitive).
        Use this tool to locate files when you know part of the filename or searching for the files.
        Requires a nameSubstring parameter for the search term.
        Returns a comma-separated list of absolute file paths, with each path on a new line.
        Returns an empty string if no matching files are found.
        Searches through all project files, including those in dependencies and libraries.
    """

    override fun handle(project: Project, args: Query): Response {
        val searchSubstring = args.nameSubstring.toLowerCase()
        return runReadAction {
            Response(FilenameIndex.getAllFilenames(project).filter {
                it.toLowerCase().contains(searchSubstring)
        }.flatMap {
                FilenameIndex.getVirtualFilesByName(it, GlobalSearchScope.allScope(project))
        }.map {
            it.path
            }.joinToString(",\n"))
        }
    }
}
@Serializable
data class Path(val absolutePath: String)

class GetFileTextByPathTool : AbstractMcpTool<Path>() {
    override val name: String = "get_file_text_by_path"
    override val description: String = """
        Retrieves the text content of a file using its absolute path, if the file is within the project scope.
        Use this tool to read file contents when you have the file's absolute path.
        Requires an absolutePath parameter specifying the file location.
        Returns one of two responses:
        - The file's content if the file exists and belongs to the project
        - empty string if the file doesn't exist or is outside the project scope
        Note: Automatically refreshes the file system before reading
    """

    override fun handle(project: Project, args: Path): Response {
        val text = runReadAction {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path(args.absolutePath)) ?: return@runReadAction null
            if (GlobalSearchScope.allScope(project).contains(file)) {
                file.readText()
            }
            else {
                ""
            }
        }
        return Response(text)
    }
}
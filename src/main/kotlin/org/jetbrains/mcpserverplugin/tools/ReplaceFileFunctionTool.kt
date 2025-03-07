package org.jetbrains.mcpserverplugin.tools

import com.intellij.psi.PsiDocumentManager
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.FileFinderUtils
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import com.intellij.psi.PsiNamedElement

@Serializable
data class ReplaceFileFunctionArgs(
    val pathInProject: String,
    val functionName: String,
    val text: String
)

/**
 * A tool that replaces a function in a file with new text.
 * The tool identifies the function by name, finds its start and end lines,
 * and replaces it with the provided text.
 */
class ReplaceFileFunctionTool : AbstractMcpTool<ReplaceFileFunctionArgs>() {
    private val LOG = logger<ReplaceFileFunctionTool>()

    override val name: String = "replace_file_function"
    override val description: String = """
        Replaces a function/method in a file with new text.

        <pathInProject> Path to the file, relative to project root
        <functionName> Name of the function/method to replace
        <text> New text for the function (should include the complete function definition)

        replace_file_function = ({pathInProject: string, functionName: string, text: string}) => string | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: ReplaceFileFunctionArgs): Response {
        // Make sure we have a clean function name
        LOG.info("Received request to replace function: '${args.functionName}' in file: ${args.pathInProject}")

        // Find the file in the project
        val findResult = FileFinderUtils.findFileInProject(project, args.pathInProject)

        return when (findResult) {
            is FileFinderUtils.FindFileResult.Found -> {
                try {
                    val virtualFile = findResult.virtualFile

                    // We'll use AtomicReference to get the result from the read action
                    val functionLocationRef = AtomicReference<Pair<Int, Int>?>(null)
                    val errorRef = AtomicReference<String?>(null)

                    // Run PSI operations in a read action on the UI thread to avoid threading issues
                    ApplicationManager.getApplication().invokeAndWait {
                        ReadAction.run<Throwable> {
                            try {
                                // Get the PSI file
                                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)

                                if (psiFile == null) {
                                    errorRef.set("Couldn't parse file")
                                    return@run
                                }

                                // Find the function's location using Structure View
                                val functionLocation = findFunctionLocation(psiFile, args.functionName)

                                if (functionLocation == null) {
                                    errorRef.set("Function '${args.functionName}' not found in IntelliJ's structure data")
                                    return@run
                                }

                                LOG.info("Found function '${args.functionName}' at lines ${functionLocation.first}-${functionLocation.second}")
                                functionLocationRef.set(functionLocation)
                            } catch (e: Exception) {
                                errorRef.set("Error finding function: ${e.message}")
                                LOG.error("Error in read action", e)
                            }
                        }
                    }

                    // Check if we had an error
                    errorRef.get()?.let {
                        return Response(error = it)
                    }

                    // Get the function location
                    val functionLocation = functionLocationRef.get() ?: return Response(error = "Failed to find function location")

                    // Read file content
                    val filePath = virtualFile.toNioPath()
                    var success = false

                    // Replace the function text
                    WriteCommandAction.runWriteCommandAction(project) {
                        try {
                            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@runWriteCommandAction
                            val document = psiFile.viewProvider.document ?: return@runWriteCommandAction

                            // Find the function PSI element
                            val functionElement = psiFile.children
                                .filterIsInstance<PsiNamedElement>()
                                .firstOrNull { it.name == args.functionName }

                            if (functionElement == null) {
                                LOG.error("Function '${args.functionName}' not found in PSI tree during write action.")
                                return@runWriteCommandAction
                            }

                            // Get the exact text range of the function
                            val functionRange = functionElement.textRange
                            val startOffset = functionRange.startOffset
                            val endOffset = functionRange.endOffset

                            // Replace the function text using PSI
                            val newFunctionText = args.text.trim()
                            document.replaceString(startOffset, endOffset, newFunctionText)

                            // Commit the document changes properly
                            PsiDocumentManager.getInstance(project).commitDocument(document)
                            virtualFile.refresh(false, false)

                            LOG.info("Successfully replaced function '${args.functionName}' using PSI APIs.")
                        } catch (e: Exception) {
                            LOG.error("Error replacing function using PSI APIs", e)
                        }
                    }

                    // If no critical errors occurred, return success
                    return Response("ok")
                } catch (e: Exception) {
                    LOG.error("Error handling function replacement", e)
                    Response(error = "Error handling function replacement: ${e.message}")
                }
            }
            is FileFinderUtils.FindFileResult.NotFound -> {
                Response(error = findResult.error)
            }
        }
    }

    /**
     * Finds the start and end line numbers for a function in a file.
     * Uses IntelliJ's Structure View API to locate the function.
     *
     * @return Pair of (startLine, endLine) or null if function not found
     */
    private fun findFunctionLocation(psiFile: PsiFile, functionName: String): Pair<Int, Int>? {
        // Use IntelliJ's PSI tree to find a PsiNamedElement with the given function name
        val functionElements = psiFile.children
            .filterIsInstance<PsiNamedElement>()
            .filter { it.name == functionName }

        if (functionElements.isEmpty()) {
            LOG.warn("Function '$functionName' not found using PSI")
            return null
        }

        val functionElement = functionElements.first()
        val document = psiFile.viewProvider.document ?: return null

        val startLine = document.getLineNumber(functionElement.textOffset) + 1
        val endLine = document.getLineNumber(functionElement.textOffset + functionElement.textLength) + 1

        return Pair(startLine, endLine)
    }
}
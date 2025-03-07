package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import java.nio.file.Path
import java.nio.file.Paths

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
                            projectDir.relativize(Paths.get(file.path))
                            true
                        } catch (e: IllegalArgumentException) {
                            false
                        }
                    }
                    .map { file ->
                        val relativePath = projectDir.relativize(Paths.get(file.path)).toString()
                        """{"path": "$relativePath", "name": "${file.name}"}"""
                    }
                    .joinToString(",\n", prefix = "[", postfix = "]")
            )
        }
    }
}

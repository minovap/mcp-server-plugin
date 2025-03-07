package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

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

package org.jetbrains.mcpserverplugin.tools

import com.intellij.execution.RunManager
import com.intellij.openapi.project.Project
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

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

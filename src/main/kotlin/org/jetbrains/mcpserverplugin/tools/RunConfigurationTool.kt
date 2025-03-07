package org.jetbrains.mcpserverplugin.tools

import com.intellij.execution.ProgramRunnerUtil.executeConfiguration
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

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

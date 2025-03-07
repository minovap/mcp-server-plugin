package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.project.Project
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

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

package org.jetbrains.mcpserverplugin.tools

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

class ListAvailableActionsTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "list_available_actions"
    override val description: String = """
        Lists all available actions in JetBrains IDE editor.
        Returns a JSON array of objects containing action information:
        - id: The action ID
        - text: The action presentation text
        Use this tool to discover available actions for execution with execute_action_by_id.
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val actionManager = ActionManager.getInstance() as ActionManagerEx
        val dataContext = DataManager.getInstance().getDataContext()

        val availableActions = runReadAction {
            // Get all action IDs
            actionManager.getActionIdList("").mapNotNull { actionId ->
                val action = actionManager.getAction(actionId) ?: return@mapNotNull null

                // Create event and presentation to check if action is enabled
                val event = AnActionEvent.createFromAnAction(action, null, "", dataContext)
                val presentation = action.templatePresentation.clone()

                // Update presentation to check if action is available
                action.update(event)

                // Only include actions that have text and are enabled
                if (event.presentation.isEnabledAndVisible && !presentation.text.isNullOrBlank()) {
                    """{"id": "$actionId", "text": "${presentation.text.replace("\"", "\\\"")}"}"""
                } else {
                    null
                }
            }
        }

        return Response(availableActions.joinToString(",\n", prefix = "[", postfix = "]"))
    }
}

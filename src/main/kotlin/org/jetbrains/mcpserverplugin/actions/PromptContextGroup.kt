package org.jetbrains.mcpserverplugin.actions

import com.intellij.icons.AllIcons
import org.jetbrains.mcpserverplugin.icons.ClaudeIcons
import org.jetbrains.mcpserverplugin.MCPConnectionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.MainMenuPresentationAware
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import org.jetbrains.mcpserverplugin.actions.todo.LLMTodoContentCreator
import org.jetbrains.mcpserverplugin.actions.SendCodeToClaudeAction

/**
 * Action group that provides a submenu of all available prompt-contexts for editor selections
 * Can be configured to either start a new chat or append to an existing chat
 */
class PromptContextGroup : DefaultActionGroup(), DumbAware, MainMenuPresentationAware {
    private val isNewChat: Boolean
        get() = templatePresentation.text == "New Chat"

    init {
        // Set the icon in the template presentation
        templatePresentation.icon = ClaudeIcons.CLAUDE_ICON
        // Text is set in plugin.xml, don't override it here
        templatePresentation.isPopupGroup = true
        // templatePresentation.text is "New Chat" or "Append"
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val connectionManager = MCPConnectionManager.getInstance()
        
        // Only show if connected to Claude and project is available
        e.presentation.isEnabledAndVisible = project != null && connectionManager.isConnected()
        e.presentation.icon = ClaudeIcons.CLAUDE_ICON

        // e.presentation.text is "New Chat" or "Append"
    }

    // This method from MainMenuPresentationAware is the key to showing the icon in popup menus
    override fun alwaysShowIconInMainMenu(): Boolean {
        return true
    }


    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return emptyArray()
        return getPromptContextsActions(project)
    }

    private fun getPromptContextsActions(project: Project): Array<AnAction> {
        val templates = LLMTodoContentCreator.listAvailableTemplates(project)
        val actionManager = ActionManager.getInstance()
        val actionSuffix = if (isNewChat) "NewChat" else "Append"

        return templates.entries.map { (displayName, templateName) ->
            val actionId = "org.jetbrains.mcpserverplugin.actions.UseWithLLMTemplate_${templateName}_$actionSuffix"
            val existingAction = actionManager.getAction(actionId)

            existingAction ?: SendCodeToClaudeAction(actionId, displayName, templateName, isNewChat)
        }.toTypedArray()
    }
}
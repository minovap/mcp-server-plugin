package org.jetbrains.mcpserverplugin.actions

import com.intellij.icons.AllIcons
import org.jetbrains.mcpserverplugin.icons.ClaudeIcons
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
open class PromptContextGroup(
    private val isNewChat: Boolean = true
) : DefaultActionGroup(), DumbAware, MainMenuPresentationAware {
    
    init {
        // Set the icon in the template presentation
        templatePresentation.icon = ClaudeIcons.CLAUDE_ICON
        templatePresentation.text = if (isNewChat) "New Chat" else "Append"
        templatePresentation.isPopupGroup = true
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
        e.presentation.icon = ClaudeIcons.CLAUDE_ICON

        if (project != null) {
            e.presentation.text = if (isNewChat) "New Chat" else "Append"
        }
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
    
    /**
     * Subclass for creating an "Append" action group
     */
    class Append : PromptContextGroup(false)
}
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
import org.jetbrains.mcpserverplugin.actions.SendFilesToClaudeAction

/**
 * Action group that provides a submenu of all available prompt-contexts for files/directories
 */
class FilePromptContextGroup : DefaultActionGroup(), DumbAware, MainMenuPresentationAware {
    init {
        // Set the icon in the template presentation
        templatePresentation.icon = ClaudeIcons.CLAUDE_ICON
        templatePresentation.text = "New Chat"
        templatePresentation.isPopupGroup = true
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
        e.presentation.icon = ClaudeIcons.CLAUDE_ICON

        if (project != null) {
            e.presentation.text = "New Chat"
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

        return templates.entries.map { (displayName, templateName) ->
            val actionId = "org.jetbrains.mcpserverplugin.actions.UseFileWithLLMTemplate_$templateName"
            val existingAction = actionManager.getAction(actionId)

            existingAction ?: SendFilesToClaudeAction(actionId, displayName, templateName)
        }.toTypedArray()
    }
}
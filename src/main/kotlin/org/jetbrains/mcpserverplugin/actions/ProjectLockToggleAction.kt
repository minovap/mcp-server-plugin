package org.jetbrains.mcpserverplugin.actions

import com.intellij.icons.AllIcons
import org.jetbrains.mcpserverplugin.icons.ClaudeIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import org.jetbrains.mcpserverplugin.ProjectLockManager

/**
 * Action to toggle locking the current project for MCP operations.
 * When locked, all MCP operations will use the locked project instead of the last focused one.
 */
class ProjectLockToggleAction : ToggleAction(), DumbAware {
    
    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return ProjectLockManager.getInstance().isProjectLocked(project)
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        ProjectLockManager.getInstance().toggleProjectLock(project)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val lockManager = ProjectLockManager.getInstance()
        
        // Only enable if we have a project
        e.presentation.isEnabled = project != null
        
        // Update the icon based on lock state
        if (project != null && lockManager.isProjectLocked(project)) {
            e.presentation.icon = ClaudeIcons.PROJECT_LOCK_ICON
            e.presentation.text = "Unlock Project for MCP"
            e.presentation.description = "Unlock project for MCP operations (currently locked to ${project.name})"
        } else {
            e.presentation.icon = ClaudeIcons.PROJECT_UNLOCK_ICON
            e.presentation.text = "Lock Project for MCP"
            e.presentation.description = "Lock MCP operations to the current project"
        }
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}
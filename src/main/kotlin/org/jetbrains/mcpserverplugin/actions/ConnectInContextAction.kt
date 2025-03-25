package org.jetbrains.mcpserverplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import org.jetbrains.mcpserverplugin.MCPConnectionManager
import org.jetbrains.mcpserverplugin.icons.ClaudeIcons

/**
 * Action to connect to Claude from context menus when disconnected.
 * This appears in place of the regular Claude actions when not connected.
 */
class ConnectInContextAction : AnAction(), DumbAware {
    init {
        templatePresentation.icon = ClaudeIcons.CLAUDE_DISCONNECTED_ICON
        templatePresentation.text = "Connect to Claude"
        templatePresentation.description = "Connect to Claude to use with this context"
    }

    override fun actionPerformed(e: AnActionEvent) {
        // Find and execute the main Connect action
        val actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        val connectAction = actionManager.getAction("org.jetbrains.mcpserverplugin.actions.Connect")
        if (connectAction != null) {
            connectAction.actionPerformed(e)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val connectionManager = MCPConnectionManager.getInstance()
        
        // Only show if Claude is disconnected and project is available
        e.presentation.isEnabledAndVisible = project != null && !connectionManager.isConnected()
        e.presentation.icon = ClaudeIcons.CLAUDE_DISCONNECTED_ICON
    }
}
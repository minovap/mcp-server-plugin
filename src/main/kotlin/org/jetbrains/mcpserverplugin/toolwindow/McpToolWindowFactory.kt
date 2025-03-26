package org.jetbrains.mcpserverplugin.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import org.jetbrains.mcpserverplugin.tools.ui.McpToolPanel

/**
 * Factory for the MCP Tools tool window
 */
class McpToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val toolPanel = McpToolPanel(project)
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(JBScrollPane(toolPanel), "", false)
        toolWindow.contentManager.addContent(content)
    }
}
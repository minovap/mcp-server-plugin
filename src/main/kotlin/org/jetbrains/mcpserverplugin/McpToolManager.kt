package org.jetbrains.mcpserverplugin

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.components.service
import org.jetbrains.mcpserverplugin.settings.PluginSettings
import org.jetbrains.mcpserverplugin.tools.*

class McpToolManager {
    companion object {
        private val EP_NAME = ExtensionPointName<AbstractMcpTool<*>>("com.intellij.mcpServer.mcpTool")

        fun getAllTools(): List<AbstractMcpTool<*>> {
            return buildList {
                // Add built-in tools
                addAll(getBuiltInTools())
                // Add extension-provided tools
                addAll(EP_NAME.extensionList)
            }
        }
        
        /**
         * Returns a list of all enabled tools based on user settings.
         * This implementation ensures no duplicates and proper filtering.
         */
        fun getEnabledTools(): List<AbstractMcpTool<*>> {
            val settings = com.intellij.openapi.components.service<org.jetbrains.mcpserverplugin.settings.PluginSettings>().state
            
            // Use a map to ensure no duplicates by tool name
            val enabledToolsMap = mutableMapOf<String, AbstractMcpTool<*>>()
            
            // Process all tools (both built-in and extension-provided) in a single collection
            getAllTools().forEach { tool ->
                val toolId = getToolId(tool)
                if (settings.isToolEnabled(toolId)) {
                    enabledToolsMap[toolId] = tool
                }
            }
            
            // Return the values from the map as a list
            return enabledToolsMap.values.toList()
        }
        
        /**
         * Generates a consistent unique ID for a tool based on its name and server (if applicable).
         */
        fun getToolId(tool: AbstractMcpTool<*>): String {
            // Extract server name from the tool's description if present
            val serverTag = "From server: "
            val description = tool.description
            val serverIndex = description.indexOf(serverTag)
            
            return if (serverIndex != -1) {
                val serverInfo = description.substring(serverIndex + serverTag.length).trim()
                val server = serverInfo.split(" ").firstOrNull()?.trim() ?: "Unknown"
                tool.name + "_" + server
            } else {
                // No server information found, use just the tool name
                tool.name
            }
        }

        private fun getBuiltInTools(): List<AbstractMcpTool<*>> = listOf(
            // Custom tools
            DeleteFileTool(),
            CopyFileTool(),
            MoveFileTool(),
            
            // Official tools
            ClaudeCodeGlobTool(),
            ClaudeCodeGrepTool(),
            ClaudeCodeLsTool(),
            ClaudeCodeViewTool(),
            ClaudeCodeEditTool(),
            ClaudeCodeReplaceTool(),
            ClaudeCodeBashTool()
        )
    }
}

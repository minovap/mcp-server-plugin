package org.jetbrains.mcpserverplugin.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.State
import org.jetbrains.mcpserverplugin.settings.MCPConfigurable.Companion.DEFAULT_DOCKER_IMAGE

@State(name = "MyPluginSettings", storages = [Storage("mcpServer.xml")])
class PluginSettings : SimplePersistentStateComponent<MyState>(MyState())

class MyState : BaseState() {
    var shouldShowNodeNotification: Boolean by property(true)
    var shouldShowClaudeNotification: Boolean by property(true)
    var shouldShowClaudeSettingsNotification: Boolean by property(true)
    var dockerImage by string(DEFAULT_DOCKER_IMAGE)
    
    // Tool enablement settings - using a map serialized as a string
    private var toolEnablementString by string("")
    
    // Helper methods for tool enablement
    fun isToolEnabled(toolName: String): Boolean {
        return !getDisabledToolsSet().contains(toolName)
    }
    
    fun setToolEnabled(toolName: String, enabled: Boolean) {
        val disabledTools = getDisabledToolsSet().toMutableSet()
        
        if (enabled) {
            disabledTools.remove(toolName)
        } else {
            disabledTools.add(toolName)
        }
        
        // Update the disabled tools string
        toolEnablementString = disabledTools.joinToString(",")
    }
    
    private fun getDisabledToolsSet(): Set<String> {
        if (toolEnablementString.isNullOrEmpty()) {
            return emptySet()
        }
        return toolEnablementString!!.split(",").toSet()
    }
}
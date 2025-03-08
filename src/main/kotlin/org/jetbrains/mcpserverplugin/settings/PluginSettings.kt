package org.jetbrains.mcpserverplugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import org.jetbrains.mcpserverplugin.tools.docker.DockerDefaults
import org.jetbrains.mcpserverplugin.settings.MCPConfigurable.Companion.DEFAULT_DOCKER_IMAGE

/**
 * Persistent settings for the MCP Server plugin.
 * Uses PersistentStateComponent directly to ensure state is properly saved.
 */
@State(
    name = "org.jetbrains.mcpserverplugin.settings.MCPSettings",
    storages = [Storage("mcpServerSettings.xml")]
)
class PluginSettings : PersistentStateComponent<PluginSettings> {
    private val LOG = Logger.getInstance(PluginSettings::class.java)

    // Basic settings
    var shouldShowNodeNotification: Boolean = true
    var shouldShowClaudeNotification: Boolean = true
    var shouldShowClaudeSettingsNotification: Boolean = true
    var dockerImage: String = DEFAULT_DOCKER_IMAGE
    var useDefaultDockerImage: Boolean = true
    
    // LLM prompt template setting
    var llmPromptTemplate: String? = null
    
    // Tool enablement settings - using a map serialized as a string
    var disabledTools: String = ""
    
    // Helper methods for tool enablement
    fun isToolEnabled(toolName: String): Boolean {
        return !getDisabledToolsSet().contains(toolName)
    }
    
    fun setToolEnabled(toolName: String, enabled: Boolean) {
        val tools = getDisabledToolsSet().toMutableSet()
        
        if (enabled) {
            tools.remove(toolName)
        } else {
            tools.add(toolName)
        }
        
        // Update the disabled tools string
        disabledTools = tools.joinToString(",")
        LOG.info("Updated disabled tools: $disabledTools")
    }
    
    private fun getDisabledToolsSet(): Set<String> {
        if (disabledTools.isEmpty()) {
            return emptySet()
        }
        return disabledTools.split(",").toSet()
    }
    
    companion object {
    }

    override fun getState(): PluginSettings {
        LOG.info("Getting PluginSettings state")
        return this
    }

    override fun loadState(state: PluginSettings) {
        LOG.info("Loading PluginSettings state: shouldShowNodeNotification=${state.shouldShowNodeNotification}, " +
                "disabledTools=${state.disabledTools}")
        XmlSerializerUtil.copyBean(state, this)
    }
    
    fun logState() {
        LOG.info("Current state: shouldShowNodeNotification=$shouldShowNodeNotification, " +
                "shouldShowClaudeNotification=$shouldShowClaudeNotification, " +
                "shouldShowClaudeSettingsNotification=$shouldShowClaudeSettingsNotification, " +
                "dockerImage=$dockerImage, " +
                "useDefaultDockerImage=$useDefaultDockerImage, " +
                "disabledTools=$disabledTools")
    }
}

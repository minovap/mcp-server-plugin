package org.jetbrains.mcpserverplugin.icons

import com.intellij.openapi.util.IconLoader

object ClaudeIcons {
    @JvmField
    val CLAUDE_ICON = IconLoader.getIcon("/icons/claude-icon.svg", ClaudeIcons::class.java)
    
    @JvmField
    val CLAUDE_CONNECTED_ICON = IconLoader.getIcon("/icons/claude-connected-icon.svg", ClaudeIcons::class.java)
    
    @JvmField
    val CLAUDE_DISCONNECTED_ICON = IconLoader.getIcon("/icons/claude-disconnected-icon.svg", ClaudeIcons::class.java)
    
    @JvmField
    val EXAMPLE_ICON = IconLoader.getIcon("/icons/actions/example-icon.svg", ClaudeIcons::class.java)
    
    @JvmField
    val AI_STYLE_ICON = IconLoader.getIcon("/icons/actions/ai-style-icon.svg", ClaudeIcons::class.java)
}
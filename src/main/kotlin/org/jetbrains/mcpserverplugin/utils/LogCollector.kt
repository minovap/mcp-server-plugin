package org.jetbrains.mcpserverplugin.utils

import com.intellij.openapi.diagnostic.logger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A utility class to collect log messages that can be displayed in a UI dialog
 * Logs are only saved to the messages list if we want to include them in tool responses
 */
class LogCollector {
    private val LOG = logger<LogCollector>()
    private val messages = mutableListOf<String>()
    
    // Flag to force collecting logs regardless of context
    private var collectLogs = false
    
    // Timestamp formatter with milliseconds
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    
    /**
     * Default constructor
     * collectLogs: Set to true to force collecting logs regardless of context
     */
    constructor(collectLogs: Boolean = false) {
        this.collectLogs = collectLogs
    }
    
    /**
     * Set whether logs should be collected
     */
    fun setCollectLogs(collect: Boolean) {
        collectLogs = collect
    }
    
    /**
     * Check if logs are being collected
     */
    fun isCollectingLogs(): Boolean {
        // Check the thread-local to see if we're in an MCP tool panel context
        val isFromToolPanel = try {
            org.jetbrains.ide.mcp.MCPService.mcpToolPanelContext.get() ?: false
        } catch (e: Exception) {
            false
        }
        
        // Collect logs if explicitly enabled or if call is from MCP tool panel
        return collectLogs || isFromToolPanel
    }
    
    private fun addLog(level: String, message: String, e: Exception? = null) {
        val timestamp = LocalDateTime.now().format(timestampFormatter)
        val fullMessage = if (e != null) {
            "[$timestamp] $level: $message - ${e.message}"
        } else {
            "[$timestamp] $level: $message"
        }
        
        if (level == "ERROR" && e != null) {
            LOG.error(message, e)
        } else if (level == "ERROR") {
            LOG.error(fullMessage)
        } else {
            LOG.info(fullMessage)
        }
        
        // Only save messages to the list if collecting is enabled
        if (isCollectingLogs()) {
            messages.add(fullMessage)
        }
    }
    
    fun info(message: String) {
        addLog("INFO", message)
    }
    
    fun warn(message: String) {
        addLog("WARNING", message)
    }
    
    fun error(message: String, e: Exception? = null) {
        addLog("ERROR", message, e)
    }
    
    fun getMessages(): List<String> = messages.toList()
}
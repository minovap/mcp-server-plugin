package org.jetbrains.mcpserverplugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Manages the connection state of the MCP WebSocket service.
 */
class MCPConnectionManager : Disposable {
    private val log = logger<MCPConnectionManager>()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    
    companion object {
        // Singleton instance
        @JvmStatic
        fun getInstance(): MCPConnectionManager = ApplicationManager.getApplication().getService(MCPConnectionManager::class.java)
    }

    // Connection state
    private var isConnected = false

    // Listeners for connection state changes
    private val listeners = mutableListOf<(Boolean) -> Unit>()
    
    init {
        // Schedule WebSocket ping validation at regular intervals
        executor.scheduleAtFixedRate(
            { validateConnection() },
            5,  // Start after 5 seconds
            3,  // Check every 3 seconds
            TimeUnit.SECONDS
        )
        
        log.info("Started WebSocket connection validation with interval: 3 seconds")
    }

    /**
     * Set the connection state and notify listeners.
     */
    fun setConnectionState(connected: Boolean) {
        if (isConnected != connected) {
            log.info("Connection state changed from $isConnected to $connected")
            isConnected = connected
            notifyListeners()
        }
    }

    /**
     * Get the current connection state.
     */
    fun isConnected(): Boolean = isConnected
    

    
    /**
     * Explicitly validate the connection status by sending WebSocket ping frames
     * This is more reliable than heartbeat for checking if WebSockets are still alive
     */
    private fun validateConnection() {
        if (!isConnected) {
            // If we're already disconnected, check if we have active channels
            // This allows for reconnection if the WebSocket is actually available
            val service = MCPWebSocketService.getInstance()
            val activeCount = service.getActiveConnectionCount()
            
            if (activeCount > 0) {
                log.info("Found $activeCount active channels while disconnected, restoring connection state")
                setConnectionState(true)
            }
            return
        }
        
        try {
            // First check if we have any active channels
            val service = MCPWebSocketService.getInstance()
            val activeCount = service.getActiveConnectionCount()
            
            if (activeCount <= 0) {
                log.warn("No active WebSocket channels found, setting connection state to disconnected")
                setConnectionState(false)
                return
            }
            
            // If we have active channels, use ping for additional validation
            log.debug("Validating ${activeCount} active connection(s) using WebSocket ping")
            val pingResult = service.sendPingToAllClients()
            
            if (!pingResult) {
                log.warn("WebSocket ping validation failed: No successful pings")
                // Update UI to show disconnected
                setConnectionState(false)
            } else {
                log.debug("WebSocket ping validation successful")
                // Ensure connection state is true
                setConnectionState(true)
            }
        } catch (e: Exception) {
            log.error("Error validating connection with ping", e)
            // If validation throws an exception, assume connection is broken
            setConnectionState(false)
        }
    }
    

    
    /**
     * Check if channels are still active and valid
     * @return true if at least one valid channel exists
     */
    private fun checkActiveChannels(): Boolean {
        try {
            val service = MCPWebSocketService.getInstance()
            
            // Use the new validation method that thoroughly checks channels
            val validCount = service.validateAllChannels()
            
            // If we have valid channels, return true
            return validCount > 0
        } catch (e: Exception) {
            log.error("Error checking active channels", e)
            return false 
        }
    }

    /**
     * Add a listener for connection state changes.
     */
    fun addConnectionListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    /**
     * Remove a listener for connection state changes.
     */
    fun removeConnectionListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Notify all listeners of the current connection state.
     */
    private fun notifyListeners() {
        listeners.forEach { it(isConnected) }
    }
    
    /**
     * Cleanup resources when the service is being disposed.
     * Implementation of the Disposable interface.
     */
    override fun dispose() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
        }
    }
}
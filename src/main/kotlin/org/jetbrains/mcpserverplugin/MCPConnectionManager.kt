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
        
        // Heartbeat interval in seconds
        private const val HEARTBEAT_INTERVAL = 5L
        
        // Heartbeat timeout in seconds - make this much longer to be more forgiving
        private const val HEARTBEAT_TIMEOUT = 30L
    }

    // Connection state
    private var isConnected = false
    
    // Timestamp of last received heartbeat
    private var lastHeartbeatTime = System.currentTimeMillis()

    // Listeners for connection state changes
    private val listeners = mutableListOf<(Boolean) -> Unit>()
    
    init {
        // Schedule periodic heartbeat sending
        executor.scheduleAtFixedRate(
            { sendHeartbeat() },
            HEARTBEAT_INTERVAL,
            HEARTBEAT_INTERVAL,
            TimeUnit.SECONDS
        )
        
        // Schedule periodic heartbeat check
        executor.scheduleAtFixedRate(
            { checkHeartbeat() },
            HEARTBEAT_TIMEOUT,
            HEARTBEAT_TIMEOUT / 2,
            TimeUnit.SECONDS
        )
        
        // Schedule more frequent validation of channels
        executor.scheduleAtFixedRate(
            { validateConnection() },
            5,  // Start after 5 seconds
            3,  // Check every 3 seconds
            TimeUnit.SECONDS
        )
        
        log.info("Started heartbeat with interval: $HEARTBEAT_INTERVAL seconds, timeout: $HEARTBEAT_TIMEOUT seconds")
    }

    /**
     * Set the connection state and notify listeners.
     */
    fun setConnectionState(connected: Boolean) {
        if (isConnected != connected) {
            log.info("Connection state changed from $isConnected to $connected")
            isConnected = connected
            
            if (connected) {
                // Reset heartbeat timer when connecting
                updateHeartbeat()
            }
            
            notifyListeners()
        }
    }

    /**
     * Get the current connection state.
     */
    fun isConnected(): Boolean = isConnected
    
    /**
     * Send a heartbeat message to all connected clients
     */
    fun sendHeartbeat() {
        try {
            if (isConnected) {
                val service = MCPWebSocketService.getInstance()
                val message = "{\"type\":\"heartbeat\",\"timestamp\":${System.currentTimeMillis()}}" 
                // Don't focus Claude app for heartbeat messages
                service.sendMessageToAllClients(message, focusClaudeApp = false)
                log.info("Heartbeat sent")
            }
        } catch (e: Exception) {
            log.error("Error sending heartbeat", e)
            // If we can't send a heartbeat, connection might be broken
            validateConnection()
        }
    }
    
    /**
     * Update the last heartbeat time when a heartbeat response is received
     */
    fun updateHeartbeat() {
        lastHeartbeatTime = System.currentTimeMillis()
        log.info("Heartbeat received at $lastHeartbeatTime")
        
        // If we're currently disconnected, update the state
        if (!isConnected) {
            log.info("Received heartbeat while disconnected, setting state to connected")
            setConnectionState(true)
        }
    }
    
    /**
     * Explicitly validate the connection status
     * This is a separate method from checkHeartbeat to provide more direct channel validation
     */
    private fun validateConnection() {
        if (!isConnected) {
            // If we're already disconnected, nothing to do
            return
        }
        
        try {
            log.debug("Validating connection status")
            val service = MCPWebSocketService.getInstance()
            val validCount = service.validateAllChannels()
            
            if (validCount <= 0) {
                log.warn("Connection validation failed: No valid channels found")
                // Update UI to show disconnected
                setConnectionState(false)
            } else {
                log.debug("Connection validation successful: $validCount valid channels")
            }
        } catch (e: Exception) {
            log.error("Error validating connection", e)
            // If validation throws an exception, assume connection is broken
            setConnectionState(false)
        }
    }
    
    /**
     * Check if we've received a heartbeat recently
     */
    private fun checkHeartbeat() {
        try {
            if (isConnected) {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastHeartbeat = currentTime - lastHeartbeatTime
                val timeoutMillis = TimeUnit.SECONDS.toMillis(HEARTBEAT_TIMEOUT)
                val warningThreshold = TimeUnit.SECONDS.toMillis(HEARTBEAT_INTERVAL * 3)
                
                // Try a heartbeat after a short delay if we haven't received one recently
                if (timeSinceLastHeartbeat > TimeUnit.SECONDS.toMillis(HEARTBEAT_INTERVAL)) {
                    // But only log if it's been longer than 2 intervals
                    if (timeSinceLastHeartbeat > TimeUnit.SECONDS.toMillis(HEARTBEAT_INTERVAL * 2)) {
                        log.info("Heartbeat check: Last heartbeat was ${timeSinceLastHeartbeat}ms ago, timeout is ${timeoutMillis}ms")
                    }
                    
                    // Always try to verify the connection status by sending another heartbeat
                    if (checkActiveChannels()) {
                        sendHeartbeat()
                    } else {
                        log.warn("No active channels found during heartbeat check")
                        setConnectionState(false)
                        return
                    }
                }
                
                // Warning threshold - still connected but getting worried
                if (timeSinceLastHeartbeat > warningThreshold) {
                    log.warn("Heartbeat warning: No response for ${timeSinceLastHeartbeat}ms, status check queued")
                    
                    // Double-check if channels are still active
                    if (!checkActiveChannels()) {
                        log.warn("No active channels during warning check, disconnecting")
                        setConnectionState(false)
                        return
                    }
                }
                
                // If no heartbeat for HEARTBEAT_TIMEOUT seconds, consider disconnected
                if (timeSinceLastHeartbeat > timeoutMillis) {
                    log.warn("No heartbeat received for ${timeSinceLastHeartbeat}ms (timeout: ${timeoutMillis}ms), considering disconnected")
                    setConnectionState(false)
                }
            }
        } catch (e: Exception) {
            log.error("Error checking heartbeat", e)
            // If we can't even check the heartbeat, consider disconnected
            if (isConnected) {
                log.warn("Exception during heartbeat check, setting state to disconnected")
                setConnectionState(false)
            }
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
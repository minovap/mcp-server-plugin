package org.jetbrains.mcpserverplugin

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.websocketx.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.ide.RestService
import org.jetbrains.mcpserverplugin.actions.todo.LLMTodoContentCreator
import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit


/**
 * WebSocket service for MCP (Model Context Protocol).
 * Provides a WebSocket endpoint for bi-directional communication with clients.
 */
class MCPWebSocketService : RestService() {
    private val serviceName = "mcpws"
    private val json = Json { 
        prettyPrint = true
        ignoreUnknownKeys = true
        // Make sure all fields are serialized, even if they have default values
        encodeDefaults = true
    }
    
    companion object {
        private val LOG = logger<MCPWebSocketService>()
        
        // Store active WebSocket connections statically to share across instances
        private val activeConnections = ConcurrentHashMap<String, WebSocketServerHandshaker>()
        
        // Store active channel contexts for sending messages statically to share across instances
        private val activeChannels = ConcurrentHashMap<String, ChannelHandlerContext>()
        
        // Get the instance for global access
        fun getInstance(): MCPWebSocketService = MCPWebSocketService()
    }
    
    override fun getServiceName(): String = serviceName

    @Throws(InterruptedException::class, InvocationTargetException::class)
    protected override fun isHostTrusted(request: FullHttpRequest): Boolean {
        return true
    }

    override fun isHostTrusted(request: FullHttpRequest, urlDecoder: QueryStringDecoder): Boolean {
        return true
    }

    override fun isOriginAllowed(request: HttpRequest): OriginCheckResult {
        return OriginCheckResult.ALLOW
    }

    override fun execute(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): String? {
        LOG.info("Received request: ${request.method()} ${urlDecoder.path()}")
        LOG.info("Headers: ${request.headers().entries().joinToString { "${it.key}: ${it.value}" }}")
        
        // Check if this is a WebSocket upgrade request
        val isWebSocketRequest = "Upgrade".equals(request.headers().get(HttpHeaderNames.CONNECTION), ignoreCase = true) &&
                "WebSocket".equals(request.headers().get(HttpHeaderNames.UPGRADE), ignoreCase = true)
        
        if (isWebSocketRequest) {
            LOG.info("Processing WebSocket upgrade request from ${context.channel().remoteAddress()}")
            handleWebSocketUpgrade(context, request)
        } else {
            LOG.info("Received non-WebSocket request to WebSocket endpoint")
            // Regular HTTP request - send an error response
            val content = "WebSocket endpoint requires WebSocket upgrade"
            val outputStream = BufferExposingByteArrayOutputStream()
            outputStream.write(content.toByteArray(StandardCharsets.UTF_8))
            send(outputStream, request, context)
        }
        
        return null
    }
    
    private fun handleWebSocketUpgrade(ctx: ChannelHandlerContext, req: FullHttpRequest) {
        val wsLocation = getWebSocketLocation(req)
        LOG.info("Creating WebSocket handshaker with location: $wsLocation")
        
        // Configure WebSocket handshaker
        val wsFactory = WebSocketServerHandshakerFactory(
            wsLocation, null, true
        )
        val handshaker = wsFactory.newHandshaker(req)
        
        if (handshaker == null) {
            LOG.error("Unsupported WebSocket version")
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel())
        } else {
            // Store the handshaker and channel context for this connection
            val channelId = ctx.channel().id().asLongText()
            LOG.info("WebSocket protocol version: ${handshaker.version()}")
            activeConnections[channelId] = handshaker
            activeChannels[channelId] = ctx
            
            // Complete handshake
            LOG.info("Starting WebSocket handshake for client: ${ctx.channel().remoteAddress()}")
            handshaker.handshake(ctx.channel(), req).addListener(ChannelFutureListener { future ->
                if (future.isSuccess) {
                    LOG.info("WebSocket connection established: $channelId from ${ctx.channel().remoteAddress()}")
                    // Get the IDE info explicitly
                    val ideInfo = getIdeInfo()
                    LOG.info("Including IDE info in welcome message: $ideInfo")
                    
                    // Update connection state in the manager
                    MCPConnectionManager.getInstance().setConnectionState(true)
                    
                    // Send a welcome message after connection with IDE info
                    val welcome = EchoResponse(
                        message = "Connected to MCP WebSocket Server",
                        timestamp = System.currentTimeMillis(),
                        clientId = channelId,
                        clientAddress = ctx.channel().remoteAddress().toString(),
                        ideInfo = ideInfo  // Explicitly set the IDE info
                    )
                    val welcomeJson = json.encodeToString(welcome)
                    LOG.info("Sending welcome message: $welcomeJson")
                    ctx.writeAndFlush(TextWebSocketFrame(welcomeJson))
                    
                    // Log connection status after successful handshake
                    LOG.info("Active connections after handshake: ${activeConnections.size}, channels: ${activeChannels.size}")
                    activeChannels.keys.forEach { id -> LOG.info("Active channel after handshake: $id") }
                } else {
                    LOG.error("WebSocket handshake failed", future.cause())
                    activeConnections.remove(channelId)
                    activeChannels.remove(channelId)
                }
            })
        }
    }
    
    /**
     * Handle incoming WebSocket frames
     */
    fun handleWebSocketFrame(ctx: ChannelHandlerContext, frame: WebSocketFrame) {
        val channelId = ctx.channel().id().asLongText()
        LOG.info("Received WebSocket frame type: ${frame.javaClass.simpleName} from channel: $channelId")
        
        // Log connection status when handling frames
        LOG.info("Active connections during frame handling: ${activeConnections.size}, channels: ${activeChannels.size}")
        activeChannels.keys.forEach { id -> LOG.info("Active channel during frame handling: $id") }
        
        val handshaker = activeConnections[channelId]
        
        if (handshaker == null) {
            LOG.warn("Received WebSocket frame for unknown connection: $channelId from ${ctx.channel().remoteAddress()}")
            // Auto-register this connection if it's missing
            activeChannels[channelId] = ctx
            LOG.info("Auto-registered missing channel: $channelId")
            return
        }
        
        when (frame) {
            is CloseWebSocketFrame -> {
                LOG.info("Closing WebSocket connection: $channelId, status: ${frame.statusCode()}, reason: ${frame.reasonText()}")
                handshaker.close(ctx.channel(), frame.retain())
                activeConnections.remove(channelId)
                activeChannels.remove(channelId)
                LOG.info("WebSocket connection closed: $channelId")
                
                // Check if we have any active connections left
                if (activeChannels.isEmpty()) {
                    LOG.info("No active connections left, updating connection state")
                    MCPConnectionManager.getInstance().setConnectionState(false)
                }
            }
            is PingWebSocketFrame -> {
                LOG.info("Received PING frame from $channelId, sending PONG")
                ctx.writeAndFlush(PongWebSocketFrame(frame.content().retain()))
            }
            is TextWebSocketFrame -> {
                // Echo the message back with a simple wrapper
                val message = frame.text()
                LOG.info("Received TEXT frame from $channelId, length: ${message.length} bytes")
                echoMessage(ctx, message)
            }
            is BinaryWebSocketFrame -> {
                LOG.info("Received BINARY frame from $channelId, length: ${frame.content().readableBytes()} bytes")
                // We don't handle binary frames in this implementation
                val response = EchoResponse(
                    message = "Binary frames are not supported",
                    timestamp = System.currentTimeMillis()
                )
                ctx.writeAndFlush(TextWebSocketFrame(json.encodeToString(response)))
            }
            else -> {
                LOG.warn("Unsupported frame type: ${frame.javaClass.name}")
                ctx.writeAndFlush(CloseWebSocketFrame(1003, "Unsupported frame type"))
            }
        }
    }
    
    private fun echoMessage(ctx: ChannelHandlerContext, text: String) {
        LOG.info("Processing WebSocket message: $text")
        
        try {
            // No longer processing heartbeat responses
            if (text.contains("\"type\":\"heartbeat-response\"")) {
                LOG.info("Ignoring heartbeat response as heartbeat mechanism is disabled")
                return
            }
            
            // Check if this is a specific request for IDE information
            if (text.contains("get-ide-info") || text.contains("getIdeInfo")) {
                // Send a detailed IDE info response
                val ideInfoResponse = mapOf(
                    "type" to "ide-info",
                    "timestamp" to System.currentTimeMillis(),
                    "ideInfo" to getIdeInfo()
                )
                val jsonResponse = json.encodeToString(ideInfoResponse)
                LOG.info("Sending IDE info response")
                ctx.writeAndFlush(TextWebSocketFrame(jsonResponse))
                return
            }
            
            // Create a response with more details for debugging
            val ideInfo = getIdeInfo()
            val response = EchoResponse(
                message = "Echo: $text",
                timestamp = System.currentTimeMillis(),
                clientId = ctx.channel().id().asLongText(),
                clientAddress = ctx.channel().remoteAddress().toString(),
                ideInfo = ideInfo  // Explicitly set IDE info
            )
            
            val jsonResponse = json.encodeToString(response)
            LOG.info("Sending response: $jsonResponse")
            
            ctx.writeAndFlush(TextWebSocketFrame(jsonResponse))
            LOG.info("Response sent successfully")
        } catch (e: Exception) {
            LOG.error("Error processing WebSocket message", e)
            try {
                // Try to send an error response
                val errorResponse = EchoResponse(
                    message = "Error: ${e.message}",
                    timestamp = System.currentTimeMillis()
                )
                ctx.writeAndFlush(TextWebSocketFrame(json.encodeToString(errorResponse)))
            } catch (ex: Exception) {
                LOG.error("Failed to send error response", ex)
            }
        }
    }
    
    private fun getWebSocketLocation(req: FullHttpRequest): String {
        val scheme = if (req.headers().contains("X-Forwarded-Proto", "https", true)) "wss" else "ws"
        val host = req.headers().get(HttpHeaderNames.HOST, "localhost")
        val location = "$scheme://$host/${getServiceName()}"
        LOG.info("Generated WebSocket location: $location for host: $host")
        return location
    }
    
    /**
     * Sends diagnostic information about the WebSocket server status
     * to help with debugging connection issues
     */
    fun sendDiagnosticInfo(ctx: ChannelHandlerContext) {
        val info = mapOf(
            "activeConnections" to activeConnections.size,
            "connectionIds" to activeConnections.keys.toList(),
            "serverTime" to System.currentTimeMillis(),
            "channelId" to ctx.channel().id().asLongText(),
            "remoteAddress" to ctx.channel().remoteAddress().toString(),
            "localAddress" to ctx.channel().localAddress().toString()
        )
        
        val jsonResponse = json.encodeToString(info)
        ctx.writeAndFlush(TextWebSocketFrame(jsonResponse))
        LOG.info("Sent diagnostic info: $jsonResponse")
    }
    
    override fun isMethodSupported(method: HttpMethod): Boolean =
        method === HttpMethod.GET || method === HttpMethod.POST
    
    /**
     * Sends a message to all connected WebSocket clients
     * 
     * @param message The message to send
     * @param focusClaudeApp Whether to focus the Claude app (default: false)
     */
    fun sendMessageToAllClients(message: String, focusClaudeApp: Boolean = false) {
        LOG.info("Sending message to all connected clients: $message, focus Claude: $focusClaudeApp")
        
        // Only focus Claude app if explicitly requested
        if (focusClaudeApp) {
            // Execute AppleScript to focus Claude app asynchronously
            val command = "osascript -e 'tell application \"System Events\" to set isRunning to (count of (every process whose name is \"Claude\")) > 0' -e 'if isRunning then' -e 'tell application \"Claude\" to activate' -e 'else' -e 'tell application \"Claude\" to launch' -e 'delay 1' -e 'tell application \"Claude\" to activate' -e 'end if' -e 'tell application \"System Events\" to tell process \"Claude\" to set frontmost to true'"
            CompletableFuture.runAsync {
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("/bin/bash", "-c", command))
                    process.waitFor(5, TimeUnit.SECONDS) // Short timeout to avoid hanging
                    if (process.isAlive()) {
                        LOG.warn("AppleScript focus command timed out, destroying process")
                        process.destroyForcibly()
                    }
                } catch (e: Exception) {
                    LOG.error("Error executing AppleScript to focus Claude app", e)
                }
            }
        }
        
        // Log the active connections for debugging
        LOG.info("Active connections: ${activeConnections.size}, active channels: ${activeChannels.size}")
        activeChannels.forEach { (id, _) -> LOG.info("Active channel: $id") }
        
        if (activeChannels.isEmpty()) {
            LOG.warn("No active WebSocket connections to send message to")
            return
        }
        
        val channelsCopy = HashMap(activeChannels) // Create a copy to avoid concurrent modification
        
        var sendFailures = 0
        channelsCopy.forEach { (channelId, ctx) ->
            try {
                LOG.info("Sending message to client: $channelId")
                
                // Check if channel is still open and writable
                if (!ctx.channel().isOpen || !ctx.channel().isWritable) {
                    throw Exception("Channel is not open or writable")
                }
                
                ctx.writeAndFlush(TextWebSocketFrame(message))
                    .addListener { future ->
                        if (!future.isSuccess) {
                            LOG.error("Failed to send message to client: $channelId", future.cause())
                            // Remove the channel if the send failed
                            activeChannels.remove(channelId)
                            activeConnections.remove(channelId)
                            
                            // If all sends failed, update connection state
                            if (activeChannels.isEmpty()) {
                                LOG.warn("All channels have failed, setting connection state to disconnected")
                                MCPConnectionManager.getInstance().setConnectionState(false)
                            }
                        }
                    }
            } catch (e: Exception) {
                sendFailures++
                LOG.error("Error sending message to client: $channelId", e)
                // Remove the channel if it's no longer valid
                activeChannels.remove(channelId)
                activeConnections.remove(channelId)
            }
        }
        
        // If all sends failed and we had channels to begin with, update the connection state
        if (sendFailures > 0 && sendFailures == channelsCopy.size) {
            LOG.warn("All message sends failed, setting connection state to disconnected")
            MCPConnectionManager.getInstance().setConnectionState(false)
        }
    }
    
    /**
     * Sends a message to the last connected WebSocket client only
     */
    fun sendMessageToLastClient(message: String) {
        LOG.info("Sending message to last connected client: $message")
        if (activeChannels.isEmpty()) {
            LOG.warn("No active WebSocket connections to send message to")
            return
        }
        
        // Get the most recently added channel (if ordered iteration is available)
        val channelsCopy = HashMap(activeChannels) // Create a copy to avoid concurrent modification
        val lastEntry = channelsCopy.entries.lastOrNull()
        
        if (lastEntry != null) {
            try {
                LOG.info("Sending message to last client: ${lastEntry.key}")
                lastEntry.value.writeAndFlush(TextWebSocketFrame(message))
            } catch (e: Exception) {
                LOG.error("Error sending message to last client: ${lastEntry.key}", e)
                // Remove the channel if it's no longer valid
                activeChannels.remove(lastEntry.key)
                activeConnections.remove(lastEntry.key)
            }
        }
    }
    
    /**
     * Gets the current count of active WebSocket connections
     */
    fun getActiveConnectionCount(): Int {
        return activeChannels.size
    }
    
    /**
     * Gets a copy of the active channels map for connection validation
     */
    fun getActiveChannels(): Map<String, ChannelHandlerContext> {
        return HashMap(activeChannels)
    }
    
    /**
     * Removes a channel that has been detected as inactive
     */
    fun removeChannel(channelId: String) {
        LOG.info("Removing inactive channel: $channelId")
        activeChannels.remove(channelId)
        activeConnections.remove(channelId)
    }
    
    /**
     * Validate all channels and remove any that are closed/broken
     * @return The number of valid channels remaining
     */
    fun validateAllChannels(): Int {
        val channelsCopy = HashMap(activeChannels) // Create a copy to avoid concurrent modification
        var validChannels = 0
        
        channelsCopy.forEach { (channelId, ctx) ->
            try {
                val channel = ctx.channel()
                if (!channel.isOpen || !channel.isActive || !channel.isWritable) {
                    LOG.warn("Channel $channelId is not valid (open=${channel.isOpen}, active=${channel.isActive}, writable=${channel.isWritable})")
                    removeChannel(channelId)
                } else {
                    validChannels++
                }
            } catch (e: Exception) {
                LOG.error("Exception checking channel $channelId: ${e.message}")
                removeChannel(channelId)
            }
        }
        
        // Update connection status if no valid channels remain
        if (validChannels == 0 && channelsCopy.isNotEmpty()) {
            LOG.warn("No valid channels remain after validation")
            MCPConnectionManager.getInstance().setConnectionState(false)
        }
        
        return validChannels
    }
    
    /**
     * Send WebSocket ping frames to all connected clients to check if connections are alive
     * This is a more direct and reliable way to check WebSocket status than checking channel properties
     * @return true if at least one ping was successfully sent, false otherwise
     */
    fun sendPingToAllClients(): Boolean {
        LOG.info("Sending WebSocket ping frames to validate connections")
        
        // Log the active connections for debugging
        LOG.info("Active connections for ping: ${activeConnections.size}, active channels: ${activeChannels.size}")
        
        if (activeChannels.isEmpty()) {
            LOG.warn("No active WebSocket connections to ping")
            return false
        }
        
        val channelsCopy = HashMap(activeChannels) // Create a copy to avoid concurrent modification
        var pingSuccess = false // Track if any ping is successful
        
        channelsCopy.forEach { (channelId, ctx) ->
            try {
                // Create a ping frame with a timestamp payload
                val pingContent = io.netty.buffer.Unpooled.wrappedBuffer(
                    System.currentTimeMillis().toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                )
                
                // WebSocket specification includes PING frames to check connection
                val pingFrame = io.netty.handler.codec.http.websocketx.PingWebSocketFrame(pingContent)
                
                LOG.info("Sending WebSocket PING to channel: $channelId")
                
                // Check if channel is still open and writable before sending
                if (!ctx.channel().isOpen || !ctx.channel().isWritable) {
                    throw Exception("Channel is not open or writable for ping")
                }
                
                // IMPORTANT: We consider the ping successful if we can send it
                // This avoids issues with asynchronous callbacks not completing in time
                ctx.writeAndFlush(pingFrame)
                pingSuccess = true // Mark success immediately if we get here
                LOG.info("Successfully sent PING to channel: $channelId")
            } catch (e: Exception) {
                LOG.error("Error sending PING to channel: $channelId", e)
                removeChannel(channelId)
            }
        }
        
        // Return true if we were able to send at least one ping
        return pingSuccess;
    }
}

/**
 * Simple echo response for WebSocket messages
 */
@Serializable
data class EchoResponse(
    val message: String,
    val timestamp: Long,
    val clientId: String? = null,
    val clientAddress: String? = null,
    val serverInfo: String = "IntelliJ MCP WebSocket Server",
    val ideInfo: Map<String, String> = getIdeInfo()
)

/**
 * Get information about the current IDE
 * Only returns the product name to avoid showing unnecessary details
 */
fun getIdeInfo(): Map<String, String> {
    val applicationInfo = com.intellij.openapi.application.ApplicationInfo.getInstance()
    return mapOf(
        "productName" to applicationInfo.fullApplicationName
    )
}

/**
 * WebSocket message for new chat content from LLM tool
 */
@Serializable
data class NewChatMessage(
    val event: String = "new-chat",
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
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
import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap


/**
 * WebSocket service for MCP (Model Context Protocol).
 * Provides a WebSocket endpoint for bi-directional communication with clients.
 */
class MCPWebSocketService : RestService() {
    private val serviceName = "mcpws"
    private val json = Json { 
        prettyPrint = true
        ignoreUnknownKeys = true
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
                    // Send a welcome message after connection
                    val welcome = EchoResponse(
                        message = "Connected to MCP WebSocket Server",
                        timestamp = System.currentTimeMillis()
                    )
                    ctx.writeAndFlush(TextWebSocketFrame(json.encodeToString(welcome)))
                    
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
            // Create a response with more details for debugging
            val response = EchoResponse(
                message = "Echo: $text",
                timestamp = System.currentTimeMillis(),
                clientId = ctx.channel().id().asLongText(),
                clientAddress = ctx.channel().remoteAddress().toString()
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
     */
    fun sendMessageToAllClients(message: String) {
        LOG.info("Sending message to all connected clients: $message")
        
        // Log the active connections for debugging
        LOG.info("Active connections: ${activeConnections.size}, active channels: ${activeChannels.size}")
        activeChannels.forEach { (id, _) -> LOG.info("Active channel: $id") }
        
        if (activeChannels.isEmpty()) {
            LOG.warn("No active WebSocket connections to send message to")
            return
        }
        
        val channelsCopy = HashMap(activeChannels) // Create a copy to avoid concurrent modification
        
        channelsCopy.forEach { (channelId, ctx) ->
            try {
                LOG.info("Sending message to client: $channelId")
                ctx.writeAndFlush(TextWebSocketFrame(message))
            } catch (e: Exception) {
                LOG.error("Error sending message to client: $channelId", e)
                // Remove the channel if it's no longer valid
                activeChannels.remove(channelId)
                activeConnections.remove(channelId)
            }
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
    val serverInfo: String = "IntelliJ MCP WebSocket Server"
)

/**
 * WebSocket message for new chat content from LLM tool
 */
@Serializable
data class NewChatMessage(
    val event: String = "new-chat",
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

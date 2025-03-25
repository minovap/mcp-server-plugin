package org.jetbrains.mcpserverplugin

import com.intellij.openapi.diagnostic.logger
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame

/**
 * Handler for WebSocket frames that forwards them to the WebSocket service.
 */
class WebSocketFrameHandler : SimpleChannelInboundHandler<WebSocketFrame>() {
    private val log = logger<WebSocketFrameHandler>()
    
    // Get the service for each request to ensure we're using latest instance
    private val service: MCPWebSocketService
        get() = MCPWebSocketService.getInstance()
    
    override fun channelRead0(ctx: ChannelHandlerContext, frame: WebSocketFrame) {
        try {
            log.info("WebSocketFrameHandler processing frame: ${frame.javaClass.simpleName}")
            service.handleWebSocketFrame(ctx, frame)
        } catch (e: Exception) {
            log.error("Error handling WebSocket frame: ${e.message}", e)
            // Don't close the connection immediately to allow for debugging
            try {
                ctx.writeAndFlush(TextWebSocketFrame("Error: ${e.message}"))
                log.info("Sent error message to client")
            } catch (ex: Exception) {
                log.error("Failed to send error message, closing connection", ex)
                ctx.close()
            }
        }
    }
    
    override fun channelActive(ctx: ChannelHandlerContext) {
        log.info("Channel active: ${ctx.channel().id().asLongText()} from ${ctx.channel().remoteAddress()}")
        
        // Update connection manager to indicate we have an active connection
        MCPConnectionManager.getInstance().setConnectionState(true)
        
        super.channelActive(ctx)
    }
    
    override fun channelInactive(ctx: ChannelHandlerContext) {
        log.info("Channel inactive: ${ctx.channel().id().asLongText()}")
        
        // Check if there are any remaining active connections
        val activeConnectionCount = service.getActiveConnectionCount()
        if (activeConnectionCount <= 1) { // Using 1 since this connection is still counted
            // If no connections are left, set state to disconnected
            log.info("Last channel becoming inactive, setting connection state to disconnected")
            MCPConnectionManager.getInstance().setConnectionState(false)
        } else {
            log.info("Channel inactive but still have ${activeConnectionCount-1} active connections")
        }
        
        super.channelInactive(ctx)
    }
    
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error("WebSocket handler exception", cause)
        ctx.close()
    }
}
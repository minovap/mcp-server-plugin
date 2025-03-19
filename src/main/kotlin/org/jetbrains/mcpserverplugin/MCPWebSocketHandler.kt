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
        super.channelActive(ctx)
    }
    
    override fun channelInactive(ctx: ChannelHandlerContext) {
        log.info("Channel inactive: ${ctx.channel().id().asLongText()}")
        super.channelInactive(ctx)
    }
    
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error("WebSocket handler exception", cause)
        ctx.close()
    }
}
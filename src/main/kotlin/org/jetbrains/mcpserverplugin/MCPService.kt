package org.jetbrains.ide.mcp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level.APP
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import org.jetbrains.mcpserverplugin.settings.PluginSettings
import io.ktor.http.ContentType.*
import io.ktor.http.contentType
import io.ktor.util.decodeBase64String
import io.ktor.util.encodeBase64
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.QueryStringDecoder
import org.jetbrains.mcpserverplugin.ProjectLockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okio.ByteString.Companion.decodeBase64
import org.jetbrains.ide.RestService
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.McpTool
import org.jetbrains.mcpserverplugin.McpToolManager
import java.nio.charset.StandardCharsets
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.starProjectedType

@Service
class MCPUsageCollector(private val scope: CoroutineScope) {
    private val url = "aHR0cHM6Ly9lb2VtdGw4NW15dTVtcjAubS5waXBlZHJlYW0ubmV0"
    private val client = HttpClient()

    fun sendUsage(toolKey: String) {
        scope.launch {
            try {
                client.post(url.decodeBase64String()) {
                    contentType(Application.Json)
                    setBody("""{"tool_key": "$toolKey"}""")
                }
            } catch (e: Throwable) {
                logger<MCPService>().warn("Failed to sent statistics for tool $toolKey", e)
            }
        }
    }
}

class MCPService : RestService() {
    private val serviceName = "mcp"
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        classDiscriminator = "schemaType"
    }
    
    companion object {
        // Thread-local variable to track if a call is from the MCP tool panel
        val mcpToolPanelContext = ThreadLocal<Boolean>()
    }

    override fun getServiceName(): String = serviceName

    override fun execute(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): String? {
        val path = urlDecoder.path().split(serviceName).last().trimStart('/')
        val project = ProjectLockManager.getInstance().getProjectForMCP() ?: return null
        
        // Use only enabled tools
        val tools = McpToolManager.getEnabledTools() 
        when (path) {
            "list_tools" -> handleListTools(tools, request, context)
            else -> handleToolExecution(path, tools, request, context, project)
        }
        return null
    }

    private fun handleListTools(
        tools: List<AbstractMcpTool<*>>,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ) {
        val toolsList = tools.map { tool ->
            ToolInfo(
                name = tool.name,
                description = tool.description,
                inputSchema = schemaFromDataClass(tool.argKlass)
            )
        }
        sendJson(toolsList, request, context)
    }

    private fun handleToolExecution(
        path: String,
        tools: List<AbstractMcpTool<*>>,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
        project: Project
    ) {
        // Check if this is a call from the MCP tool panel
        val isFromToolPanel = request.headers().contains("X-MCP-Tool-Panel", "true", true)
        
        val tool = tools.find { it.name == path } ?: run {
            sendJson(Response(error = "Unknown tool: $path"), request, context)
            return
        }

        service<MCPUsageCollector>().sendUsage(tool.name)
        val args = try {
            parseArgs(request, tool.argKlass)
        } catch (e: Throwable) {
            logger<MCPService>().warn("Failed to parse arguments for tool $path", e)
            sendJson(Response(error = e.message), request, context)
            return
        }
        val result = try {
            toolHandle(tool, project, args, isFromToolPanel)
        } catch (e: Throwable) {
            logger<MCPService>().warn("Failed to execute tool $path", e)
            Response(error = "Failed to execute tool $path, message ${e.message}")
        }
        sendJson(result, request, context)
    }

    @Suppress("UNCHECKED_CAST")
    private fun sendJson(data: Any, request: FullHttpRequest, context: ChannelHandlerContext) {
        val jsonString = when (data) {
            is List<*> -> json.encodeToString<List<ToolInfo>>(ListSerializer(ToolInfo.serializer()), data as List<ToolInfo>)
            is Response -> json.encodeToString<Response>(Response.serializer(), data)
            else -> throw IllegalArgumentException("Unsupported type for serialization")
        }
        val outputStream = BufferExposingByteArrayOutputStream()
        outputStream.write(jsonString.toByteArray(StandardCharsets.UTF_8))
        send(outputStream, request, context)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> parseArgs(request: FullHttpRequest, klass: KClass<T>): T {
        val body = request.content().toString(StandardCharsets.UTF_8)
        if (body.isEmpty()) {
            return NoArgs as T
        }
        return when (klass) {
            NoArgs::class -> NoArgs as T
            else -> {
                json.decodeFromString(serializer(klass.starProjectedType), body) as T
            }
        }
    }

    private fun <Args : Any> toolHandle(tool: McpTool<Args>, project: Project, args: Any, isFromToolPanel: Boolean = false): Response {
        // We need a way to pass the isFromToolPanel flag to the tool handle method
        // Since we can't directly modify the handle() method signature in the interface,
        // we'll store this in a thread-local variable that LogCollector can check
        mcpToolPanelContext.set(isFromToolPanel)
        try {
            @Suppress("UNCHECKED_CAST")
            return tool.handle(project, args as Args)
        } finally {
            // Clean up the thread-local to prevent memory leaks
            mcpToolPanelContext.remove()
        }
    }

    override fun isMethodSupported(method: HttpMethod): Boolean =
        method === HttpMethod.GET || method === HttpMethod.POST

    override fun getMaxRequestsPerMinute(): Int {
        return 1000000;
    }

    private fun schemaFromDataClass(kClass: KClass<*>): JsonSchemaObject {
        if (kClass == NoArgs::class) return JsonSchemaObject(type = "object")

        val constructor = kClass.primaryConstructor
            ?: error("Class ${kClass.simpleName} must have a primary constructor")

        val properties = constructor.parameters.mapNotNull { param ->
            param.name?.let { name ->
                name to when (param.type.classifier) {
                    String::class -> PropertySchema("string")
                    Int::class, Long::class, Double::class, Float::class -> PropertySchema("number")
                    Boolean::class -> PropertySchema("boolean")
                    List::class -> PropertySchema("array")
                    else -> PropertySchema("object")
                }
            }
        }.toMap()

        val required = constructor.parameters
            .filter { !it.type.isMarkedNullable }
            .mapNotNull { it.name }

        return JsonSchemaObject(
            type = "object",
            properties = properties,
            required = required
        )
    }
}

@Serializable
object NoArgs

@Serializable
data class ToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JsonSchemaObject
)

@Serializable
data class Response(
    val status: String? = null,
    val error: String? = null,
    val logs: List<String>? = null
)

@Serializable
data class JsonSchemaObject(
    val type: String,
    val properties: Map<String, PropertySchema> = emptyMap(),
    val required: List<String> = emptyList(),
    val items: PropertySchema? = null
)

@Serializable
data class PropertySchema(
    val type: String
)

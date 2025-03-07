package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import java.util.concurrent.TimeUnit

@Serializable
data class WaitArgs(val milliseconds: Long = 5000)

class WaitTool : AbstractMcpTool<WaitArgs>() {
    override val name: String = "wait"
    override val description: String = """
        Waits for a specified number of milliseconds (default: 5000ms = 5 seconds).
        Optionally accepts a milliseconds parameter to specify the wait duration.
        Returns "ok" after the wait completes.
        Use this tool when you need to pause before executing the next command.
    """.trimIndent()

    override fun handle(project: Project, args: WaitArgs): Response {
        val waitTime = if (args.milliseconds <= 0) 5000 else args.milliseconds

        try {
            TimeUnit.MILLISECONDS.sleep(waitTime)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Response(error = "Wait interrupted")
        }

        return Response("ok")
    }
}

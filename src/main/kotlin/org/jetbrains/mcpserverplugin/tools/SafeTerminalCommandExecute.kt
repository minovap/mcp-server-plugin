package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.settings.PluginSettings
import org.jetbrains.mcpserverplugin.tools.docker.DockerDefaults
import org.jetbrains.mcpserverplugin.tools.docker.DockerManager
import org.jetbrains.mcpserverplugin.tools.docker.DevContainerConfig

// At the top of your file
private val LOG = Logger.getInstance(SafeTerminalCommandExecute::class.java)

class SafeTerminalCommandExecute : AbstractMcpTool<SafeTerminalCommandArgs>() {
    override val name: String = "safe_terminal_command_execute"
    override val description: String = """
        Safely executes a terminal command in a gitpod/workspace-full Docker container.
        This provides a permissionless and sandboxed environment for running commands safely.
        
        <command> Shell command to execute in the container
        
        safe_terminal_command_execute = ({command: string}) => string | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: SafeTerminalCommandArgs): Response {
        // Get project root directory
        val projectDir = runReadAction<String?> {
            project.guessProjectDir()?.toNioPathOrNull()?.toString()
        } ?: return Response(error = "Could not determine project root directory")

        val projectName = project.name.let {
            // Sanitize the project name for Docker container naming
            // Replace any non-alphanumeric characters with hyphens and convert to lowercase
            it.replace(Regex("[^a-zA-Z0-9]"), "-").lowercase()
        }

        // Initialize Docker manager
        val dockerManager = DockerManager(projectDir, projectName)
        
        // Check if Docker is available
        val dockerPath = dockerManager.dockerPath
            ?: return Response(error = "Docker executable not found. Make sure Docker is installed and in PATH.")

        try {
            // Clean up old containers (older than 24 hours)
            try {
                dockerManager.cleanupOldContainers()
            } catch (e: Exception) {
                // Just log the cleanup error but continue with the main functionality
                LOG.warn("Failed to clean up old containers: ${e.message}")
            }
            
            // Get settings
            val settings = service<PluginSettings>()
            
            // Check for devcontainer.json first
            val config = DevContainerConfig.fromProjectDir(projectDir)
            
            // Get the Docker image - either from settings or the default
            val defaultImage = if (settings.useDefaultDockerImage) DockerDefaults.DEFAULT_IMAGE else settings.dockerImage
            LOG.info("Image from settings: $defaultImage (useDefaultDockerImage: ${settings.useDefaultDockerImage})")
            
            // Log info about found devcontainer.json config
            if (config != null) {
                LOG.info("Found devcontainer.json - Image: ${config.image}, Dockerfile: ${config.build?.dockerfile}")
            }
            
            val dockerImage = dockerManager.getDockerImage(defaultImage)
            LOG.info("Final Docker image selected: $dockerImage")
            
            // Ensure container is running
            LOG.info("Ensuring container is running with image: $dockerImage")
            if (!dockerManager.ensureContainerIsRunning(dockerImage)) {
                return Response(error = "Failed to start Docker container")
            }
            
            // Execute the command
            val output = dockerManager.executeCommand(args.command)
            
            // Format the output
            val formattedOutput = buildString {
                append("bash# ${args.command}")
                append("\n")
                append(output)
            }
            
            return Response(formattedOutput)
        } catch (e: Exception) {
            return Response(error = "Error executing Docker command: ${e.message}")
        }
    }
}

@Serializable
data class SafeTerminalCommandArgs(
    val command: String
)
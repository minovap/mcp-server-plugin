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

        // Initialize Docker manager
        val dockerManager = DockerManager(projectDir)
        
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
                append(simplifyDockerCommand("/docker exec ${dockerManager.containerName} bash -c \"${args.command}\""))
                append("\n----\n")
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

/**
 * Simplifies a Docker command by removing common options and paths,
 * reducing it to a more readable format.
 *
 * @param fullCommand The full Docker command to simplify
 * @return A simplified version of the Docker command
 */
private fun simplifyDockerCommand(fullCommand: String): String {
    // Break the command into tokens by space, preserving quoted sections
    val tokens = mutableListOf<String>()
    var currentToken = StringBuilder()
    var inQuotes = false
    var quoteChar = ' '

    for (char in fullCommand) {
        when {
            (char == '"' || char == '\'') && !inQuotes -> {
                inQuotes = true
                quoteChar = char
                currentToken.append(char)
            }
            char == quoteChar && inQuotes -> {
                inQuotes = false
                currentToken.append(char)
            }
            char == ' ' && !inQuotes -> {
                if (currentToken.isNotEmpty()) {
                    tokens.add(currentToken.toString())
                    currentToken = StringBuilder()
                }
            }
            else -> currentToken.append(char)
        }
    }

    if (currentToken.isNotEmpty()) {
        tokens.add(currentToken.toString())
    }

    // Find the docker binary path - it should be first token ending with "docker" or "docker.exe"
    val dockerIndex = tokens.indexOfFirst { it.endsWith("/docker") || it.endsWith("\\docker.exe") || it == "docker" }
    if (dockerIndex == -1) return fullCommand // can't simplify if docker command not found

    // Find the container name
    val containerIndex = tokens.indexOfFirst { it.startsWith("mcp-intellij-container-") }
    if (containerIndex == -1) return fullCommand // can't simplify if container name not found
    
    // Find bash -c
    val bashIndex = tokens.indexOf("bash")
    if (bashIndex == -1 || bashIndex >= tokens.size - 2 || tokens[bashIndex + 1] != "-c") 
        return fullCommand // can't simplify if bash -c not found

    // Create simplified command
    return "/docker exec ${tokens[containerIndex]} bash -c \"${tokens.subList(bashIndex + 2, tokens.size).joinToString(" ")}\""
}
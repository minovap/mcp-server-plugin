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
private val LOG = Logger.getInstance(ClaudeCodeBashTool::class.java)

class ClaudeCodeBashTool : AbstractMcpTool<BashToolArgs>() {
    override val name: String = "bash"
    override val isClaudeCodeTool: Boolean = true
    override val description: String = """
Executes a given bash command in a persistent shell session with optional timeout, ensuring proper handling and security measures.

Before executing the command, please follow these steps:

1. Directory Verification:
   - If the command will create new directories or files, first use the LS tool to verify the parent directory exists and is the correct location
   - For example, before running "mkdir foo/bar", first use LS to check that "foo" exists and is the intended parent directory

2. Security Check:
   - For security and to limit the threat of a prompt injection attack, some commands are limited or banned. If you use a disallowed command, you will receive an error message explaining the restriction. Explain the error to the User.
   - Verify that the command is not one of the banned commands: alias, curl, curlie, wget, axel, aria2c, nc, telnet, lynx, w3m, links, httpie, xh, http-prompt, chrome, firefox, safari.

3. Command Execution:
   - After ensuring proper quoting, execute the command.
   - Capture the output of the command.

Usage notes:
  - The command argument is required.
  - You can specify an optional timeout in milliseconds (up to 600000ms / 10 minutes). If not specified, commands will timeout after 30 minutes.
  - If the output exceeds 30000 characters, output will be truncated before being returned to you.
  - VERY IMPORTANT: You MUST avoid using search commands like `find` and `grep`. Instead use GrepTool, GlobTool, or dispatch_agent to search. You MUST avoid read tools like `cat`, `head`, `tail`, and `ls`, and use View and LS to read files.
  - When issuing multiple commands, use the ';' or '&&' operator to separate them. DO NOT use newlines (newlines are ok in quoted strings).
""".trimIndent()

    override fun handle(project: Project, args: BashToolArgs): Response {
        // Get project root directory
        val projectDir = runReadAction<String?> {
            project.guessProjectDir()?.toNioPathOrNull()?.toString()
        } ?: return Response(error = "Could not determine project root directory")

        val projectName = project.name.let {
            // Sanitize the project name for Docker container naming
            // Replace any non-alphanumeric characters with hyphens and convert to lowercase
            it.replace(Regex("[^a-zA-Z0-9]"), "-").lowercase()
        }

        // Validate command
        val command = args.command
        
        // Check for banned commands
        val bannedCommands = listOf(
            "alias", "curlie", "axel", "aria2c", "nc", "telnet",
            "lynx", "w3m", "links", "httpie", "xh", "http-prompt", "chrome", 
            "firefox", "safari"
        )
        
        for (bannedCmd in bannedCommands) {
            val bannedRegex = Regex("(^|\\s|;|\\||&)$bannedCmd(\\s|;|\\||&|$)")
            if (bannedRegex.containsMatchIn(command)) {
                return Response(error = "Command '$bannedCmd' is not allowed for security reasons. Please use an alternative approach.")
            }
        }

        // Initialize Docker manager
        val dockerManager = DockerManager(projectDir, projectName)
        
        // Check if Docker is available
        if (dockerManager.dockerPath == null) {
            return Response(error = "Docker executable not found. Make sure Docker is installed and in PATH.")
        }

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
            
            // Execute the command with timeout if specified
            val timeout = args.timeout ?: 1800000 // Default to 30 minutes if not specified
            val limitedTimeout = minOf(timeout, 600000) // Limit to max 10 minutes (600,000 ms)
            
            val output = dockerManager.executeCommand(command, limitedTimeout)
            
            // Truncate output if it exceeds 30,000 characters
            val truncatedOutput = if (output.length > 30000) {
                output.substring(0, 30000) + "\n... (output truncated, exceeds 30000 characters)"
            } else {
                output
            }
            
            // Format the output
            val formattedOutput = buildString {
                append("bash# ${args.command}")
                append("\n")
                append(truncatedOutput)
            }
            
            return Response(formattedOutput)
        } catch (e: Exception) {
            return Response(error = "Error executing Docker command: ${e.message}")
        }
    }
}

@Serializable
data class BashToolArgs(
    val command: String,
    val timeout: Int? = null
)
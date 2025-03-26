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
import org.jetbrains.mcpserverplugin.utils.LogCollector

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
        // Create a log collector for diagnostic information
        val logCollector = LogCollector()
        logCollector.info("Starting bash tool execution")
        
        // Get project root directory
        val projectDir = runReadAction<String?> {
            project.guessProjectDir()?.toNioPathOrNull()?.toString()
        } ?: return Response(error = "Could not determine project root directory", logs = logCollector.getMessages())

        logCollector.info("Project directory: $projectDir")
        
        val projectName = project.name.let {
            // Sanitize the project name for Docker container naming
            // Replace any non-alphanumeric characters with hyphens and convert to lowercase
            val sanitized = it.replace(Regex("[^a-zA-Z0-9]"), "-").lowercase()
            logCollector.info("Sanitized project name: $sanitized")
            sanitized
        }

        // Validate command
        val command = args.command
        logCollector.info("Command to execute: $command")
        
        // Check for banned commands
        val bannedCommands = listOf(
            "alias", "curlie", "axel", "aria2c", "nc", "telnet",
            "lynx", "w3m", "links", "httpie", "xh", "http-prompt", "chrome", 
            "firefox", "safari"
        )
        logCollector.info("Checking for banned commands")
        
        for (bannedCmd in bannedCommands) {
            val bannedRegex = Regex("(^|\\s|;|\\||&)$bannedCmd(\\s|;|\\||&|$)")
            if (bannedRegex.containsMatchIn(command)) {
                logCollector.error("Command contains banned term: '$bannedCmd'")
                return Response(error = "Command '$bannedCmd' is not allowed for security reasons. Please use an alternative approach.", logs = logCollector.getMessages())
            }
        }
        logCollector.info("No banned commands found")

        // Initialize Docker manager with a log collector function
        logCollector.info("Initializing Docker manager")
        // Create a log collector function that adds Docker logs to our logs collection
        val dockerLogCollector: (String) -> Unit = { logMessage ->
            // Add the Docker log messages to our logs list with a DOCKER: prefix
            logCollector.info("DOCKER: $logMessage")
        }
        val dockerManager = DockerManager(projectDir, projectName, dockerLogCollector)
        
        // Check if Docker is available
        if (dockerManager.dockerPath == null) {
            logCollector.error("Docker executable not found")
            return Response(error = "Docker executable not found. Make sure Docker is installed and in PATH.", logs = logCollector.getMessages())
        }
        logCollector.info("Docker executable found at: ${dockerManager.dockerPath}")

        try {
            logCollector.info("Starting Docker container operation")
            
            // Clean up old containers (older than 24 hours)
            try {
                logCollector.info("Cleaning up old containers")
                dockerManager.cleanupOldContainers()
                logCollector.info("Old containers cleanup completed")
            } catch (e: Exception) {
                // Just log the cleanup error but continue with the main functionality
                LOG.warn("Failed to clean up old containers: ${e.message}")
                logCollector.warn("Failed to clean up old containers: ${e.message}")
            }
            
            // Get settings
            logCollector.info("Getting plugin settings")
            val settings = service<PluginSettings>()
            
            // Check for devcontainer.json first
            logCollector.info("Checking for devcontainer.json")
            val config = DevContainerConfig.fromProjectDir(projectDir)
            
            // Get the Docker image - either from settings or the default
            val defaultImage = if (settings.useDefaultDockerImage) DockerDefaults.DEFAULT_IMAGE else settings.dockerImage
            LOG.info("Image from settings: $defaultImage (useDefaultDockerImage: ${settings.useDefaultDockerImage})")
            logCollector.info("Image from settings: $defaultImage (useDefaultDockerImage: ${settings.useDefaultDockerImage})")
            
            // Log info about found devcontainer.json config
            if (config != null) {
                LOG.info("Found devcontainer.json - Image: ${config.image}, Dockerfile: ${config.build?.dockerfile}")
                logCollector.info("Found devcontainer.json - Image: ${config.image}, Dockerfile: ${config.build?.dockerfile}")
            } else {
                logCollector.info("No devcontainer.json found, using default settings")
            }
            
            val dockerImage = dockerManager.getDockerImage(defaultImage)
            LOG.info("Final Docker image selected: $dockerImage")
            logCollector.info("Final Docker image selected: $dockerImage")
            
            // Ensure container is running
            LOG.info("Ensuring container is running with image: $dockerImage")
            logCollector.info("Ensuring container is running with image: $dockerImage")
            if (!dockerManager.ensureContainerIsRunning(dockerImage)) {
                logCollector.error("Failed to start Docker container")
                return Response(error = "Failed to start Docker container", logs = logCollector.getMessages())
            }
            logCollector.info("Docker container is running successfully")
            
            // Execute the command with timeout if specified
            val timeout = args.timeout ?: 1800000 // Default to 30 minutes if not specified
            val limitedTimeout = minOf(timeout, 600000) // Limit to max 10 minutes (600,000 ms)
            logCollector.info("Executing command with timeout: ${limitedTimeout}ms")
            LOG.info("BASH_CMD_START: Executing bash command with timeout ${limitedTimeout}ms: $command")
            
            val startTime = System.currentTimeMillis()
            val output = dockerManager.executeCommand(command, limitedTimeout)
            val executionTime = System.currentTimeMillis() - startTime
            LOG.info("BASH_CMD_COMPLETE: Command completed in ${executionTime}ms")
            logCollector.info("Command executed, output length: ${output.length} characters, execution time: ${executionTime}ms")
            LOG.info("BASH_CMD_EXECUTION_TIME: ${executionTime}ms")
            
            // Truncate output if it exceeds 30,000 characters
            val truncatedOutput = if (output.length > 30000) {
                logCollector.info("Output exceeds 30,000 characters, truncating")
                output.substring(0, 30000) + "\n... (output truncated, exceeds 30000 characters)"
            } else {
                output
            }
            
            // Format the output with BASH_ prefixed logs (similar to DOCKER_ logs)
            val formattedOutput = buildString {
                append("bash# ${args.command}")
                append("\n")
                append(truncatedOutput)
            }
            
            logCollector.info("Command execution completed successfully")
            LOG.info("BASH_CMD_SUCCESS: Command execution successful with output length ${output.length} characters")
            return Response(formattedOutput, logs = logCollector.getMessages())
        } catch (e: Exception) {
            logCollector.error("Exception during Docker command execution: ${e.message}")
            logCollector.error("${e.stackTraceToString()}")
            LOG.error("BASH_CMD_ERROR: Exception during command execution: ${e.message}", e)
            return Response(error = "Error executing Docker command: ${e.message}", logs = logCollector.getMessages())
        }
    }
}

@Serializable
data class BashToolArgs(
    val command: String,
    val timeout: Int? = null
)
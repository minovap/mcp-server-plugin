package org.jetbrains.mcpserverplugin.tools

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.toNioPathOrNull
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.settings.PluginSettings
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Paths
import java.text.SimpleDateFormat
import kotlin.io.path.exists
import kotlin.math.absoluteValue

// At the top of your file
private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(SafeTerminalCommandExecute::class.java)

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
        val projectDir = com.intellij.openapi.application.runReadAction<String?> {
            project.guessProjectDir()?.toNioPathOrNull()?.toString()
        } ?: return Response(error = "Could not determine project root directory")

        // Get docker image from settings
        val dockerImage = com.intellij.openapi.components.service<PluginSettings>().state.dockerImage ?: "gitpod/workspace-full"

        // Find docker executable path
        val dockerPath = findDockerExecutable()
            ?: return Response(error = "Docker executable not found. Make sure Docker is installed and in PATH.")

        // Generate a unique container name based on hash of project directory
        val containerName = "mcp-intellij-container-${projectDir.hashCode().absoluteValue}"
        val containerPrefix = "mcp-intellij-container-"

        // Clean up old containers (older than 24 hours)
        try {
            cleanupOldContainers(dockerPath, containerPrefix)
        } catch (e: Exception) {
            // Just log the cleanup error but continue with the main functionality
            LOG.warn("Failed to clean up old containers: ${e.message}")
        }

        // Check if container already exists and its status
        val checkContainerCmd = listOf(
            dockerPath, "ps", "-a", "--filter", "name=$containerName", "--format", "{{.Status}}"
        )
        val containerStatus = Runtime.getRuntime().exec(checkContainerCmd.toTypedArray()).inputStream.bufferedReader().readText().trim()
        val containerExists = containerStatus.isNotEmpty()

        // Determine the appropriate Docker command
        val createContainerCmd = listOf(
            dockerPath,
            "run",
            "--user",
            "root",
            "--name", containerName,
            "--user", "root",
            "--platform", "linux/amd64",
            "-d",  // Run in detached mode
            "-v", "$projectDir:$projectDir",
            "-w", projectDir,
            dockerImage,
            "tail", "-f", "/dev/null"  // Keep container running
        )

        val execCommand = listOf(
            dockerPath, "exec",
            "-w", projectDir,
            containerName,
            "bash", "-c", args.command
        )

        // Handle container state
        try {
            if (!containerExists) {
                // Create new container if it doesn't exist
                val process = ProcessBuilder(createContainerCmd)
                    .redirectErrorStream(true)
                    .start()
                process.waitFor()
                Thread.sleep(1000) // Wait for container to fully start
            } else if (containerStatus.contains("Exited") || !containerStatus.startsWith("Up")) {
                // Remove and recreate problematic container
                Runtime.getRuntime().exec(listOf(dockerPath, "rm", "-f", containerName).toTypedArray()).waitFor()
                val process = ProcessBuilder(createContainerCmd)
                    .redirectErrorStream(true)
                    .start()
                process.waitFor()
                Thread.sleep(1000) // Wait for container to fully start
            }

            // Execute the command in the container
            val dockerCommandString = execCommand.joinToString(" ") {
                if (it.contains(" ") || it.contains("\"") || it.contains("'")) "\"$it\"" else it
            }

            // Execute the command
            val process = ProcessBuilder(execCommand)
                .redirectErrorStream(true)
                .start()

            // Read the output
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            // Wait for the process to complete
            val exitCode = process.waitFor()

            // Format the output as requested
            val formattedOutput = buildString {
                append(simplifyDockerCommand(dockerCommandString))
                append("\n----\n")
                append(output)
                if (exitCode != 0) {
                    append("\nExit code: ").append(exitCode)
                }
            }

            return Response(formattedOutput)

        } catch (e: Exception) {
            return Response(error = "Error executing Docker command: ${e.message}")
        }
    }

    /**
     * Cleans up containers with the specified prefix that are older than 24 hours
     */
    private fun cleanupOldContainers(dockerPath: String, containerPrefix: String) {
        // Get all containers with our prefix
        val listCommand = listOf(
            dockerPath, "container", "ls", "--all",
            "--filter", "name=$containerPrefix",
            "--format", "{{.ID}}|{{.CreatedAt}}"
        )

        val result = Runtime.getRuntime().exec(listCommand.toTypedArray())
        result.waitFor()
        val containerInfo = result.inputStream.bufferedReader().readLines().filter { it.isNotEmpty() }

        val twentyFourHoursAgoMillis = System.currentTimeMillis() - (12 * 60 * 60 * 1000)
        val containerIdsToRemove = mutableListOf<String>()

        // Filter containers by age
        for (info in containerInfo) {
            try {
                val parts = info.split("|")
                if (parts.size != 2) continue

                val containerId = parts[0]
                val createdAt = parts[1]

                // Parse Docker timestamp format (2023-04-26 15:44:37 -0700 PDT)
                val dateStr = createdAt.substringBeforeLast(" ") // Remove timezone name
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z")
                val containerDate = dateFormat.parse(dateStr)

                if (containerDate.time < twentyFourHoursAgoMillis) {
                    containerIdsToRemove.add(containerId)
                }
            } catch (e: Exception) {
                LOG.warn("Error parsing container creation time: ${e.message}")
            }
        }

        // Remove old containers
        if (containerIdsToRemove.isNotEmpty()) {
            LOG.info("Found ${containerIdsToRemove.size} old containers to remove")

            try {
                val removeCommand = mutableListOf(dockerPath, "container", "rm", "--force")
                removeCommand.addAll(containerIdsToRemove)

                val process = ProcessBuilder(removeCommand)
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    LOG.info("Successfully removed old containers: $output")
                } else {
                    LOG.warn("Issue removing containers. Exit code: $exitCode, Output: $output")
                }
            } catch (e: Exception) {
                LOG.warn("Failed to remove old containers: ${e.message}")
            }
        } else {
            LOG.info("No old containers to remove")
        }
    }

    /**
     * Attempts to find the Docker executable in common locations and PATH
     * @return Full path to docker executable or null if not found
     */
    private fun findDockerExecutable(): String? {
        // Check common Docker installation paths
        val commonPaths = listOf(
            "/usr/bin/docker",
            "/usr/local/bin/docker",
            "/opt/homebrew/bin/docker",  // macOS Homebrew on Apple Silicon
            "/usr/local/Cellar/docker",  // Other Homebrew location
            "C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe"  // Windows
        )

        for (path in commonPaths) {
            if (File(path).exists()) {
                return path
            }
        }

        // Check PATH environment
        val pathEnv = System.getenv("PATH") ?: return null
        val pathDirs = pathEnv.split(File.pathSeparator)

        for (dir in pathDirs) {
            val dockerPath = Paths.get(dir, "docker").toString()
            val dockerExePath = Paths.get(dir, "docker.exe").toString()

            if (File(dockerPath).exists()) {
                return dockerPath
            }
            if (File(dockerExePath).exists()) {
                return dockerExePath
            }
        }

        // Try using "which docker" or "where docker" to find Docker
        try {
            val command = if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                listOf("where", "docker")
            } else {
                listOf("which", "docker")
            }

            // Create a command line with console environment
            val commandLine = GeneralCommandLine(command)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

            // Create a process handler
            val processHandler = OSProcessHandler(commandLine)
            val output = StringBuilder()

            // Add a process listener to capture output
            processHandler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (outputType === ProcessOutputTypes.STDOUT) {
                        output.append(event.text)
                    }
                }
            })

            // Start and wait for process
            processHandler.startNotify()
            if (processHandler.waitFor(5000)) {
                val exitCode = processHandler.exitCode ?: -1
                val outputString = output.toString().trim()

                if (exitCode == 0 && outputString.isNotEmpty()) {
                    return outputString
                } else {
                    LOG.warn("Docker not found. Output: ${outputString.ifEmpty { "null" }}, Exit code: $exitCode")
                    return null
                }
            } else {
                LOG.warn("Process timed out while searching for Docker")
                processHandler.destroyProcess()
                return null
            }
        } catch (e: Exception) {
            // Ignore exceptions from which/where commands
        }

        return null
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

    // Find the image name index
    val imageIndex = tokens.indexOf("gitpod/workspace-full")
    if (imageIndex == -1) return fullCommand // can't simplify if image not found

    // Find the command after the image (usually bash -c)
    // Need to search for bash after the image index
    val commandIndex = tokens.indexOfFirst { it == "bash" && tokens.indexOf(it) > imageIndex }
    if (commandIndex == -1) return fullCommand // can't simplify if bash command not found

    // Create simplified command: '/docker run image bash -c'
    val simplified = StringBuilder("/docker")

    // Add 'run' or 'exec' command
    val runOrExecIndex = tokens.indexOfFirst { it == "run" || it == "exec" }
    if (runOrExecIndex != -1) {
        simplified.append(" ").append(tokens[runOrExecIndex])
    }

    // Add the image name
    simplified.append(" ").append(tokens[imageIndex])

    // Add the remaining command (bash -c and anything that follows)
    for (i in commandIndex until tokens.size) {
        simplified.append(" ").append(tokens[i])
    }

    return simplified.toString()
}
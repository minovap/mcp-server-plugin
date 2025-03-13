package org.jetbrains.mcpserverplugin.tools.docker

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import org.jetbrains.mcpserverplugin.settings.MCPConfigurable
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.math.absoluteValue

private val LOG = Logger.getInstance(DockerManager::class.java)

/**
 * Constants for default Docker image and platform
 */
object DockerDefaults {
    /**
     * Default Docker image to use when none is specified
     */
    const val DEFAULT_IMAGE = MCPConfigurable.DEFAULT_DOCKER_IMAGE

    /**
     * Default platform to use for the default image (null means use host platform)
     */
    const val DEFAULT_IMAGE_PLATFORM = "linux/amd64"
}

/**
 * Manages Docker operations for safe terminal command execution
 */
class DockerManager(private val projectDir: String, private val projectName: String) {

    // Store the Dockerfile content hash from the last build
    // This helps detect when Dockerfile content has changed
    private var lastDockerfileContentHash: String? = null
    private var lastDockerfilePath: String? = null

    /**
     * Path to the Docker executable
     */
    val dockerPath: String? = findDockerExecutable()

    /**
     * Generate a unique container name based on project directory hash
     */
    val containerName: String = if (!projectName.isNullOrEmpty()) {
        "mcp-intellij-$projectName"
    } else {
        "mcp-intellij-container-${projectDir.hashCode().absoluteValue}"
    }

    /**
     * Prefix for container names for cleanup purposes
     */
    private val containerPrefix = "mcp-intellij-container-"

    /**
     * Gets the appropriate Docker image to use based on devcontainer.json configuration
     * or falls back to default image
     *
     * @return The Docker image to use
     */
    fun getDockerImage(defaultImage: String = DockerDefaults.DEFAULT_IMAGE): String {
        val config = DevContainerConfig.fromProjectDir(projectDir)

        return when {
            // If image is explicitly specified, use it
            config?.image != null -> {
                LOG.info("Using image from devcontainer.json: ${config.image}")
                config.image
            }

            // If Dockerfile is specified, build and use custom image
            config?.build?.dockerfile != null -> {
                val imageName = buildCustomImage(config)
                LOG.info("Using custom built image from dockerfile ${config.build.dockerfile}: $imageName")
                imageName
            }
            // Log if we found config but no image or build
            config != null -> {
                LOG.info("Found devcontainer.json but no image or build specified")
                LOG.info("Falling back to default image: $defaultImage")
                defaultImage
            }

            // Fall back to default image
            else -> {
                LOG.info("Using default Docker image: $defaultImage")
                defaultImage
            }
        }
    }

    /**
     * Builds a custom Docker image based on the devcontainer.json configuration
     *
     * @param config The devcontainer configuration
     * @return The name of the built image
     */
    private fun buildCustomImage(config: DevContainerConfig): String {
        dockerPath ?: throw IllegalStateException("Docker executable not found")

        // Generate a unique image name based on project dir hash
        val imageName = "mcp-intellij-image-${projectDir.hashCode().absoluteValue}"

        // Determine context and Dockerfile paths
        val dockerfilePath = config.build?.dockerfile ?: ".llm/Dockerfile"
        val contextPath = config.build?.context ?: Paths.get(dockerfilePath).parent?.toString() ?: ".llm"

        val fullDockerfilePath = if (Paths.get(dockerfilePath).isAbsolute) {
            dockerfilePath
        } else {
            Paths.get(projectDir, dockerfilePath).toString()
        }

        val fullContextPath = if (Paths.get(contextPath).isAbsolute) {
            contextPath
        } else {
            Paths.get(projectDir, contextPath).toString()
        }

        LOG.info("Building Docker image: $imageName")
        LOG.info("Dockerfile path: $fullDockerfilePath")
        LOG.info("Context path: $fullContextPath")

        // Check if Dockerfile exists
        val dockerfileExists = File(fullDockerfilePath).exists()
        LOG.info("Dockerfile exists: $dockerfileExists")
        val dockerfileContent = if (dockerfileExists) File(fullDockerfilePath).readText() else ""

        // Determine platform - use explicit platform from config, or detect based on image
        val platform = config.platform ?: if (
            (File(fullDockerfilePath).exists() && File(fullDockerfilePath).readText().contains(DockerDefaults.DEFAULT_IMAGE))
        ) DockerDefaults.DEFAULT_IMAGE_PLATFORM else null

        // Build Docker image
        val buildCommandBase = mutableListOf(dockerPath, "build")

        // Create a unique tag that includes a hash of the Dockerfile content to force rebuilds
        // when the Dockerfile changes
        val dockerfileHash = dockerfileContent.hashCode().absoluteValue.toString().take(8)
        val uniqueTag = "$imageName:$dockerfileHash"
        LOG.info("Using unique image tag: $uniqueTag (based on Dockerfile hash)")

        // Add the tag
        buildCommandBase.add("-t")
        buildCommandBase.add(uniqueTag)

        // Also tag as the base image name
        buildCommandBase.add("-t")
        buildCommandBase.add(imageName)

        // Add platform specification if available
        if (platform != null) {
            buildCommandBase.addAll(listOf("--platform", platform))
        }

        // Add the dockerfile and context path
        buildCommandBase.addAll(listOf(
            "-f", fullDockerfilePath,
            fullContextPath
        ))

        val buildCommand = buildCommandBase

        try {
            LOG.info("Running Docker build command: ${buildCommand.joinToString(" ")}")

            // Use GeneralCommandLine instead of Runtime.exec to properly inherit environment variables
            val commandLine = GeneralCommandLine(buildCommand)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

            // Create a process handler that inherits environment variables
            val processHandler = OSProcessHandler(commandLine)
            val stdoutBuffer = StringBuilder()
            val stderrBuffer = StringBuilder()

            // Add a process listener to capture output
            processHandler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (outputType === ProcessOutputTypes.STDOUT) {
                        LOG.info("[Docker build] ${event.text}")
                        stdoutBuffer.append(event.text)
                    } else if (outputType === ProcessOutputTypes.STDERR) {
                        LOG.error("[Docker build error] ${event.text}")
                        stderrBuffer.append(event.text)
                    }
                }
            })

            // Start and wait for process
            processHandler.startNotify()
            val processFinished = processHandler.waitFor(600000) // 10 minute timeout for build

            if (!processFinished) {
                LOG.error("Docker build process timed out after 10 minutes")
                processHandler.destroyProcess()
                throw IllegalStateException("Docker build timed out")
            }

            val exitCode = processHandler.exitCode ?: -1
            if (exitCode != 0) {
                LOG.error("Docker build failed with exit code: $exitCode")
                LOG.error("Docker build stdout: ${stdoutBuffer}")
                LOG.error("Docker build stderr: ${stderrBuffer}")
                throw IllegalStateException("Docker build failed with exit code: $exitCode")
            }

            LOG.info("Docker build completed successfully for image: $imageName")
            return uniqueTag
        } catch (e: Exception) {
            LOG.error("Failed to build Docker image: ${e.message}")
            throw IllegalStateException("Failed to build Docker image: ${e.message}", e)
        }
    }

    /**
     * Cleans up containers with the specified prefix that are older than 24 hours
     */
    fun cleanupOldContainers() {
        val dockerPath = dockerPath ?: return

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
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z")
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
     * Creates or restarts a Docker container for command execution
     *
     * @param dockerImageName The Docker image to use
     * @return true if container is ready, false otherwise
     */
    fun ensureContainerIsRunning(dockerImageName: String): Boolean {

        LOG.info("Ensuring container is running with image: $dockerImageName")

        val dockerPath = dockerPath ?: return false

        // Check if we need to specify platform for this image
        val needsForcedPlatform = dockerImageName == DockerDefaults.DEFAULT_IMAGE

        // Try to read platform from devcontainer.json
        val config = DevContainerConfig.fromProjectDir(projectDir)
        val platform = config?.platform ?: if (needsForcedPlatform) DockerDefaults.DEFAULT_IMAGE_PLATFORM else null

        // Check if container already exists and its status
        val checkContainerCmd = listOf(
            dockerPath, "ps", "-a", "--filter", "name=$containerName", "--format", "{{.Status}}"
        )

        val containerStatus = Runtime.getRuntime().exec(checkContainerCmd.toTypedArray()).inputStream.bufferedReader().readText().trim()
        val containerExists = containerStatus.isNotEmpty()
        var containerImage: String

        // If container exists, check if it's using the correct image
        if (containerExists) {
            // Get the image of the existing container
            val containerImageCmd = listOf(
                dockerPath, "inspect",
                "--format", "{{.Config.Image}}",
                containerName
            )

            containerImage = Runtime.getRuntime().exec(containerImageCmd.toTypedArray())
                .inputStream.bufferedReader().readText().trim()

            LOG.info("Existing container image: $containerImage, Requested image: $dockerImageName")

            // If images don't match, remove the container and recreate it
            if (containerImage != dockerImageName) {
                // If the image contains a tag (custom built with a hash), check if it's just a newer version of the same base image
                if (dockerImageName.contains(":") && containerImage.contains(":")) {
                    val imageTag = dockerImageName.substringAfter(":")
                    val containerTag = containerImage.substringAfter(":")
                    val imageBase = dockerImageName.substringBefore(":")
                    val containerBase = containerImage.substringBefore(":")

                    if (imageBase == containerBase) {
                        LOG.info("Container exists but Dockerfile has changed. Rebuilding with new tag $imageTag (was $containerTag)")
                    }
                } else {
                    LOG.info("Container exists but using different image. Removing and recreating.")
                }

                Runtime.getRuntime().exec(listOf(dockerPath, "rm", "-f", containerName).toTypedArray()).waitFor()
                return createContainer(dockerImageName, platform)
            }
        }


        // If container exists but not running properly, remove and recreate it
        if (containerExists && !containerStatus.startsWith("Up")) {
            Runtime.getRuntime().exec(listOf(dockerPath, "rm", "-f", containerName).toTypedArray()).waitFor()
            return createContainer(dockerImageName, platform)
        }

        // Create new container if it doesn't exist
        if (!containerExists) {
            LOG.info("Container doesn't exist. Creating new container.")
            return createContainer(dockerImageName, platform)
        }

        // Container exists and is running
        LOG.info("Container is already running.")
        return true
    }

    /**
     * Creates a new Docker container
     *
     * @param dockerImage The Docker image to use
     * @param platform Optional platform specification (e.g., "linux/amd64")
     * @return true if container was created successfully, false otherwise
     */
    private fun createContainer(dockerImage: String, platform: String? = null): Boolean {
        val dockerPath = dockerPath ?: return false

        val createContainerCmd = mutableListOf(
            dockerPath,
            "run",
            "--user", "root",
            "--name", containerName
        )

        // Add platform if specified
        if (platform != null) {
            createContainerCmd.addAll(listOf("--platform", platform))
        }

        // Add remaining container options
        createContainerCmd.addAll(listOf(
            "-d",  // Run in detached mode
            "-v", "$projectDir:$projectDir",
            "-w", projectDir,
            dockerImage,
            "tail", "-f", "/dev/null"  // Keep container running
        ))

        try {
            val process = ProcessBuilder(createContainerCmd)
                .redirectErrorStream(true)
                .start()

            val exitCode = process.waitFor()
            Thread.sleep(1000) // Wait for container to fully start

            if (exitCode != 0) {
                LOG.error("Failed to create container. Exit code: $exitCode")
                return false
            }

            return true
        } catch (e: Exception) {
            LOG.error("Error creating container: ${e.message}")
            return false
        }
    }

    /**
     * Executes a command in the Docker container
     *
     * @param command The command to execute
     * @param timeoutMs Optional timeout in milliseconds (default is 1800000 - 30 minutes)
     * @return The command output or error message
     */
    fun executeCommand(command: String, timeoutMs: Int = 1800000): String {
        val dockerPath = dockerPath ?: return "Docker executable not found"

        // Check if tmux is available in the container
        val tmuxAvailable = isTmuxAvailable(dockerPath, containerName)

        return if (tmuxAvailable) {
            executeTmuxCommand(dockerPath, command, timeoutMs)
        } else {
            executeSimpleCommand(dockerPath, command, timeoutMs)
        }
    }

    /**
     * Checks if tmux is available in the container
     *
     * @param dockerPath Path to docker executable
     * @param containerName Name of the container
     * @return True if tmux is available, false otherwise
     */
    private fun isTmuxAvailable(dockerPath: String, containerName: String): Boolean {
        val checkTmuxCommand = listOf(
            dockerPath, "exec",
            containerName,
            "bash", "-c",
            "command -v tmux >/dev/null 2>&1 && echo 'DOCKER_AVAILABLE' || echo 'NO_DOCKER_FOUND'"
        )

        try {
            val checkProcess = ProcessBuilder(checkTmuxCommand)
                .redirectErrorStream(true)
                .start()

            val result = checkProcess.inputStream.bufferedReader().readText().trim()
            checkProcess.waitFor()

            return result.contains("DOCKER_AVAILABLE")
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Executes a command in the Docker container using the original simple approach
     *
     * @param dockerPath Path to docker executable
     * @param command The command to execute
     * @param timeoutMs Timeout in milliseconds
     * @return The command output or error message
     */
    private fun executeSimpleCommand(dockerPath: String, command: String, timeoutMs: Int = 1800000): String {
        LOG.info("DOCKER_SIMPLE_EXEC: Using simple execution method with timeout ${timeoutMs}ms")
        val execCommand = listOf(
            dockerPath, "exec",
            "-w", projectDir,
            containerName,
            "bash", "-c", command
        )
        try {
            val process = ProcessBuilder(execCommand)
                .redirectErrorStream(true)
                .start()
                
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            
            // Start a timeout handler
            val future = java.util.concurrent.CompletableFuture<Boolean>()
            val timeoutThread = Thread {
                try {
                    Thread.sleep(timeoutMs.toLong())
                    if (!future.isDone) {
                        LOG.warn("DOCKER_TIMEOUT: Command execution timed out after ${timeoutMs/1000} seconds")
                        process.destroy()
                        future.complete(false)
                    }
                } catch (e: InterruptedException) {
                    // Thread was interrupted, which means process completed
                }
            }
            timeoutThread.isDaemon = true
            timeoutThread.start()
            
            var line: String?
            try {
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
                future.complete(true)
            } catch (e: Exception) {
                LOG.warn("DOCKER_READ_ERROR: Error reading process output: ${e.message}")
                if (!future.isDone) {
                    future.complete(false)
                }
            }
            
            val completed = future.getNow(false)
            if (!completed) {
                return output.toString() + "\n[Command execution timed out after ${timeoutMs/1000} seconds]"
            }
            
            val exitCode = if (process.isAlive) {
                process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
                process.exitValue()
            } else {
                process.exitValue()
            }
            
            if (exitCode != 0) {
                output.append("\nExit code: ").append(exitCode)
            }
            
            return output.toString()
        } catch (e: Exception) {
            return "Error executing command: ${e.message}"
        }
    }

    /**
     * Executes a command in the Docker container using tmux for better user experience
     *
     * @param dockerPath Path to docker executable
     * @param command The command to execute
     * @param timeoutMs Timeout in milliseconds
     * @return The command output or error message
     */
    private fun executeTmuxCommand(dockerPath: String, command: String, timeoutMs: Int = 1800000): String {
        LOG.info("DOCKER_TMUX_EXEC: Using tmux execution method")

        // Generate a unique output file for this command
        val outputFileId = System.currentTimeMillis().toString()
        val outputFileName = "/tmp/docker_output_$outputFileId.txt"
        val markerFileName = "/tmp/docker_marker_$outputFileId.txt"

        // Fixed tmux session name for predictable connection
        val tmuxSessionName = "llm-session"

        // First, ensure the tmux session exists
        val createSessionCommand = listOf(
            dockerPath, "exec",
            containerName,
            "bash", "-c",
            "tmux has-session -t $tmuxSessionName 2>/dev/null || tmux new-session -d -s $tmuxSessionName"
        )

        try {
            val sessionProcess = ProcessBuilder(createSessionCommand).start()
            sessionProcess.waitFor()
        } catch (e: Exception) {
            LOG.warn("DOCKER_TMUX_ERROR: Failed to create tmux session: ${e.message}")
        }

        // before running a command send CTRL+C to cancel any input from a human tmux observer
        val interruptCommand = listOf(
            dockerPath, "exec",
            containerName,
            "bash", "-c",
            """tmux send-keys -t $tmuxSessionName C-c"""
        )

        try {
            ProcessBuilder(interruptCommand).start().waitFor()
            // Small delay to ensure the interrupt is processed
            Thread.sleep(200)
        } catch (e: Exception) {
            LOG.warn("DOCKER_INTERRUPT_ERROR: Failed to send interrupt: ${e.message}")
        }

        // Prepare a command that will:
        // 1. Run in tmux (for visual feedback)
        // 2. Save complete output to a file
        // 3. Create a marker file when done
        val wrappedCommand = """
        cd $projectDir && 
        clear &&
        (echo "~/# $command" && 
        $command && 
        echo $? > ${outputFileName}.exit || 
        echo $? > ${outputFileName}.exit) | 
        tee $outputFileName && 
        touch $markerFileName
    """.trimIndent().replace("\n", " ")

        // Run the command in the tmux session
        val execCommand = listOf(
            dockerPath, "exec",
            "-w", projectDir,
            containerName,
            "bash", "-c",
            """tmux send-keys -t $tmuxSessionName '$wrappedCommand' C-m"""
        )

        // Send the command to tmux
        LOG.info("DOCKER_CMD_START: Sending command to tmux")
        ProcessBuilder(execCommand).start().waitFor()

        // Give a moment for the process to start
        Thread.sleep(100)
        LOG.info("DOCKER_CMD_WAITING: Waiting for command to complete")

        // Poll for command completion by checking for the marker file
        val maxWaitTimeMs = timeoutMs.toLong() // Use provided timeout
        val pollIntervalMs = 250L
        val startTime = System.currentTimeMillis()

        var isCommandComplete = false

        // Wait for the command to complete by checking for marker file
        while (System.currentTimeMillis() - startTime < maxWaitTimeMs && !isCommandComplete) {
            // Check if the marker file exists
            val checkMarkerCommand = listOf(
                dockerPath, "exec",
                containerName,
                "bash", "-c",
                "[ -f $markerFileName ] && echo 'COMPLETE' || echo 'RUNNING'"
            )

            try {
                val checkProcess = ProcessBuilder(checkMarkerCommand)
                    .redirectErrorStream(true)
                    .start()

                val markerCheck = checkProcess.inputStream.bufferedReader().readText().trim()
                checkProcess.waitFor()

                // Check if the marker file exists
                isCommandComplete = markerCheck.contains("COMPLETE")

                if (isCommandComplete) {
                    LOG.info("DOCKER_CMD_COMPLETE: Command completed (marker file found)")
                    break
                }
            } catch (e: Exception) {
                LOG.warn("DOCKER_CHECK_ERROR: Error checking command status: ${e.message}")
            }

            // Wait before polling again
            Thread.sleep(pollIntervalMs)
        }

        if (!isCommandComplete) {
            // If we get here, the process didn't complete within the timeout
            LOG.warn("DOCKER_CMD_TIMEOUT: Command execution timed out after ${maxWaitTimeMs/1000} seconds")
            return "[Command execution timed out after ${maxWaitTimeMs/1000} seconds]"
        }

        // Get command exit code from file
        val cmdExitCodeCommand = listOf(
            dockerPath, "exec",
            containerName,
            "cat", "${outputFileName}.exit"
        )

        val cmdExitCodeProcess = ProcessBuilder(cmdExitCodeCommand).start()
        val cmdExitCode = cmdExitCodeProcess.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: -1
        cmdExitCodeProcess.waitFor()

        LOG.info("DOCKER_CMD_EXIT_CODE: Command inside container exited with code: $cmdExitCode")

        // Read the output file
        val catCommand = listOf(
            dockerPath, "exec",
            containerName,
            "cat", outputFileName
        )

        try {
            val process = ProcessBuilder(catCommand)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            LOG.info("DOCKER_OUTPUT_RAW_SIZE: Got ${output.length} characters of output")

            // Split the output by lines
            val lines = output.split("\n")
            LOG.info("DOCKER_LINES_COUNT: ${lines.size}")

            if (lines.isNotEmpty()) {
                LOG.info("DOCKER_FIRST_LINE: \"${lines.firstOrNull() ?: ""}\"")
                if (lines.size > 1) {
                    LOG.info("DOCKER_SECOND_LINE: \"${lines.getOrNull(1) ?: ""}\"")
                }
                LOG.info("DOCKER_LAST_LINE: \"${lines.lastOrNull() ?: ""}\"")
                if (lines.size > 1) {
                    LOG.info("DOCKER_SECOND_LAST_LINE: \"${lines.getOrElse(lines.size - 2) { "" }}\"")
                }
            }

            // Process output: remove the first line that contains the command echo
            val processedLines = if (lines.isNotEmpty()) {
                // Skip the first line (which is the echoed command)
                lines.drop(1)
            } else {
                lines
            }

            // Clean up temporary files
            val cleanupCommand = listOf(
                dockerPath, "exec",
                containerName,
                "bash", "-c",
                "rm -f $outputFileName ${outputFileName}.exit $markerFileName"
            )

            ProcessBuilder(cleanupCommand).start()
            LOG.info("DOCKER_CLEANUP: Removed temporary output files")

            val finalOutput = processedLines.joinToString("\n")
            LOG.info("DOCKER_OUTPUT_FINAL_SIZE: ${finalOutput.length}")

            return finalOutput
        } catch (e: Exception) {
            LOG.error("DOCKER_ERROR: Error reading command output: ${e.message}", e)
            return "Error capturing command output: ${e.message}"
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
            val command = if (System.getProperty("os.name").lowercase().contains("windows")) {
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
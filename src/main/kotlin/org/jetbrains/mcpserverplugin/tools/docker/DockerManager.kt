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
class DockerManager(private val projectDir: String) {
    
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
    val containerName: String = "mcp-intellij-container-${projectDir.hashCode().absoluteValue}"
    
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

        // Always add no-cache option to ensure fresh builds when Dockerfile changes
        // This prevents using cached layers that might not reflect recent Dockerfile changes
        buildCommandBase.add("--no-cache")
        
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
            
            // Use Runtime.exec to capture both standard and error output
            val process = Runtime.getRuntime().exec(buildCommand.toTypedArray())
            
            val stdoutBuffer = StringBuilder()
            val stderrBuffer = StringBuilder()
            
            // Capture standard output
            val stdoutThread = Thread {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    LOG.info("[Docker build] $line")
                    stdoutBuffer.append(line).append("\n")
                }
            }
            
            // Capture error output
            val stderrThread = Thread {
                val reader = BufferedReader(InputStreamReader(process.errorStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    LOG.error("[Docker build error] $line")
                    stderrBuffer.append(line).append("\n")
                }
            }
            
            stdoutThread.start()
            stderrThread.start()
            
            // Wait for threads to complete
            stdoutThread.join()
            stderrThread.join()
            
            val exitCode = process.waitFor()
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
     * @return The command output or error message
     */
    fun executeCommand(command: String): String {
        val dockerPath = dockerPath ?: return "Docker executable not found"
        
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
            var line: String?
            
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                output.append("\nExit code: ").append(exitCode)
            }
            
            return output.toString()
        } catch (e: Exception) {
            return "Error executing command: ${e.message}"
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
package org.jetbrains.mcpserverplugin.tools.docker

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Paths

private val LOG = Logger.getInstance(DevContainerConfig::class.java)

/**
 * Represents the structure of a devcontainer.json file
 */
@Serializable
data class DevContainerConfig(
    val image: String? = null,
    val build: BuildConfig? = null,
    val platform: String? = null // Added support for platform specification
) {
    @Serializable
    data class BuildConfig(
        val dockerfile: String? = null,
        val context: String? = null
    )

    companion object {
        private val json = Json { 
            ignoreUnknownKeys = true 
            isLenient = true
        }

        /**
         * Reads and parses the devcontainer.json file from the specified project directory
         *
         * @param projectDir The project root directory
         * @return DevContainerConfig object or null if file doesn't exist or can't be parsed
         */
        fun fromProjectDir(projectDir: String): DevContainerConfig? {
            val devcontainerPath = Paths.get(projectDir, ".llm", "devcontainer.json").toString()
            val devcontainerFile = File(devcontainerPath)

            if (!devcontainerFile.exists()) {
                LOG.info("No devcontainer.json file found at: $devcontainerPath")
                return null
            }

            return try {
                // Read file content and remove comments (// style)
                val rawContent = devcontainerFile.readText()
                val contentWithoutComments = removeJsonComments(rawContent)
                
                LOG.info("Original devcontainer.json content: $rawContent")
                LOG.info("Cleaned devcontainer.json content: $contentWithoutComments")
                
                val config = json.decodeFromString<DevContainerConfig>(contentWithoutComments)
                LOG.info("Parsed config - image: ${config.image}, build: ${config.build?.dockerfile}")
                
                config
            } catch (e: Exception) {
                LOG.error("Failed to parse devcontainer.json", e)
                LOG.warn("Failed to parse devcontainer.json: ${e.message}")
                null
            }
        }
        
        /**
         * Removes C-style comments from JSON string
         * Handles both // line comments and /* */ block comments
         */
        private fun removeJsonComments(json: String): String {
            val result = StringBuilder()
            var inString = false
            var inLineComment = false
            var inBlockComment = false
            var i = 0
            
            while (i < json.length) {
                when {
                    // Handle string literals
                    json[i] == '"' && !inLineComment && !inBlockComment -> {
                        // Check if the quote is escaped
                        if (i > 0 && json[i-1] == '\\') {
                            // This is an escaped quote, not a string boundary
                            result.append(json[i])
                        } else {
                            // Toggle string mode
                            inString = !inString
                            result.append(json[i])
                        }
                    }
                    
                    // Detect start of line comment
                    !inString && !inLineComment && !inBlockComment && i < json.length - 1 && 
                            json[i] == '/' && json[i + 1] == '/' -> {
                        inLineComment = true
                        i++ // Skip the next character (the second /)
                    }
                    
                    // Detect end of line comment
                    inLineComment && (json[i] == '\n' || json[i] == '\r') -> {
                        inLineComment = false
                        result.append(json[i]) // Keep the newline
                    }
                    
                    // Detect start of block comment
                    !inString && !inLineComment && !inBlockComment && i < json.length - 1 && 
                            json[i] == '/' && json[i + 1] == '*' -> {
                        inBlockComment = true
                        i++ // Skip the next character (the *)
                    }
                    
                    // Detect end of block comment
                    inBlockComment && i < json.length - 1 && json[i] == '*' && json[i + 1] == '/' -> {
                        inBlockComment = false
                        i++ // Skip the next character (the /)
                    }
                    
                    // Normal character, not in a comment
                    !inLineComment && !inBlockComment -> {
                        result.append(json[i])
                    }
                }
                i++
            }
            
            return result.toString()
        }
    }
}

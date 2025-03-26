package org.jetbrains.mcpserverplugin.utils.filesearch

import com.intellij.openapi.util.getPathMatcher
import java.nio.file.FileSystems
import java.nio.file.Paths

/**
 * Matches a file path against a glob pattern. Supports both file names and full paths.
 * 
 * @param filePath The file path or name to check
 * @param pattern The glob pattern to match against
 * @return true if the file matches the pattern, false otherwise
 */
fun FileSearch.matchesGlobPattern(filePath: String, pattern: String): Boolean {
    // Special case for patterns like "**" which should match everything
    if (pattern == "**") return true
    
    // Special handling for directory patterns
    val hasDirectory = pattern.contains("/")
    
    // Try standard glob pattern matchers first
    try {
        // 1. Try direct matching with IntelliJ's matcher
        try {
            val intellijMatcher = getPathMatcher(pattern)
            val path = Paths.get(filePath)
            if (intellijMatcher.matches(path)) return true
        } catch (e: Exception) {
            // Ignore and try next method
        }
        
        // 2. Try with Java's built-in glob matcher
        try {
            val javaMatcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
            val path = Paths.get(filePath)
            if (javaMatcher.matches(path)) return true
        } catch (e: Exception) {
            // Ignore and try next method
        }
        
        // 3. If we have a pattern with directories, try pattern matching with **
        if (hasDirectory) {
            // Make pattern work with ** for directory matching
            val expandedPattern = if (!pattern.startsWith("**/") && !pattern.startsWith("/")) {
                "**/$pattern"
            } else {
                pattern
            }
            
            try {
                val matcher = FileSystems.getDefault().getPathMatcher("glob:$expandedPattern")
                val path = Paths.get(filePath)
                if (matcher.matches(path)) return true
            } catch (e: Exception) {
                // Ignore and try fallback
            }
        }
        
        // 4. For simple file name patterns, check just the file name
        if (!hasDirectory) {
            val fileName = Paths.get(filePath).fileName.toString()
            try {
                val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
                val path = Paths.get(fileName)
                if (matcher.matches(path)) return true
            } catch (e: Exception) {
                // Ignore and try fallback
            }
        }
    } catch (e: Exception) {
        // Just continue to fallback
    }
    
    // Fall back to our own pattern matching for cases where the standard matchers fail
    
    // Helper function to match simple patterns without directory components
    val matchSimplePattern = { fileName: String, simplePattern: String ->
        when {
            // Handle *.ext pattern
            simplePattern.startsWith("*.") -> {
                val extension = simplePattern.substring(2)
                fileName.endsWith(".$extension")
            }
            // Handle other patterns with *
            simplePattern.contains("*") -> {
                val regex = simplePattern
                    .replace(".", "\\.")
                    .replace("*", ".*")
                fileName.matches(regex.toRegex())
            }
            // Exact match
            else -> fileName == simplePattern
        }
    }
    
    // Handle patterns based on their structure
    return when {
        // If pattern has directory separators
        hasDirectory -> {
            val fileName = Paths.get(filePath).fileName.toString()
            val dirPattern = pattern.substringBeforeLast("/")
            val filePattern = pattern.substringAfterLast("/")
            
            // Special case for patterns like "**/*.kt" or "*/*.kt"
            if (dirPattern == "**" || dirPattern == "*") {
                matchSimplePattern(fileName, filePattern)
            } else {
                // Check both directory and file parts
                filePath.contains("/$dirPattern/") && matchSimplePattern(fileName, filePattern)
            }
        }
        
        // Handle extension patterns like *.{js,ts}
        pattern.contains("{") && pattern.contains("}") -> {
            val prefix = pattern.substringBefore("{")
            val extensions = pattern.substringAfter("{").substringBefore("}").split(",")
            
            extensions.any { ext ->
                val fullPattern = prefix + ext
                matchSimplePattern(Paths.get(filePath).fileName.toString(), fullPattern)
            }
        }
        
        // For simple patterns with no special cases
        else -> {
            val fileName = Paths.get(filePath).fileName.toString()
            matchSimplePattern(fileName, pattern)
        }
    }
}
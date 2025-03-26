package org.jetbrains.mcpserverplugin.utils.filesearch

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves a relative path against a base path.
 * Handles both absolute and relative paths correctly.
 * 
 * @param base The base path to resolve against
 * @param relativePath The relative path to resolve
 * @return The resolved path
 */
fun FileSearch.resolveRel(base: Path, relativePath: String): Path {
    // Normalize path separators
    val path = relativePath.replace("\\", "/")
    
    // Handle current directory
    if (path.isEmpty() || path == "." || path == "./") {
        return base
    }
    
    // Handle absolute paths
    if (path.startsWith("/")) {
        return base.resolve(path.substring(1))
    }
    
    // Handle regular relative paths
    return base.resolve(path)
}
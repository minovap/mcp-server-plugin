package org.jetbrains.mcpserverplugin.utils

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Singleton class that provides file-level locks to prevent concurrent modifications
 * to the same file by different tools (like ReplaceTool and EditBlocksTool).
 */
object FileLocks {
    // Map of file paths to their corresponding locks
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    
    /**
     * Gets or creates a lock for the specified file path.
     * 
     * @param path The path to get a lock for
     * @return The ReentrantLock for the specified path
     */
    private fun getLockForPath(path: Path): ReentrantLock {
        val canonicalPath = path.toAbsolutePath().normalize().toString()
        return locks.computeIfAbsent(canonicalPath) { ReentrantLock() }
    }
    
    /**
     * Executes the given action while holding the lock for the specified file path.
     * 
     * @param path The path to lock during execution
     * @param action The action to execute while holding the lock
     * @return The result of the action
     */
    fun <T> withFileLock(path: Path, action: () -> T): T {
        val lock = getLockForPath(path)
        lock.lock()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }
}

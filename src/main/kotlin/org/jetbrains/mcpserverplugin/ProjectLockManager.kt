package org.jetbrains.mcpserverplugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager

/**
 * Manages locking of a specific project for MCP operations.
 * When locked, all MCP operations will use the locked project instead of getLastFocusedOrOpenedProject.
 */
@Service
class ProjectLockManager : Disposable {
    private val log = logger<ProjectLockManager>()
    
    companion object {
        // Singleton instance
        @JvmStatic
        fun getInstance(): ProjectLockManager = ApplicationManager.getApplication().service()
    }

    // Currently locked project, null if not locked
    private var lockedProject: Project? = null

    // Listeners for lock state changes
    private val listeners = mutableListOf<(Boolean, Project?) -> Unit>()
    
    /**
     * Get the current locked project, or null if not locked.
     */
    fun getLockedProject(): Project? = lockedProject
    
    /**
     * Check if a specific project is currently locked.
     */
    fun isProjectLocked(project: Project): Boolean = lockedProject == project
    
    /**
     * Check if any project is currently locked.
     */
    fun isLocked(): Boolean = lockedProject != null
    
    /**
     * Lock to the specified project or unlock if already locked to this project.
     * If locked to a different project, switch to the new project.
     * Returns the new lock state (true if locked, false if unlocked).
     */
    fun toggleProjectLock(project: Project): Boolean {
        if (lockedProject == project) {
            // Already locked to this project, so unlock
            log.info("Unlocking project: ${project.name}")
            lockedProject = null
            notifyListeners(false, null)
            return false
        } else {
            // Lock to this project
            log.info("Locking to project: ${project.name}")
            lockedProject = project
            notifyListeners(true, project)
            return true
        }
    }
    
    /**
     * Force unlock any locked project.
     */
    fun unlockProject() {
        if (lockedProject != null) {
            log.info("Forcibly unlocking project: ${lockedProject?.name}")
            val oldProject = lockedProject
            lockedProject = null
            notifyListeners(false, oldProject)
        }
    }
    
    /**
     * Get the project to use for MCP operations.
     * If a project is locked and open, returns that project.
     * If the locked project is closed, falls back to getLastFocusedOrOpenedProject.
     * If no project is locked, returns getLastFocusedOrOpenedProject.
     */
    fun getProjectForMCP(): Project? {
        val locked = lockedProject
        
        // If we have a locked project and it's still open, use it
        if (locked != null && !locked.isDisposed && ProjectManager.getInstance().openProjects.contains(locked)) {
            return locked
        }
        
        // If the locked project is closed or we don't have one, fall back to the last focused project
        if (locked != null) {
            // The locked project was closed, so unlock it
            log.info("Locked project ${locked.name} is closed, unlocking")
            unlockProject()
        }
        
        // Fall back to standard behavior
        return getLastFocusedOrOpenedProject()
    }
    
    /**
     * Get the last focused or opened project (standard behavior when not locked).
     */
    private fun getLastFocusedOrOpenedProject(): Project? {
        return IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project 
            ?: ProjectManager.getInstance().openProjects.firstOrNull()
    }

    /**
     * Add a listener for lock state changes.
     * The listener is called with the new lock state and the locked project (or null if unlocked).
     */
    fun addLockListener(listener: (Boolean, Project?) -> Unit) {
        listeners.add(listener)
    }

    /**
     * Remove a listener for lock state changes.
     */
    fun removeLockListener(listener: (Boolean, Project?) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Notify all listeners of the current lock state.
     */
    private fun notifyListeners(locked: Boolean, project: Project?) {
        listeners.forEach { it(locked, project) }
    }
    
    /**
     * Cleanup resources when the service is being disposed.
     */
    override fun dispose() {
        // Nothing to dispose
        lockedProject = null
        listeners.clear()
    }
}
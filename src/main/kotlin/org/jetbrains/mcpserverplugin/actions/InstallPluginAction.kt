package org.jetbrains.mcpserverplugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import java.io.File

/**
 * An action that installs the latest built plugin jar file and restarts the IDE
 */
class InstallPluginAction : AnAction(), DumbAware {
    companion object {
        // Plugin ID as defined in plugin.xml
        private const val PLUGIN_ID = "com.intellij.mcpServer"
        
        // Path to the built plugin jar relative to the project root
        private const val PLUGIN_JAR_PATH = "build/libs/mcp-server-plugin.jar"
        
        // Notification group ID
        private const val NOTIFICATION_GROUP_ID = "MCP Plugin Installation"
    }
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        installPluginAndRestart(project)
    }

    override fun update(e: AnActionEvent) {
        // Enable the action only when we have a project
        e.presentation.isEnabledAndVisible = e.project != null
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
    
    private fun installPluginAndRestart(project: Project) {
        object : Task.Backgroundable(project, "Installing Plugin", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Installing plugin and preparing to restart..."
                
                try {
                    // Get plugin source file
                    val sourceJarFile = File(project.basePath, PLUGIN_JAR_PATH)
                    
                    if (!sourceJarFile.exists()) {
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(
                                project,
                                "Plugin jar file not found at ${sourceJarFile.absolutePath}. " +
                                "Make sure you have built the plugin first.",
                                "Plugin Installation Failed"
                            )
                        }
                        return
                    }
                    
                    // Get the plugins directory from the PathManager
                    val targetDir = File(PathManager.getPluginsPath())
                    
                    // Copy the jar file to the plugins directory
                    val targetFile = File(targetDir, sourceJarFile.name)
                    FileUtil.copy(sourceJarFile, targetFile)
                    
                    // Log successful installation
                    println("Installed ${sourceJarFile.absolutePath} to ${targetFile.absolutePath}")
                    
                    // Show notification and offer restart
                    ApplicationManager.getApplication().invokeLater {
                        ApplicationManagerEx.getApplicationEx().restart(true)
                    }
                    
                } catch (ex: Exception) {
                    // Log the exception for debugging
                    ex.printStackTrace()
                    
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Failed to install plugin: ${ex.message}\n${ex.stackTraceToString()}",
                            "Plugin Installation Failed"
                        )
                    }
                }
            }
        }.queue()
    }
}

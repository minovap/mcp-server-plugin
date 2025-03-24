package org.jetbrains.mcpserverplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import javax.swing.Icon
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ActionUpdateThread
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class ConnectAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        // Execute AppleScript to focus Claude app
        // Read JS file into memory
        val resource = ConnectAction::class.java.classLoader.getResource("js/claude-console.js") ?: throw IllegalStateException("JS file not found")
        val jsContent = resource.readText()
        // JS content already read from resources
        
        // Save current clipboard content and set new content using java.awt.Toolkit

        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val savedClipboard = clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String ?: ""
        
        // Set clipboard to JS content
        val stringSelection = StringSelection(jsContent)
        clipboard.setContents(stringSelection, null)
        
        val osascript = """
if application "Claude" is not running then
	tell application "Claude" to activate
	delay 2
end if

tell application "Claude" to activate
set maxWaitTime to 10 -- Maximum wait time in seconds
set startTime to current date

-- First trigger the developer tools
if application "Claude" is frontmost then
	log "Claude is frontmost, sending keystroke"
	delay 1
	tell application "System Events" to keystroke "i" using {command down, option down, shift down}
	delay 1
end if

-- First loop: Wait for and close "Developer Tools - file" windows
set fileWindowsClosed to false
set fileLoopStartTime to current date

repeat until fileWindowsClosed or ((current date) - fileLoopStartTime) > maxWaitTime
	set fileWindowsClosed to true -- Assume all file windows are closed
	
	tell application "System Events"
		tell process "Claude"
			set allWindows to every window
			repeat with currentWindow in allWindows
				set windowName to name of currentWindow
				log "Checking window: " & windowName
				
				if windowName starts with "Developer Tools - file:" then
					log "Closing file-based developer tools window"
					click (first button of currentWindow whose description is "close button")
					set fileWindowsClosed to false -- Still found windows to close
					exit repeat -- Exit and check again after a short delay
				end if
			end repeat
		end tell
	end tell
	
	if not fileWindowsClosed then
		delay 0.5 -- Wait a bit before checking again
	end if
end repeat


delay 1.5


-- Clipboard is already set from Kotlin

tell application "System Events"
	keystroke "v" using {command down} -- Paste from clipboard
	key code 36 -- Press Return
end tell


delay 1.5

-- Second loop: Wait for and close "Developer Tools - https" windows
set httpWindowsClosed to false
set httpLoopStartTime to current date

repeat until httpWindowsClosed or ((current date) - httpLoopStartTime) > maxWaitTime
	set httpWindowsClosed to true -- Assume all https windows are closed
	
	tell application "System Events"
		tell process "Claude"
			set allWindows to every window
			repeat with currentWindow in allWindows
				set windowName to name of currentWindow
				log "Checking window: " & windowName
				
				if windowName starts with "Developer Tools - https:" then
					log "Closing https-based developer tools window"
					click (first button of currentWindow whose description is "close button")
					set httpWindowsClosed to false -- Still found windows to close
					exit repeat -- Exit and check again after a short delay
				end if
			end repeat
		end tell
	end tell
	
	if not httpWindowsClosed then
		delay 0.5 -- Wait a bit before checking again
	end if
end repeat

log "Script completed"
"""
        val command = osascript.trimIndent().lines().joinToString(" \\\n") {
            "-e '${it.replace("'", "\\'")}'"
        }.let { "osascript \\\n$it" }

        Runtime.getRuntime().exec(arrayOf("/bin/bash", "-c", command))
        
        // Restore original clipboard content
        val restoreClipboardSelection = StringSelection(savedClipboard)
        clipboard.setContents(restoreClipboardSelection, null)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
        e.presentation.text = "Connect"
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}
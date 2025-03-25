package org.jetbrains.mcpserverplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.DataFlavor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConnectAction : AnAction(), DumbAware, ClipboardOwner {
    private val logger = Logger.getInstance(ConnectAction::class.java)
    private val clipboardLatch = CountDownLatch(1)
    private val hasTransferCompleted = AtomicBoolean(false)

    override fun actionPerformed(e: AnActionEvent) {
        try {
            // Read JS file into memory
            val resource = ConnectAction::class.java.classLoader.getResource("js/claude-console.js")
                ?: throw IllegalStateException("JS file not found")
            val jsContent = resource.readText()

            // Save current clipboard content using DataFlavor safely
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            var savedClipboard = ""
            try {
                savedClipboard = clipboard.getData(DataFlavor.stringFlavor) as? String ?: ""
                logger.info("Saved original clipboard content")
            } catch (e: Exception) {
                logger.warn("Could not save original clipboard content", e)
                // Continue with empty saved content if we can't get the current clipboard
            }

            // Set clipboard to JS content and register this class as the clipboard owner
            // to get notified when the content is taken or replaced
            val stringSelection = StringSelection(jsContent)
            ApplicationManager.getApplication().invokeAndWait {
                clipboard.setContents(stringSelection, this)
                logger.info("Set clipboard to JS content")
            }

            // Verify clipboard content was set correctly
            try {
                val clipContent = clipboard.getData(DataFlavor.stringFlavor) as? String
                if (clipContent != jsContent) {
                    logger.warn("Clipboard content verification failed")
                }
            } catch (e: Exception) {
                logger.warn("Could not verify clipboard content", e)
            }

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

            // Execute the command and wait for it to complete
            val process = Runtime.getRuntime().exec(arrayOf("/bin/bash", "-c", command))

            // Wait for a short time to allow the AppleScript to use the clipboard
            // The lostOwnership callback should be triggered when the paste happens
            val pasteTimeout = 5000L // 5 seconds timeout for paste operation
            if (!clipboardLatch.await(pasteTimeout, TimeUnit.MILLISECONDS)) {
                logger.warn("Clipboard paste operation timed out after ${pasteTimeout}ms")
            }

            // Wait a bit more to ensure the AppleScript had time to access clipboard
            if (!hasTransferCompleted.get()) {
                Thread.sleep(500) // Additional safety delay
            }

            // Restore original clipboard content
            ApplicationManager.getApplication().invokeAndWait {
                try {
                    val restoreClipboardSelection = StringSelection(savedClipboard)
                    clipboard.setContents(restoreClipboardSelection, null)
                    logger.info("Restored original clipboard content")
                } catch (e: Exception) {
                    logger.error("Failed to restore clipboard content", e)
                }
            }

            // Wait for the process to complete to avoid leaving zombie processes
            try {
                process.waitFor(10, TimeUnit.SECONDS)
            } catch (e: Exception) {
                logger.warn("AppleScript execution process did not complete normally", e)
            }

        } catch (ex: Exception) {
            logger.error("Error in ConnectAction", ex)
        }
    }

    // This callback is triggered when another application/process takes clipboard ownership
    override fun lostOwnership(clipboard: java.awt.datatransfer.Clipboard, contents: Transferable) {
        hasTransferCompleted.set(true)
        clipboardLatch.countDown()
        logger.info("Clipboard content was used by another application")
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
        e.presentation.text = "Connect"
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}
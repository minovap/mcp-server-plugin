package org.jetbrains.mcpserverplugin.actions
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.psi.util.PsiUtilBase
import org.jetbrains.mcpserverplugin.actions.element.CodeElementFinder
import org.jetbrains.mcpserverplugin.actions.element.ElementInfoBuilder
import org.jetbrains.mcpserverplugin.actions.ui.LLMTodoDialog

/**
 * Action that sends selected code or editor context to Claude
 * This action is available in the editor context menu and sends the code
 * from the editor to Claude via WebSocket and/or copies to clipboard
 */
class SendCodeToClaudeAction(
    private val actionId: String = "org.jetbrains.mcpserverplugin.actions.SendCodeToClaudeAction",
    private val displayName: String = "Send Code to Claude",
    private val preselectedTemplate: String? = null
) : AnAction(displayName) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = PsiUtilBase.getPsiFileInEditor(editor, project) ?: return
        
        // Get the selected code or try to find the current element under cursor
        val selectedElement = CodeElementFinder.findElementAtCursor(editor, file)
        if (selectedElement == null) {
            Messages.showErrorDialog(
                project,
                "No suitable code element found at cursor position.", 
                "Use with LLM"
            )
            return
        }
        
        // Check if there's an actual text selection
        val hasTextSelection = CodeElementFinder.hasTextSelection(editor)
        
        // Show dialog to get user input
        val dialog = LLMTodoDialog(
            project, 
            selectedElement, 
            file, 
            editor, 
            includeCode = hasTextSelection,
            preselectedTemplate = preselectedTemplate
        )
        // Get element info for the todo
        val elementInfo = ElementInfoBuilder.getElementInfo(selectedElement, file)
        
        // If text is selected, use only the selected text as the code context
        // Otherwise, don't include any code
        val surroundingCode = if (hasTextSelection) {
            // Get only the selected text
            CodeElementFinder.getSelectedText(editor) ?: ""
        } else {
            // If no text is selected, don't include code
            ""
        }
        
        // Delegate to common handler
        ClaudePromptProcessor.processContext(project, elementInfo, surroundingCode, dialog)
    }
    
    override fun update(e: AnActionEvent) {
        // Only enable the action if we're in an editor with a file
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        
        e.presentation.isEnabledAndVisible = project != null && editor != null
    }
}
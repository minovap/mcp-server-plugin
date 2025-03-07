package org.jetbrains.mcpserverplugin.actions
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.psi.util.PsiUtilBase
import org.jetbrains.mcpserverplugin.actions.element.CodeElementFinder
import org.jetbrains.mcpserverplugin.actions.element.ElementInfoBuilder
import org.jetbrains.mcpserverplugin.actions.todo.LLMTodoContentCreator
import org.jetbrains.mcpserverplugin.actions.ui.LLMTodoDialog
/**
 * Action that adds selected code to a LLM task and copies it to clipboard
 * This action is available in the editor context menu and copies the todo content
 * directly to the clipboard without creating a scratch file
 */
class AddToLLMTodoAction : AnAction() {
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
        
        // Show dialog to get user input
        val dialog = LLMTodoDialog(project)
        if (dialog.showAndGet()) {
            val userInput = dialog.getUserInput()
            
            // Get element info and surrounding code for the todo
            val elementInfo = ElementInfoBuilder.getElementInfo(selectedElement, file)
            val surroundingCode = ElementInfoBuilder.getSurroundingCode(selectedElement, file, editor, 50)
            
            // Create the todo content
            val todoContent = LLMTodoContentCreator.createTodoContent(elementInfo, surroundingCode, userInput)
            
            // Copy to clipboard
            LLMTodoContentCreator.copyToClipboard(todoContent)
            
            // Show a notification that content has been copied to clipboard
            Messages.showInfoMessage(
                project,
                "LLM task content has been copied to clipboard.",
                "LLM Task Created"
            )
        }
    }
    
    override fun update(e: AnActionEvent) {
        // Only enable the action if we're in an editor with a file
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        
        e.presentation.isEnabledAndVisible = project != null && editor != null
    }
}
package org.jetbrains.mcpserverplugin.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import kotlinx.serialization.Serializable
import org.jetbrains.annotations.Nullable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.util.concurrent.CompletableFuture
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Arguments for the AskUserTool
 */
@Serializable
data class AskUserToolArgs(
    val question: String
)

/**
 * A tool that allows the LLM to ask the user a question through a dialog box
 * and get the answer back.
 */
class AskUserTool : AbstractMcpTool<AskUserToolArgs>() {
    override val name: String = "ask_user"
    override val description: String = """
        Displays a dialog box that asks the user a question and returns their answer.
        
        Parameters:
        - question: The question to ask the user
        
        Returns the user's answer as a string, or an error if the user cancels the dialog.
    """.trimIndent()

    override fun handle(project: Project, args: AskUserToolArgs): Response {
        // Create a CompletableFuture to handle the async result
        val resultFuture = CompletableFuture<String>()
        
        // Run the dialog on the UI thread
        ApplicationManager.getApplication().invokeLater {
            val dialog = AskUserDialog(project, args.question, resultFuture)
            dialog.show()
        }
        
        try {
            // Wait for the result
            val result = resultFuture.get()
            return Response(result)
        } catch (e: Exception) {
            return Response(error = "User cancelled the dialog or an error occurred: ${e.message}")
        }
    }
    
    /**
     * Dialog that asks the user a question and captures their answer
     */
    private class AskUserDialog(
        project: Project,
        private val question: String,
        private val resultFuture: CompletableFuture<String>
    ) : DialogWrapper(project) {
        private val textArea: JBTextArea
        
        init {
            // Fixed title - not customizable by LLM
            this.title = "Question from Claude"
            
            // Create a multi-line text area
            textArea = JBTextArea(6, 50)
            textArea.lineWrap = true
            textArea.wrapStyleWord = true
            // Use standard font without specific references
            textArea.font = javax.swing.UIManager.getFont("TextField.font")
            
            // Configure key bindings for the text area
            // Make Shift+Enter insert a newline
            textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-break")
            
            // Make Enter submit the dialog
            textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "enter-action")
            textArea.actionMap.put("enter-action", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    doOKAction()
                }
            })
            
            init()
        }
        
        @Nullable
        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout(10, 10))
            panel.border = JBUI.Borders.empty(10)
            
            // Create question label with wrapping to prevent excessively wide dialogs
            // Using HTML with a fixed width to force text wrapping
            val wrappedQuestion = "<html><div style='width: 450px;'>$question</div></html>"
            val questionLabel = JBLabel(wrappedQuestion)
            questionLabel.border = JBUI.Borders.empty(0, 0, 8, 0) // Add bottom padding
            
            // Add scrollbars to the textarea
            val scrollPane = JBScrollPane(textArea)
            scrollPane.border = IdeBorderFactory.createBorder()
            
            // Use a form layout with labeled sections
            val formBuilder = FormBuilder.createFormBuilder()
                .addComponent(questionLabel)
                .addComponent(scrollPane)
            
            val form = formBuilder.panel
            panel.add(form, BorderLayout.CENTER)
            
            // Set minimum size for the dialog
            panel.preferredSize = Dimension(500, 250)
            
            return panel
        }
        
        override fun doOKAction() {
            // Get the user's answer and complete the future with it
            resultFuture.complete(textArea.text)
            super.doOKAction()
        }
        
        override fun doCancelAction() {
            // Complete the future with an exception to indicate cancellation
            resultFuture.completeExceptionally(Exception("User cancelled the dialog"))
            super.doCancelAction()
        }
        
        override fun getPreferredFocusedComponent(): JComponent? {
            return textArea
        }
    }
}
package org.jetbrains.mcpserverplugin.actions.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.Nullable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Dialog to get user input for the LLM Todo
 */
class LLMTodoDialog(project: Project) : DialogWrapper(project) {
    private val textArea = JBTextArea(8, 50)
    
    init {
        title = "Use with LLM"
        init()
        
        // Configure the input map to handle Shift+Enter for newlines
        textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-break")
        // Make Enter submit the dialog
        textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "enter-action")
        textArea.actionMap.put("enter-action", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                doOKAction()
            }
        })
    }
    
    fun getUserInput(): String {
        return textArea.text
    }
    
    @Nullable
    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 10))
        panel.border = JBUI.Borders.empty(10)
        
        val label = JBLabel("What would you like to do with this code?")
        panel.add(label, BorderLayout.NORTH)
        
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        
        // Using system defaults for text color instead of hard-coded colors
        textArea.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(textArea.foreground.darker(), 1),
            BorderFactory.createEmptyBorder(4, 4, 4, 4)
        )
        
        // Add scrollbars
        val scrollPane = JBScrollPane(textArea)
        scrollPane.preferredSize = Dimension(500, 200)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        // Update the note about keyboard shortcuts
        val noteLabel = JBLabel("Press Enter to submit, Shift+Enter for newlines")
        noteLabel.border = JBUI.Borders.emptyTop(5)
        panel.add(noteLabel, BorderLayout.SOUTH)
        
        return panel
    }
    
    override fun getPreferredFocusedComponent(): JComponent? {
        return textArea
    }
    
    override fun doValidate(): ValidationInfo? {
        return if (textArea.text.isBlank()) {
            ValidationInfo("Please enter a task description", textArea)
        } else {
            null
        }
    }
}

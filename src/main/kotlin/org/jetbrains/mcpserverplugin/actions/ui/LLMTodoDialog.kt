package org.jetbrains.mcpserverplugin.actions.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.highlighter.HighlighterFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorSettings
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.Nullable
import org.jetbrains.mcpserverplugin.actions.todo.LLMTodoContentCreator
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.ItemEvent
import java.awt.event.ItemListener
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Dialog to get user input for the LLM Todo
 */
class LLMTodoDialog(
    private val project: Project,
    private val selectedElement: com.intellij.psi.PsiElement? = null,
    private val file: com.intellij.psi.PsiFile? = null,
    private val editor: com.intellij.openapi.editor.Editor? = null,
    private val selectedFiles: Array<com.intellij.openapi.vfs.VirtualFile>? = null,
    private val includeCode: Boolean = false,
    private val preselectedTemplate: String? = null
) : DialogWrapper(project) {
    private val textArea = JBTextArea(8, 50)
    
    // Template selection dropdown
    private val templateComboBox = ComboBox<String>()
    private val availableTemplates: Map<String, String>
    private var selectedTemplateName: String? = null
    
    // Sample element info and code for preview
    private val sampleElementInfo = "File: ./example/path/MyFile.kt\nClass: ExampleClass\nFunction: exampleFunction\nLine: 42"
    private val sampleCode = "// This is a sample code preview\nfun exampleFunction() {\n    val greeting = \"Hello, World!\"\n    println(greeting)\n    // Additional code would appear here\n    return greeting\n}"
    
    // Editor for preview
    private lateinit var previewEditor: EditorEx
    
    // Initialize context-related fields
    private var actualElementInfo: String? = null
    private var actualFilePath: String? = null
    
    // File content information
    private var selectedFileContents: List<org.jetbrains.mcpserverplugin.actions.SendFilesToClaudeAction.FileContent>? = null
    
    init {
        title = "Use with LLM"
        
        // Initialize available templates
        availableTemplates = LLMTodoContentCreator.listAvailableTemplates(project)
        
        // Prepare actual content if available
        if (selectedElement != null && file != null) {
            actualElementInfo = org.jetbrains.mcpserverplugin.actions.element.ElementInfoBuilder.getElementInfo(selectedElement, file)
            actualFilePath = file.virtualFile?.path
        } else if (selectedFiles != null && selectedFiles.isNotEmpty()) {
            // Process selected files
            selectedFileContents = buildFileContentsPreview(selectedFiles)
            
            // Get paths for title
            if (selectedFiles.size == 1) {
                actualFilePath = selectedFiles[0].path
            }
        }
        
        // Create the preview editor with markdown highlighting
        initPreviewEditor()
        
        // Setup template combobox
        initTemplateComboBox(preselectedTemplate)
        
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
        
        // Add listener to text area to update preview
        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { updatePreview() }
            override fun removeUpdate(e: DocumentEvent) { updatePreview() }
            override fun changedUpdate(e: DocumentEvent) { updatePreview() }
        })
        
        // Initial preview update
        updatePreview()
    }
    
    /**
     * Initialize the template selection combobox
     * @param preselectedTemplate Optional template name to preselect
     */
    private fun initTemplateComboBox(preselectedTemplate: String? = null) {
        // Add available templates to the combobox
        val model = DefaultComboBoxModel<String>()
        
        // Always include default template first
        model.addElement("Default")
        selectedTemplateName = "default"
        
        // Add other templates sorted alphabetically
        availableTemplates.keys
            .filter { it != "Default" }
            .sorted()
            .forEach { model.addElement(it) }
            
        templateComboBox.model = model
        
        // Preselect template if specified
        if (preselectedTemplate != null) {
            val templateDisplayName = availableTemplates.entries.find { it.value == preselectedTemplate }?.key
            if (templateDisplayName != null) {
                templateComboBox.selectedItem = templateDisplayName
                selectedTemplateName = preselectedTemplate
            }
        }
        
        // Add listener to update preview when template changes
        templateComboBox.addItemListener { event ->
            if (event.stateChange == ItemEvent.SELECTED) {
                val selectedTemplate = event.item as String
                selectedTemplateName = availableTemplates[selectedTemplate]
                updatePreview()
            }
        }
    }
    
    /**
     * Initialize the preview editor component
     */
    private fun initPreviewEditor() {
        // Create document and editor
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument("")
        previewEditor = editorFactory.createEditor(document, project, FileTypeManager.getInstance().getFileTypeByExtension("md"), true) as EditorEx
        
        // Configure editor settings
        val editorSettings: EditorSettings = previewEditor.settings
        editorSettings.isVirtualSpace = false
        editorSettings.isLineMarkerAreaShown = false
        editorSettings.isIndentGuidesShown = false
        editorSettings.isLineNumbersShown = true
        editorSettings.isFoldingOutlineShown = true
        editorSettings.additionalColumnsCount = 3
        editorSettings.additionalLinesCount = 3
        editorSettings.isCaretRowShown = false
        
        previewEditor.isViewer = true // Read-only
    }
    
    /**
     * Updates the preview panel with the current content that would be sent to the LLM
     */
    private fun updatePreview() {
        val userInput = textArea.text.trim()
        
        // Determine what content to show based on the available inputs
        if (selectedElement != null && file != null) {
            // Case 1: Single element is selected - like in AddToLLMTodoAction
            updatePreviewForSelectedElement(userInput)
        } else if (selectedFileContents != null) {
            // Case 2: Files are selected - like in SendFilesToClaudeAction
            updatePreviewForSelectedFiles(userInput)
        } else {
            // Case 3: No selection - use example content
            updatePreviewWithSampleContent(userInput)
        }
    }
    
    /**
     * Updates preview for a selected code element
     */
    private fun updatePreviewForSelectedElement(userInput: String) {
        // Use actual element info
        val elementInfo = actualElementInfo ?: return
        
        // Get the appropriate code based on whether we should include code
        val codeToUse = if (includeCode && editor != null) {
            // If we should include code, get the selected text
            org.jetbrains.mcpserverplugin.actions.element.CodeElementFinder.getSelectedText(editor) ?: ""
        } else {
            // Otherwise, don't include code
            ""
        }
        
        // Create the actual preview content
        val previewContent = LLMTodoContentCreator.createTodoContent(
            elementInfo = elementInfo,
            surroundingCode = codeToUse,
            userInput = if (userInput.isBlank()) "[Your task description]" else userInput,
            project = project,
            templateName = selectedTemplateName
        )
        
        // Update the document text
        updatePreviewText(previewContent)
        
        // Update window title to show filename
        updateTitleWithFilename()
    }
    
    /**
     * Updates preview for selected files
     */
    private fun updatePreviewForSelectedFiles(userInput: String) {
        // Get file contents based on context
        val fileContents = selectedFileContents ?: return
        
        // Build element info and file content
        val elementInfo = buildElementInfoFromFiles(fileContents)
        val surroundingCode = if (includeCode) {
            buildSurroundingCodeFromFiles(fileContents)
        } else {
            ""
        }
        
        // Create the actual preview content
        val previewContent = LLMTodoContentCreator.createTodoContent(
            elementInfo = elementInfo,
            surroundingCode = surroundingCode,
            userInput = if (userInput.isBlank()) "[Your task description]" else userInput,
            project = project,
            templateName = selectedTemplateName
        )
        
        // Update the document text
        updatePreviewText(previewContent)
        
        // Update window title
        updateTitleWithFilename()
    }
    
    /**
     * Updates preview with sample content when no real content is available
     */
    private fun updatePreviewWithSampleContent(userInput: String) {
        // Use the sample info and code
        val codeToUse = if (includeCode) {
            sampleCode
        } else {
            ""
        }
        
        // Create the preview content
        val previewContent = LLMTodoContentCreator.createTodoContent(
            elementInfo = sampleElementInfo,
            surroundingCode = codeToUse,
            userInput = if (userInput.isBlank()) "[Your task description]" else userInput,
            project = project,
            templateName = selectedTemplateName
        )
        
        // Update the document text
        updatePreviewText(previewContent)
        
        // Set generic title
        setTitle("Use with LLM")
    }
    
    /**
     * Updates the preview text in the editor
     */
    private fun updatePreviewText(content: String) {
        runWriteAction { 
            previewEditor.document.setText(content)
        }
    }
    
    /**
     * Updates the dialog title with the current filename if available
     */
    private fun updateTitleWithFilename() {
        val fileDesc = if (actualFilePath != null) {
            val filename = actualFilePath!!.substringAfterLast('/')
            "Using $filename"
        } else if (selectedFiles?.size == 1) {
            val filename = selectedFiles[0].name
            "Using $filename"
        } else if (selectedFiles != null && selectedFiles.size > 1) {
            "Using ${selectedFiles.size} files"
        } else {
            "Use with LLM"
        }
        setTitle(fileDesc)
    }
    
    /**
     * Builds element info from selected files
     */
    private fun buildElementInfoFromFiles(fileContents: List<org.jetbrains.mcpserverplugin.actions.SendFilesToClaudeAction.FileContent>): String {
        return fileContents.joinToString("\n") { 
            when {
                it.isDirectory -> "Directory: ${it.path}"
                it.isTooLarge -> "Large File: ${it.path}"
                else -> "File: ${it.path}"
            }
        }
    }
    
    /**
     * Builds surrounding code content from selected files
     */
    private fun buildSurroundingCodeFromFiles(
        fileContents: List<org.jetbrains.mcpserverplugin.actions.SendFilesToClaudeAction.FileContent>
    ): String {
        // Get all files that aren't directories or too large
        val validFiles = fileContents.filter { !it.isDirectory && !it.isTooLarge }
        
        // Process each file - limit to 100 lines per file
        return validFiles.joinToString("\n\n") { fileContent ->
            // Limit to 100 lines per file
            val content = fileContent.content.lines().take(100).joinToString("\n")
            "# File: ${fileContent.path}\n$content"
        }
    }
    
    /**
     * Runs the given action as a write action
     */
    private fun runWriteAction(action: () -> Unit) {
        com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
            action()
        }
    }
    
    fun getUserInput(): String {
        return textArea.text
    }
    
    fun getSelectedTemplateName(): String? {
        return selectedTemplateName
    }
    
    @Nullable
    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 10))
        panel.border = JBUI.Borders.empty(10)
        
        // Top section with label
        val topPanel = JPanel(BorderLayout(10, 0))
        val label = JBLabel("What would you like to do with this code?")
        topPanel.add(label, BorderLayout.WEST)
        
        // Template selection dropdown with label
        val templatePanel = JPanel(BorderLayout(5, 0))
        val templateLabel = JBLabel("Template:")
        templatePanel.add(templateLabel, BorderLayout.WEST)
        templatePanel.add(templateComboBox, BorderLayout.CENTER)
        
        topPanel.add(templatePanel, BorderLayout.EAST)
        panel.add(topPanel, BorderLayout.NORTH)
        
        // Create a splitter for the input and preview
        val splitter = JBSplitter(true, 0.3f, 0.2f, 0.8f)
        splitter.splitterProportionKey = "llm.todo.dialog.splitter.proportion"
        
        // Input panel
        val inputPanel = JPanel(BorderLayout())
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(textArea.foreground.darker(), 1),
            BorderFactory.createEmptyBorder(4, 4, 4, 4)
        )
        
        // Add scrollbars to text area
        val textScrollPane = JBScrollPane(textArea)
        textScrollPane.preferredSize = Dimension(500, 100)
        inputPanel.add(textScrollPane, BorderLayout.CENTER)
        
        // Preview panel
        val previewPanel = JPanel(BorderLayout())
        previewPanel.border = JBUI.Borders.empty(10, 0, 0, 0)
        
        // Add preview label
        val previewLabel = JBLabel("Preview of text that will be sent to LLM:")
        previewPanel.add(previewLabel, BorderLayout.NORTH)
        
        // Add editor component
        val editorComponent = previewEditor.component
        editorComponent.preferredSize = Dimension(500, 300)
        previewPanel.add(editorComponent, BorderLayout.CENTER)
        
        // Add panels to splitter
        splitter.firstComponent = inputPanel
        splitter.secondComponent = previewPanel
        
        panel.add(splitter, BorderLayout.CENTER)
        
        // Options panel with just keyboard shortcut note
        val optionsPanel = JPanel(BorderLayout())
        optionsPanel.border = JBUI.Borders.emptyTop(10)
        
        // Note about keyboard shortcuts
        val noteLabel = JBLabel("Press Enter to submit, Shift+Enter for newlines", AllIcons.General.Note, JBLabel.LEFT)
        noteLabel.border = JBUI.Borders.emptyTop(5)
        optionsPanel.add(noteLabel, BorderLayout.SOUTH)
        
        panel.add(optionsPanel, BorderLayout.SOUTH)
        
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
    
    /**
     * Set empty border for content pane
     */
    override fun createContentPaneBorder(): javax.swing.border.Border? {
        return JBUI.Borders.empty()
    }
    
    /**
     * Set preferred dialog size
     */
    override fun getPreferredSize(): Dimension {
        // First try to get the saved size from settings
        val settings = LLMTodoDialogSettings.getInstance()
        return settings.getDialogSize()
    }
    
    /**
     * Save dialog size when closed and clean up resources
     */
    override fun dispose() {
        // Save the current size to settings
        val currentSize = getSize()
        if (currentSize != null && currentSize.width > 0 && currentSize.height > 0) {
            val settings = LLMTodoDialogSettings.getInstance()
            settings.saveDialogSize(currentSize)
        }
        
        super.dispose()
        EditorFactory.getInstance().releaseEditor(previewEditor)
    }
    
    /**
     * Provide dimension service key to persist dialog size
     */
    override fun getDimensionServiceKey(): String {
        return "org.jetbrains.mcpserverplugin.actions.ui.LLMTodoDialog"
    }
    
    /**
     * Builds file contents for preview
     */
    private fun buildFileContentsPreview(files: Array<com.intellij.openapi.vfs.VirtualFile>): List<org.jetbrains.mcpserverplugin.actions.SendFilesToClaudeAction.FileContent> {
        val result = mutableListOf<org.jetbrains.mcpserverplugin.actions.SendFilesToClaudeAction.FileContent>()
        val projectRootPath = project.basePath
        
        for (file in files) {
            val absolutePath = file.path
            val relativePath = if (projectRootPath != null && absolutePath.startsWith(projectRootPath)) {
                absolutePath.substring(projectRootPath.length).removePrefix("/")
            } else {
                // If not in project or no project root, use absolute path
                absolutePath
            }
            
            if (file.isDirectory) {
                // For directories, we include a summary but not their content
                result.add(org.jetbrains.mcpserverplugin.actions.SendFilesToClaudeAction.FileContent(relativePath, "Directory", isDirectory = true))
            } else {
                try {
                    // For regular files, include their content if they're not too large
                    if (file.length > MAX_FILE_SIZE) {
                        result.add(org.jetbrains.mcpserverplugin.actions.SendFilesToClaudeAction.FileContent(relativePath, "File too large to include", isTooLarge = true))
                    } else {
                        val content = String(file.contentsToByteArray())
                        // Preview with first few lines
                        val previewContent = content.lines().take(10).joinToString("\n")
                        result.add(org.jetbrains.mcpserverplugin.actions.SendFilesToClaudeAction.FileContent(relativePath, previewContent))
                    }
                } catch (e: Exception) {
                    result.add(org.jetbrains.mcpserverplugin.actions.SendFilesToClaudeAction.FileContent(relativePath, "Could not read file: ${e.message}"))
                }
            }
        }
        
        return result
    }
    
    // Define max file size constant
    companion object {
        private const val MAX_FILE_SIZE = 1024 * 1024 // 1MB max file size
    }
}
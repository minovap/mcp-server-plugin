package org.jetbrains.mcpserverplugin.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import org.jetbrains.mcpserverplugin.McpToolManager
import javax.swing.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.ItemEvent
import org.jetbrains.mcpserverplugin.tools.docker.DockerDefaults

class MCPConfigurable : Configurable {
    private val LOG = Logger.getInstance(MCPConfigurable::class.java)

    private var panel: JPanel? = null
    private var useDefaultDockerImageCheckbox: JCheckBox? = null
    private var showNodeCheckbox: JCheckBox? = null
    private var showClaudeCheckbox: JCheckBox? = null
    private var showClaudeSettingsCheckbox: JCheckBox? = null
    private var dockerImageField: JBTextField? = null

    // Store tool checkboxes by name
    private val toolCheckboxes = mutableMapOf<String, JCheckBox>()

    override fun getDisplayName() = "MCP Server"

    override fun createComponent(): JComponent {
        LOG.info("Creating MCP Server configurable component")
        
        // Initialize notification settings
        showNodeCheckbox = JCheckBox("Show Node Notification")
        showClaudeCheckbox = JCheckBox("Show Claude Notification")
        showClaudeSettingsCheckbox = JCheckBox("Show Claude Settings Notification")

        // Create Docker image field
        dockerImageField = JBTextField(30)

        // Create Reset button for Docker image
        val resetDockerButton = JButton("Reset to Default")
        resetDockerButton.addActionListener {
            dockerImageField?.text = DEFAULT_DOCKER_IMAGE
        }
        
        // Create Use Default Docker Image checkbox
        useDefaultDockerImageCheckbox = JCheckBox("Use default Docker image")
        useDefaultDockerImageCheckbox?.addItemListener { e ->
            val useDefault = e.stateChange == ItemEvent.SELECTED
            dockerImageField?.isEnabled = !useDefault
            resetDockerButton.isEnabled = !useDefault
            
            if (useDefault) {
                // When checked, display but disable the default image
                dockerImageField?.text = DockerDefaults.DEFAULT_IMAGE
            }
        }

        // Create a Docker image panel with a titled border
        val dockerPanel = JPanel(BorderLayout())
        
        // Add checkbox at the top
        val checkboxPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        checkboxPanel.add(useDefaultDockerImageCheckbox)
        dockerPanel.add(checkboxPanel, BorderLayout.NORTH)

        // Create a horizontal layout for the docker image field and reset button
        val dockerFieldPanel = JPanel()
        dockerFieldPanel.layout = BoxLayout(dockerFieldPanel, BoxLayout.X_AXIS)
        val dockerLabel = JLabel("Docker Image:")
        dockerFieldPanel.add(dockerLabel)
        dockerFieldPanel.add(Box.createHorizontalStrut(5))
        dockerFieldPanel.add(dockerImageField)
        dockerFieldPanel.add(Box.createHorizontalStrut(10))
                dockerFieldPanel.add(resetDockerButton)
                dockerFieldPanel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)

        dockerPanel.add(dockerFieldPanel, BorderLayout.CENTER)
        
        // Add a note label about devcontainer.json
        val notePanel = JPanel(BorderLayout())
        val devcontainerNote = JLabel(
            "<html><em>Note: If a project contains a valid <code>.llm/devcontainer.json</code> file, " +
            "it will take precedence over these settings.</em></html>"
        )
        devcontainerNote.font = devcontainerNote.font.deriveFont(devcontainerNote.font.size2D - 1.0f)
        devcontainerNote.foreground = Color(100, 100, 100)
        devcontainerNote.border = BorderFactory.createEmptyBorder(0, 4, 8, 0)
        notePanel.add(devcontainerNote, BorderLayout.WEST)
        dockerPanel.add(notePanel, BorderLayout.SOUTH)
        dockerPanel.border = BorderFactory.createTitledBorder("Docker Image")
        
        // Add note about LLM prompt template
        val promptNotePanel = JPanel(BorderLayout())
        val noteLabel = JLabel("<html>The LLM prompt template is now configured via the <code>.llm/prompt-context.md</code> file in your project root.<br>" +
                "This file contains the template with placeholders:<br>" +
                "<b>{{TASK}}</b> - The task description entered by the user<br>" +
                "<b>{{CONTEXT}}</b> - The file path and code element information<br>" +
                "<b>{{CODE}}</b> - The code surrounding the selected element</html>")
        noteLabel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        promptNotePanel.add(noteLabel, BorderLayout.CENTER)
        promptNotePanel.border = BorderFactory.createTitledBorder("LLM Prompt Template")

        try {
            // Fetch all tools
            val tools = McpToolManager.getAllTools()

            // Create a panel for all tools without tabs
            val toolsPanel = JPanel(GridLayout(0, 1))

            // Sort tools by name for better readability
            val sortedTools = tools.sortedBy { it.name }

            // Add checkbox for each tool
            for (tool in sortedTools) {
                val toolName = tool.name

                // Create a panel for each tool with checkbox and info button
                val toolPanel = JPanel(FlowLayout(FlowLayout.LEFT))

                // Convert toolName from underscore_case to Proper case with spaces
                val formattedToolName = toolName.split("_")
                    .joinToString(" ") { word -> 
                        if (word.isNotEmpty()) {
                            word.first().uppercase() + word.substring(1).lowercase()
                        } else {
                            ""
                        }
                    }

                // Create checkbox with the formatted name
                val checkbox = JCheckBox(formattedToolName)
                toolCheckboxes[toolName] = checkbox
                toolPanel.add(checkbox)

                // Create an info icon with tooltip and click listener
                val infoLabel = JLabel(AllIcons.General.ContextHelp)
                infoLabel.toolTipText = tool.description

                // Add mouse listener for click popup
                infoLabel.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        JOptionPane.showMessageDialog(
                            panel,
                            tool.description,
                            "Tool Information",
                        JOptionPane.INFORMATION_MESSAGE
                        )
                    }
                })

                toolPanel.add(infoLabel)
                toolsPanel.add(toolPanel)
            }

            // Add a scrollpane for all tools
            val scrollPane = JBScrollPane(toolsPanel)
            scrollPane.border = BorderFactory.createEmptyBorder()

            // If there are no tools, show a message
            if (tools.isEmpty()) {
                val noToolsPanel = JPanel(BorderLayout())
                noToolsPanel.add(JLabel("No tools found"), BorderLayout.CENTER)
                        scrollPane.setViewportView(noToolsPanel)
            }

            // Add buttons for selecting/deselecting all tools
            val selectAllButton = JButton("Select All")
            selectAllButton.addActionListener {
                toolCheckboxes.values.forEach { it.isSelected = true }
            }

            val deselectAllButton = JButton("Deselect All")
            deselectAllButton.addActionListener {
                toolCheckboxes.values.forEach { it.isSelected = false }
            }

            // Add buttons to a panel
            val buttonPanel = JPanel()
            buttonPanel.layout = BoxLayout(buttonPanel, BoxLayout.X_AXIS)
            buttonPanel.add(selectAllButton)
            buttonPanel.add(Box.createHorizontalStrut(10))
            buttonPanel.add(deselectAllButton)
            buttonPanel.add(Box.createHorizontalGlue())
            buttonPanel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)

            // Create tools container with titled border
            val toolsContainer = JPanel(BorderLayout())
            toolsContainer.add(buttonPanel, BorderLayout.NORTH)
            toolsContainer.add(scrollPane, BorderLayout.CENTER)
            toolsContainer.border = BorderFactory.createTitledBorder("Active MCP Tools")

            // Create main panel with notification checkboxes
            panel = JPanel(BorderLayout())
            
            // Add notification settings to the top
            val notificationPanel = JPanel(GridLayout(3, 1))
            notificationPanel.add(showNodeCheckbox)
            notificationPanel.add(showClaudeCheckbox)
            notificationPanel.add(showClaudeSettingsCheckbox)
            notificationPanel.border = BorderFactory.createTitledBorder("Notification Settings")
            
            // Add all panels to main
            val mainContent = JPanel()
            mainContent.layout = BoxLayout(mainContent, BoxLayout.Y_AXIS)
            mainContent.add(notificationPanel)
            mainContent.add(dockerPanel)
            mainContent.add(promptNotePanel)
            
            panel!!.add(mainContent, BorderLayout.NORTH)
            panel!!.add(toolsContainer, BorderLayout.CENTER)
            panel!!.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        } catch (e: Exception) {
            LOG.error("Error loading MCP tools: ${e.message}", e)
            panel = JPanel(BorderLayout())
            panel!!.add(dockerPanel, BorderLayout.NORTH)
            panel!!.add(JLabel("Error loading tools: ${e.message}"), BorderLayout.CENTER)
        }

        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = service<PluginSettings>()
        LOG.info("Checking if settings are modified")

        // Check if notification settings are modified
        if (showNodeCheckbox?.isSelected != settings.shouldShowNodeNotification ||
            showClaudeCheckbox?.isSelected != settings.shouldShowClaudeNotification ||
            showClaudeSettingsCheckbox?.isSelected != settings.shouldShowClaudeSettingsNotification ||
            useDefaultDockerImageCheckbox?.isSelected != settings.useDefaultDockerImage) {
            LOG.info("Basic settings are modified")
            return true
        }
        
        if (!settings.useDefaultDockerImage && dockerImageField?.text != settings.dockerImage) {
            LOG.info("Basic settings are modified")
            return true
        }

        // Check if any tool enablement is modified
        for ((toolName, checkbox) in toolCheckboxes) {
            if (checkbox.isSelected != settings.isToolEnabled(toolName)) {
                LOG.info("Tool enablement for $toolName is modified")
                return true
            }
        }

        return false
    }

    override fun apply() {
        val settings = service<PluginSettings>()
        LOG.info("Applying settings changes")

        // Apply notification settings
        settings.shouldShowNodeNotification = showNodeCheckbox?.isSelected ?: true
        settings.shouldShowClaudeNotification = showClaudeCheckbox?.isSelected ?: true
        settings.shouldShowClaudeSettingsNotification = showClaudeSettingsCheckbox?.isSelected ?: true
        settings.useDefaultDockerImage = useDefaultDockerImageCheckbox?.isSelected ?: true
        
        // Only save custom docker image if not using default
        if (!settings.useDefaultDockerImage) {
            settings.dockerImage = dockerImageField?.text ?: DEFAULT_DOCKER_IMAGE
        }

        // Apply tool enablement settings
        for ((toolName, checkbox) in toolCheckboxes) {
            settings.setToolEnabled(toolName, checkbox.isSelected)
        }
        
        // Log the current state after applying changes
        settings.logState()
    }

    override fun reset() {
        val settings = service<PluginSettings>()
        settings.logState()
        LOG.info("Resetting UI to match saved settings")

        // Reset notification settings
        showNodeCheckbox?.isSelected = settings.shouldShowNodeNotification
        showClaudeCheckbox?.isSelected = settings.shouldShowClaudeNotification
        showClaudeSettingsCheckbox?.isSelected = settings.shouldShowClaudeSettingsNotification
        useDefaultDockerImageCheckbox?.isSelected = settings.useDefaultDockerImage
        
        // Set the Docker image field
        dockerImageField?.text = settings.dockerImage
        
        // Update field enabled state
        dockerImageField?.isEnabled = !settings.useDefaultDockerImage
        panel?.findComponentByName("Reset to Default")?.isEnabled = !settings.useDefaultDockerImage

        // Reset tool enablement settings
        for ((toolName, checkbox) in toolCheckboxes) {
            checkbox.isSelected = settings.isToolEnabled(toolName)
        }
    }

    companion object {
        const val DEFAULT_DOCKER_IMAGE = "gitpod/workspace-full"
    }
}

// Helper extension to find a component by name
private fun Container.findComponentByName(name: String): Component? {
    var result: Component? = null
    for (component in this.components) {
        if (component is JButton && component.text == name) result = component
        if (component is Container && result == null) result = component.findComponentByName(name)
    }
    return result
}

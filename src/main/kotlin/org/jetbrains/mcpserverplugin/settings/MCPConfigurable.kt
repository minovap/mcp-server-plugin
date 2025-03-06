package org.jetbrains.mcpserverplugin.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import javax.swing.*
import javax.swing.border.TitledBorder
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import org.jetbrains.mcpserverplugin.McpToolManager

class MCPConfigurable : Configurable {
    private val LOG = Logger.getInstance(MCPConfigurable::class.java)

    private var panel: JPanel? = null
    private var showNodeCheckbox: JCheckBox? = null
    private var showClaudeCheckbox: JCheckBox? = null
    private var showClaudeSettingsCheckbox: JCheckBox? = null
    private var dockerImageField: JBTextField? = null

    // Store tool checkboxes by name
    private val toolCheckboxes = mutableMapOf<String, JCheckBox>()

    override fun getDisplayName() = "MCP Server"

    override fun createComponent(): JComponent {
        // Initialize notification settings (hidden from UI)
        showNodeCheckbox = JCheckBox("Show Node Notification")
        showClaudeCheckbox = JCheckBox("Show Claude Notification")
        showClaudeSettingsCheckbox = JCheckBox("Show Claude Settings Notification")

        // Create Docker image field
        dockerImageField = JBTextField(30)

        // Create Reset button
        val resetButton = JButton("Reset to Default")
        resetButton.addActionListener {
            dockerImageField?.text = DEFAULT_DOCKER_IMAGE
        }

        // Create a Docker image panel with a titled border
        val dockerPanel = JPanel(BorderLayout())

        // Create a horizontal layout for the docker image field and reset button
        val dockerFieldPanel = JPanel()
        dockerFieldPanel.layout = BoxLayout(dockerFieldPanel, BoxLayout.X_AXIS)
        dockerFieldPanel.add(JLabel("Docker Image:"))
                dockerFieldPanel.add(Box.createHorizontalStrut(5))
                dockerFieldPanel.add(dockerImageField)
                dockerFieldPanel.add(Box.createHorizontalStrut(10))
                dockerFieldPanel.add(resetButton)
                dockerFieldPanel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)

        dockerPanel.add(dockerFieldPanel, BorderLayout.CENTER)
        dockerPanel.border = BorderFactory.createTitledBorder("Docker Image")

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

            // Create main panel
            panel = JPanel(BorderLayout())
            panel!!.add(dockerPanel, BorderLayout.NORTH)
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
        val settings = service<PluginSettings>().state

        // Check if notification settings are modified
        if (showNodeCheckbox?.isSelected != settings.shouldShowNodeNotification ||
            showClaudeCheckbox?.isSelected != settings.shouldShowClaudeNotification ||
            showClaudeSettingsCheckbox?.isSelected != settings.shouldShowClaudeSettingsNotification ||
            dockerImageField?.text != settings.dockerImage) {
            return true
        }

        // Check if any tool enablement is modified
        for ((toolName, checkbox) in toolCheckboxes) {
            if (checkbox.isSelected != settings.isToolEnabled(toolName)) {
                return true
            }
        }

        return false
    }

    override fun apply() {
        val settings = service<PluginSettings>().state

        // Apply notification settings
        settings.shouldShowNodeNotification = showNodeCheckbox?.isSelected ?: true
        settings.shouldShowClaudeNotification = showClaudeCheckbox?.isSelected ?: true
        settings.shouldShowClaudeSettingsNotification = showClaudeSettingsCheckbox?.isSelected ?: true
        settings.dockerImage = dockerImageField?.text ?: DEFAULT_DOCKER_IMAGE

        // Apply tool enablement settings
        for ((toolName, checkbox) in toolCheckboxes) {
            settings.setToolEnabled(toolName, checkbox.isSelected)
        }
    }

    override fun reset() {
        val settings = service<PluginSettings>().state

        // Reset notification settings
        showNodeCheckbox?.isSelected = settings.shouldShowNodeNotification
        showClaudeCheckbox?.isSelected = settings.shouldShowClaudeNotification
        showClaudeSettingsCheckbox?.isSelected = settings.shouldShowClaudeSettingsNotification
        dockerImageField?.text = settings.dockerImage ?: DEFAULT_DOCKER_IMAGE

        // Reset tool enablement settings
        for ((toolName, checkbox) in toolCheckboxes) {
            checkbox.isSelected = settings.isToolEnabled(toolName)
        }
    }

    companion object {
        const val DEFAULT_DOCKER_IMAGE = "gitpod/workspace-full"
    }
}
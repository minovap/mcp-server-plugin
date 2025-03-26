package org.jetbrains.mcpserverplugin.tools.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.ItemEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add

/**
 * Panel for running MCP tools via HTTP
 */
class McpToolPanel(private val project: Project) : JPanel(BorderLayout()) {
    // UI Components
    private val toolComboBox = ComboBox<ToolItem>()
    private val resultArea = JBTextArea(15, 50).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val logsArea = JBTextArea(15, 50).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val tabbedPane = com.intellij.ui.tabs.TabInfo(JBScrollPane(resultArea)).apply {
        setText("Results")
    }
    private val logTabInfo = com.intellij.ui.tabs.TabInfo(JBScrollPane(logsArea)).apply {
        setText("Logs")
    }
    private val contentTabs = com.intellij.ui.tabs.impl.JBTabsImpl(project).apply {
        addTab(tabbedPane)
        addTab(logTabInfo)
    }
    private val runButton = JButton("Run Tool").apply {
        icon = com.intellij.icons.AllIcons.Actions.Execute
    }
    
    // Logs checkbox
    private val logsCheckBox = JBCheckBox("Enable Logs", true).apply {
        toolTipText = "Enable or disable logging for tool execution"
    }
    
    // Request time label
    private val requestTimeLabel = JBLabel("", SwingConstants.LEFT)
    
    // Arguments panel
    private val argsPanel = JPanel(BorderLayout())
    private val argumentsContainer = JPanel(GridLayout(0, 1, 5, 5))
    private val argumentComponents = mutableMapOf<String, Pair<JComponent, JBCheckBox?>>()
    
    // Define available tools and their arguments
    private val availableTools = listOf(
        ToolItem(
            "glob", 
            "Search files by pattern", 
            mapOf(
                "pattern" to ArgumentInfo("**/*.kt", false),
                "path" to ArgumentInfo("src", true)
            )
        ),
        ToolItem(
            "grep", 
            "Search file contents", 
            mapOf(
                "pattern" to ArgumentInfo("class", false),
                "path" to ArgumentInfo("src", true),
                "include" to ArgumentInfo("*.kt", true)
            )
        ),
        ToolItem(
            "ls", 
            "List directory contents", 
            mapOf(
                "path" to ArgumentInfo("src", false),
                "ignore" to ArgumentInfo("[]", true)
            )
        ),
        ToolItem(
            "view", 
            "View file contents", 
            mapOf(
                "file_path" to ArgumentInfo("build.gradle.kts", false),
                "offset" to ArgumentInfo("0", true),
                "limit" to ArgumentInfo("2000", true)
            )
        ),
        ToolItem(
            "replace", 
            "Write file contents", 
            mapOf(
                "file_path" to ArgumentInfo("src/main/kotlin/Test.kt", false),
                "content" to ArgumentInfo("// Your content here", false)
            )
        ),
        ToolItem(
            "bash", 
            "Execute shell commands", 
            mapOf(
                "command" to ArgumentInfo("echo 'Hello World'", false),
                "timeout" to ArgumentInfo("30000", true)
            )
        ),
        ToolItem(
            "get_project_files_tree", 
            "Get project structure", 
            mapOf()
        ),
        ToolItem(
            "list_tools", 
            "List available tools", 
            mapOf()
        )
    )
    
    init {
        setupUI()
    }
    
    private fun setupUI() {
        border = JBUI.Borders.empty(10)
        
        // Setup tools dropdown
        setupToolComboBox()
        
        // Tool selection and arguments panel
        val topPanel = JPanel(BorderLayout(10, 10))
        
        val toolSelectionPanel = JPanel(BorderLayout(10, 0))
        val toolLabel = JBLabel("Tool:", SwingConstants.RIGHT)
        toolSelectionPanel.add(toolLabel, BorderLayout.WEST)
        toolSelectionPanel.add(toolComboBox, BorderLayout.CENTER)
        
        // Arguments panel
        argsPanel.border = BorderFactory.createTitledBorder("Arguments")
        val argsScrollPane = JBScrollPane(argumentsContainer)
        argsPanel.add(argsScrollPane, BorderLayout.CENTER)
        
        topPanel.add(toolSelectionPanel, BorderLayout.NORTH)
        topPanel.add(argsPanel, BorderLayout.CENTER)
        
        // Results panel with tabs
        val resultsPanel = JPanel(BorderLayout())
        resultsPanel.border = BorderFactory.createTitledBorder("Results")
        resultsPanel.add(contentTabs, BorderLayout.CENTER)
        
        // Button panel with logs checkbox
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        
        // Request time label - left aligned
        val timePanel = JPanel(BorderLayout())
        timePanel.add(requestTimeLabel, BorderLayout.WEST)
        
        // Add both panels to a container panel with BorderLayout
        val controlPanel = JPanel(BorderLayout())
        controlPanel.add(timePanel, BorderLayout.WEST)  // Time label on left
        controlPanel.add(buttonPanel, BorderLayout.EAST)  // Buttons on right
        
        // Add checkbox and button to the button panel
        buttonPanel.add(logsCheckBox)
        buttonPanel.add(runButton)
        
        // Setup run button action
        runButton.addActionListener { runTool() }
        
        // Add all panels to main panel
        val mainPanel = JPanel(BorderLayout(0, 10))
        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(resultsPanel, BorderLayout.CENTER)
        mainPanel.add(controlPanel, BorderLayout.SOUTH)
        
        add(mainPanel, BorderLayout.CENTER)
    }
    
    /**
     * Set up the tool dropdown
     */
    private fun setupToolComboBox() {
        val model = DefaultComboBoxModel<ToolItem>()
        availableTools.forEach { model.addElement(it) }
        toolComboBox.model = model
        
        // Add listener to update args when tool changes
        toolComboBox.addItemListener { event ->
            if (event.stateChange == ItemEvent.SELECTED) {
                val selected = event.item as ToolItem
                updateArgumentsPanel(selected)
            }
        }
        
        // Set initial selection
        if (availableTools.isNotEmpty()) {
            toolComboBox.selectedIndex = 0
            updateArgumentsPanel(availableTools[0])
        }
    }
    
    /**
     * Update the arguments panel based on the selected tool
     */
    private fun updateArgumentsPanel(toolItem: ToolItem) {
        // Clear existing arguments
        argumentsContainer.removeAll()
        argumentComponents.clear()
        
        // Create form builder
        val formBuilder = FormBuilder.createFormBuilder()
        
        // Add fields for each argument
        toolItem.arguments.forEach { (name, argInfo) ->
            val textField = JBTextField(argInfo.defaultValue)
            
            // Set tooltip based on whether this is an array parameter
            val isArrayParam = argInfo.defaultValue.startsWith("[") && argInfo.defaultValue.endsWith("]")
            if (isArrayParam) {
                textField.toolTipText = "Enter comma-separated values for $name (e.g., value1, value2, value3)"
            } else {
                textField.toolTipText = "Enter value for $name"
            }
            
            // Add action listener to run tool when Enter is pressed
            textField.addActionListener { runTool() }
            
            // For optional arguments, create a checkbox
            if (argInfo.optional) {
                val checkBox = JBCheckBox("Include", false)
                checkBox.toolTipText = "Check to include this optional argument"
                
                // Create a panel with both components
                val fieldPanel = JPanel(BorderLayout(5, 0))
                fieldPanel.add(textField, BorderLayout.CENTER)
                fieldPanel.add(checkBox, BorderLayout.EAST)
                
                // Add to form
                formBuilder.addLabeledComponent(name, fieldPanel)
                
                // Store components
                argumentComponents[name] = Pair(textField, checkBox)
                
                // Disable text field initially when checkbox is unchecked
                textField.isEnabled = checkBox.isSelected
                
                // Add listener to checkbox
                checkBox.addItemListener { 
                    textField.isEnabled = checkBox.isSelected
                }
            } else {
                // Required field - no checkbox needed
                formBuilder.addLabeledComponent(name, textField)
                argumentComponents[name] = Pair(textField, null)
            }
        }
        
        // If no arguments, show a message
        if (toolItem.arguments.isEmpty()) {
            formBuilder.addComponent(JBLabel("This tool does not require any arguments"))
        }
        
        // Add the form to the arguments container
        argumentsContainer.add(formBuilder.panel)
        
        // Update UI
        argumentsContainer.revalidate()
        argumentsContainer.repaint()
    }
    
    /**
     * Create JSON from the argument fields
     */
    private fun buildArgsJson(): String {
        val jsonObject = buildJsonObject {
            argumentComponents.forEach { (name, components) ->
                val (textField, checkbox) = components
                val textComponent = textField as JBTextField
                
                // Only include if the argument is required or the checkbox is selected
                if (checkbox == null || checkbox.isSelected) {
                    val value = textComponent.text.trim()
                    
                    // Get the default value for this argument to determine if it should be treated as an array
                    val defaultValue = toolComboBox.selectedItem?.let { toolItem ->
                        (toolItem as? ToolItem)?.arguments?.get(name)?.defaultValue
                    } ?: ""
                    val isArrayByDefault = defaultValue.startsWith("[") && defaultValue.endsWith("]")
                    
                    // Try to determine the type of value and convert appropriately
                    try {
                        // If it looks like a number, convert to a number
                        if (value.matches("-?\\d+".toRegex())) {
                            put(name, value.toInt())
                        } else if (value.matches("-?\\d+\\.\\d+".toRegex())) {
                            put(name, value.toDouble())
                        } else if (value.equals("true", ignoreCase = true) || 
                                value.equals("false", ignoreCase = true)) {
                            put(name, value.toBoolean())
                        } else if (value.startsWith("[") && value.endsWith("]")) {
                            // Treat as JSON array string - pass it as is
                            put(name, value)
                        } else if (value.startsWith("{") && value.endsWith("}")) {
                            // Treat as JSON object string
                            put(name, value)
                        } else if (isArrayByDefault) {
                            // This is an array parameter, but the value doesn't start with [ and end with ]
                            // Treat comma-separated values as array items
                            // Split by comma, trim each item, and handle empty case
                            val items = if (value.isEmpty()) {
                                emptyList()
                            } else {
                                value.split(',').map { it.trim() }
                            }
                            
                            // Create a proper JSON array using kotlinx.serialization
                            val jsonArray = kotlinx.serialization.json.buildJsonArray {
                                items.forEach { item ->
                                    add(kotlinx.serialization.json.JsonPrimitive(item))
                                }
                            }
                            put(name, jsonArray)
                        } else {
                            // Treat as string
                            put(name, value)
                        }
                    } catch (e: Exception) {
                        // If parsing fails, default to string
                        put(name, value)
                    }
                }
            }
        }
        
        return jsonObject.toString()
    }
    
    /**
     * Run the selected tool
     */
    private fun runTool() {
        val toolItem = toolComboBox.selectedItem as? ToolItem ?: return
        val args = buildArgsJson()
        
        // Show processing message
        resultArea.text = "Processing request..."
        runButton.isEnabled = false
        requestTimeLabel.text = "" // Clear previous request time
        
        // Run the request in a background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val startTime = System.currentTimeMillis()
                val result = executeToolViaHttp(toolItem.name, args)
                val endTime = System.currentTimeMillis()
                val requestTime = endTime - startTime
                
                // Update UI on EDT
                ApplicationManager.getApplication().invokeLater {
                    resultArea.text = formatResult(result)
                    requestTimeLabel.text = "Request time: ${requestTime}ms"
                    
                    // Update logs if available
                    try {
                        val jsonElement = kotlinx.serialization.json.Json { 
                            isLenient = true
                            ignoreUnknownKeys = true
                            encodeDefaults = false
                        }.parseToJsonElement(result)
                        
                        if (jsonElement is kotlinx.serialization.json.JsonObject) {
                            val logs = jsonElement["logs"]?.let {
                                if (it is kotlinx.serialization.json.JsonArray) {
                                    it.map { logEntry ->
                                        if (logEntry is kotlinx.serialization.json.JsonPrimitive) logEntry.content else ""
                                    }.joinToString("\n")
                                } else {
                                    null
                                }
                            }
                            
                            if (!logs.isNullOrEmpty()) {
                                logsArea.text = logs
                                // If logs available, make the log tab title bold
                                logTabInfo.setTooltipText("${logs.lines().size} log entries available")
                                logTabInfo.setText("Logs (${logs.lines().size})")
                                // Make the logs tab title more noticeable
                                logTabInfo.append(" ", com.intellij.ui.SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                            } else {
                                logsArea.text = "No logs available for this operation."
                                logTabInfo.setText("Logs")
                                // Reset tab title to normal
                                logTabInfo.setTooltipText(null)
                            }
                        }
                    } catch (e: Exception) {
                        logsArea.text = "Error parsing logs: ${e.message}"
                    }
                    
                    runButton.isEnabled = true
                }
            } catch (e: Exception) {
                // Handle and display the error
                ApplicationManager.getApplication().invokeLater {
                    resultArea.text = "Error: ${e.message}\n\n${e.stackTraceToString()}"
                    runButton.isEnabled = true
                    requestTimeLabel.text = "Request failed"
                }
            }
        }
    }
    
    /**
     * Execute a tool by making an HTTP request to the IDE's embedded server
     */
    private fun executeToolViaHttp(tool: String, argsJson: String): String {
        // Get the actual port from the BuiltInServerManager instead of using hardcoded port
        val port = org.jetbrains.ide.BuiltInServerManager.getInstance().port
        // The MCP service is available at http://localhost:{port}/api/mcp/{tool}
        val url = URL("http://localhost:$port/api/mcp/$tool")
        
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        
        // Only add the special header if logs are enabled
        if (logsCheckBox.isSelected) {
            connection.setRequestProperty("X-MCP-Tool-Panel", "true")  // Special header to identify calls from MCP tool panel
        }
        
        connection.doOutput = true
        
        // Only send body if we have args
        if (argsJson.isNotBlank() && argsJson != "{}") {
            connection.outputStream.use { os ->
                val writer = OutputStreamWriter(os)
                writer.write(argsJson)
                writer.flush()
            }
        }
        
        // Read the response
        val responseCode = connection.responseCode
        val inputStream = if (responseCode >= 400) connection.errorStream else connection.inputStream
        
        return BufferedReader(InputStreamReader(inputStream)).use { br ->
            val response = StringBuilder()
            var line: String?
            while (br.readLine().also { line = it } != null) {
                response.append(line).append('\n')
            }
            response.toString()
        }
    }
    
    /**
     * Format the result string to make it more readable
     * If response has status and error fields, display them appropriately
     */
    private fun formatResult(result: String): String {
        try {
            // Try to parse the JSON
            val json = kotlinx.serialization.json.Json { 
                prettyPrint = true 
                isLenient = true
                ignoreUnknownKeys = true
                encodeDefaults = false
            }
            
            // Parse to JsonElement
            val jsonElement = json.parseToJsonElement(result)
            
            // Check if this is a Response object with status or error fields
            if (jsonElement is kotlinx.serialization.json.JsonObject) {
                val status = jsonElement["status"]?.let {
                    if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
                }
                val error = jsonElement["error"]?.let {
                    if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
                }
                
                // Format based on available fields
                if (status != null || error != null) {
                    val resultBuilder = StringBuilder()
                    
                    // Show status if available
                    if (status != null) {
                        resultBuilder.append(status)
                    }
                    
                    // Show error if available
                    if (error != null) {
                        // Add separator if we already have status
                        if (status != null) {
                            resultBuilder.append("\n\n")
                        }
                        resultBuilder.append("Error: ").append(error)
                    }
                    
                    return resultBuilder.toString()
                }
            }
            
            // If not a Response object or no status/error fields, pretty print the JSON
            return json.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(), jsonElement)
                
        } catch (e: Exception) {
            // If it's not valid JSON, return as-is
            return result
        }
    }
    
    /**
     * Data class to represent an argument's information
     */
    data class ArgumentInfo(val defaultValue: String, val optional: Boolean)
    
    /**
     * Data class to represent tools in the dropdown
     */
    data class ToolItem(
        val name: String, 
        val description: String, 
        val arguments: Map<String, ArgumentInfo>
    ) {
        override fun toString(): String = "$name - $description"
    }
}
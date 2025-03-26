package org.jetbrains.mcpserverplugin.actions.ui.json

import com.intellij.json.JsonFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorSettings
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.JPanel
import kotlin.reflect.KClass
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.annotations.NotNull
import org.jetbrains.ide.mcp.JsonSchemaObject
import org.jetbrains.ide.mcp.PropertySchema

/**
 * Panel for editing JSON values based on a schema
 */
class JsonSchemaPanel(private val project: Project) : JPanel(BorderLayout()) {
    private lateinit var editor: EditorEx
    private val json = Json { prettyPrint = true }
    
    init {
        // Create the editor
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument("{}")
        editor = editorFactory.createEditor(document, project, JsonFileType.INSTANCE, false) as EditorEx
        
        // Configure editor settings
        val editorSettings: EditorSettings = editor.settings
        editorSettings.isVirtualSpace = false
        editorSettings.isLineMarkerAreaShown = false
        editorSettings.isIndentGuidesShown = true
        editorSettings.isLineNumbersShown = true
        editorSettings.isFoldingOutlineShown = true
        editorSettings.additionalColumnsCount = 3
        editorSettings.additionalLinesCount = 3
        editorSettings.isCaretRowShown = true
        
        // Add the editor to the panel
        val scrollPane = JBScrollPane(editor.component)
        add(scrollPane, BorderLayout.CENTER)
    }
    
    /**
     * Set the argument class to generate a schema
     */
    fun setArgClass(argClass: KClass<*>) {
        // Generate schema template
        val schemaTemplate = generateSchemaTemplate(argClass)
        
        // Update the editor with the template
        ApplicationManager.getApplication().runWriteAction {
            editor.document.setText(schemaTemplate)
        }
    }
    
    /**
     * Get the current JSON value from the editor
     */
    fun getJsonValue(): String {
        return editor.document.text
    }
    
    /**
     * Set the JSON value in the editor
     */
    fun setJsonValue(jsonValue: String) {
        ApplicationManager.getApplication().runWriteAction {
            editor.document.setText(jsonValue)
        }
    }
    
    /**
     * Generate a schema template based on the argument class
     */
    private fun generateSchemaTemplate(argClass: KClass<*>): String {
        // Create a schema from the class
        val schema = schemaFromDataClass(argClass)
        
        // Generate a template JSON object based on the schema
        val templateObject = generateTemplateFromSchema(schema)
        
        // Convert to pretty-printed JSON
        return json.encodeToString(templateObject)
    }
    
    /**
     * Generate a schema description from a data class
     */
    private fun schemaFromDataClass(kClass: KClass<*>): JsonSchemaObject {
        // Special handling for NoArgs
        if (kClass.simpleName == "NoArgs") {
            return JsonSchemaObject(type = "object")
        }

        // Get the constructor parameters to build the schema
        val constructor = kClass.constructors.firstOrNull()
            ?: error("Class ${kClass.simpleName} must have a constructor")

        val properties = constructor.parameters.mapNotNull { param ->
            param.name?.let { name ->
                name to when {
                    param.type.classifier == String::class -> PropertySchema("string")
                    param.type.classifier == Int::class || 
                    param.type.classifier == Long::class || 
                    param.type.classifier == Double::class || 
                    param.type.classifier == Float::class -> PropertySchema("number")
                    param.type.classifier == Boolean::class -> PropertySchema("boolean")
                    param.type.classifier == List::class -> PropertySchema("array")
                    param.type.classifier == Map::class -> PropertySchema("object")
                    else -> PropertySchema("object")
                }
            }
        }.toMap()

        // Get the required parameters (non-nullable)
        val required = constructor.parameters
            .filter { !it.isOptional && !it.type.isMarkedNullable }
            .mapNotNull { it.name }

        return JsonSchemaObject(
            type = "object",
            properties = properties,
            required = required
        )
    }
    
    /**
     * Generate a template JSON object from a schema
     */
    private fun generateTemplateFromSchema(schema: JsonSchemaObject): JsonObject {
        return buildJsonObject {
            schema.properties.forEach { (name, propSchema) ->
                when (propSchema.type) {
                    "string" -> put(name, "")
                    "number" -> put(name, 0)
                    "boolean" -> put(name, false)
                    "array" -> put(name, "[]")
                    "object" -> put(name, "{}")
                    else -> put(name, "")
                }
            }
        }
    }
    
    /**
     * Clean up resources
     */
    fun dispose() {
        EditorFactory.getInstance().releaseEditor(editor)
    }
}
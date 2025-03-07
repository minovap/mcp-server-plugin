package org.jetbrains.mcpserverplugin.tools

import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.utils.FileFinderUtils
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class ReplaceFileFunctionArgs(
    val pathInProject: String,
    val functionName: String,
    val text: String
)

/**
 * A tool that replaces a function in a file with new text.
 * The tool identifies the function by name, finds its start and end lines,
 * and replaces it with the provided text.
 */
class ReplaceFileFunctionTool : AbstractMcpTool<ReplaceFileFunctionArgs>() {
    private val LOG = logger<ReplaceFileFunctionTool>()
    
    override val name: String = "replace_file_function"
    override val description: String = """
        Replaces a function/method in a file with new text.
        
        <pathInProject> Path to the file, relative to project root
        <functionName> Name of the function/method to replace
        <text> New text for the function (should include the complete function definition)
        
        replace_file_function = ({pathInProject: string, functionName: string, text: string}) => string | { error: string };
    """.trimIndent()

    override fun handle(project: Project, args: ReplaceFileFunctionArgs): Response {
        // Make sure we have a clean function name
        LOG.info("Received request to replace function: '${args.functionName}' in file: ${args.pathInProject}")
        
        // Find the file in the project
        val findResult = FileFinderUtils.findFileInProject(project, args.pathInProject)
        
        return when (findResult) {
            is FileFinderUtils.FindFileResult.Found -> {
                try {
                    val virtualFile = findResult.virtualFile
                    
                    // We'll use AtomicReference to get the result from the read action
                    val functionLocationRef = AtomicReference<Pair<Int, Int>?>(null)
                    val errorRef = AtomicReference<String?>(null)
                    
                    // Run PSI operations in a read action on the UI thread to avoid threading issues
                    ApplicationManager.getApplication().invokeAndWait {
                        ReadAction.run<Throwable> {
                            try {
                                // Get the PSI file
                                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                                
                                if (psiFile == null) {
                                    errorRef.set("Couldn't parse file")
                                    return@run
                                }
                                
                                // Find the function's location using Structure View
                                val functionLocation = findFunctionLocation(psiFile, args.functionName)
                                
                                if (functionLocation == null) {
                                    LOG.error("Function '${args.functionName}' not found in file structure view")
                                    
                                    // Fallback: Try to find the function directly in the file content
                                    val fileContent = String(Files.readAllBytes(virtualFile.toNioPath()))
                                    val fallbackLocation = findFunctionInTextContent(fileContent, args.functionName)
                                    
                                    if (fallbackLocation == null) {
                                        errorRef.set("Function '${args.functionName}' not found in file")
                                        return@run
                                    } else {
                                        LOG.info("Found function using text search fallback at lines ${fallbackLocation.first}-${fallbackLocation.second}")
                                        functionLocationRef.set(fallbackLocation)
                                    }
                                } else {
                                    LOG.info("Found function '${args.functionName}' at lines ${functionLocation.first}-${functionLocation.second}")
                                    functionLocationRef.set(functionLocation)
                                }
                            } catch (e: Exception) {
                                errorRef.set("Error finding function: ${e.message}")
                                LOG.error("Error in read action", e)
                            }
                        }
                    }
                    
                    // Check if we had an error
                    errorRef.get()?.let {
                        return Response(error = it)
                    }
                    
                    // Get the function location
                    val functionLocation = functionLocationRef.get() ?: return Response(error = "Failed to find function location")
                    
                    // Read file content
                    val filePath = virtualFile.toNioPath()
                    var success = false
                    
                    // Replace the function text
                    WriteCommandAction.runWriteCommandAction(project) {
                        try {
                            val originalLines = Files.readAllLines(filePath).toMutableList()
                            
                            // Adjust for 0-based index in the list vs 1-based line numbers
                            val startIndex = functionLocation.first - 1
                            val endIndex = functionLocation.second - 1
                            
                            // Log the original function for debugging
                            val originalFunction = originalLines.subList(startIndex, endIndex + 1).joinToString("\n")
                            LOG.info("Original function text:\n$originalFunction")
                            
                            // Remove the original function lines
                            val linesCount = endIndex - startIndex + 1
                            repeat(linesCount) { originalLines.removeAt(startIndex) }
                            
                            // Add the new function text
                            val newLines = args.text.split("\n")
                            originalLines.addAll(startIndex, newLines)
                            
                            // Write the updated content back to the file
                            Files.write(filePath, originalLines)
                            virtualFile.refresh(false, false)
                            
                            success = true
                        } catch (e: Exception) {
                            LOG.error("Error replacing function", e)
                            success = false
                        }
                    }
                    
                    if (success) {
                        Response("ok")
                    } else {
                        Response(error = "Error replacing function")
                    }
                } catch (e: Exception) {
                    LOG.error("Error handling function replacement", e)
                    Response(error = "Error handling function replacement: ${e.message}")
                }
            }
            is FileFinderUtils.FindFileResult.NotFound -> {
                Response(error = findResult.error)
            }
        }
    }

    /**
     * Finds a function in the text content of a file by searching for its signature.
     * This is a fallback method when structure view fails.
     * 
     * @return Pair of (startLine, endLine) or null if function not found
     */
    private fun findFunctionInTextContent(fileContent: String, functionName: String): Pair<Int, Int>? {
        try {
            val lines = fileContent.lines()
            
            // Create various patterns to match function definitions
            val patterns = listOf(
                Regex("\\s*$functionName\\s*\\(.*\\)\\s*\\{"), // Standard function: functionName() {
                Regex("\\s*$functionName\\s*:\\s*function\\s*\\(.*\\)\\s*\\{"), // Object method: functionName: function() {
                Regex("\\s*$functionName\\s*=\\s*function\\s*\\(.*\\)\\s*\\{"), // Assignment: functionName = function() {
                Regex("\\s*$functionName\\s*:\\s*\\(.*\\)\\s*=>\\s*\\{") // Arrow function: functionName: () => {
            )
            
            var startLine = -1
            var bracketCount = 0
            var inFunction = false

            for ((index, line) in lines.withIndex()) {
                // If we're not in a function yet, check if this line starts one
                if (!inFunction) {
                    if (patterns.any { it.matches(line) }) {
                        LOG.info("Found function definition at line ${index + 1}: $line")
                        startLine = index + 1
                        inFunction = true
                        bracketCount = line.count { it == '{' } - line.count { it == '}' }
                    }
                    continue
                }
                
                // We're inside a function, count brackets to find the end
                bracketCount += line.count { it == '{' } - line.count { it == '}' }
                
                // When bracket count reaches 0, we've found the end of the function
                if (bracketCount <= 0) {
                    val endLine = index + 1
                    LOG.info("Found end of function at line $endLine")
                    return Pair(startLine, endLine)
                }
            }
            
            // If we found the start but not the end, use the last line as end
            if (startLine != -1) {
                LOG.info("Function appears to end at EOF, using last line")
                return Pair(startLine, lines.size)
            }
            
            return null
        } catch (e: Exception) {
            LOG.error("Error in text search fallback", e)
            return null
        }
    }

    /**
     * Finds the start and end line numbers for a function in a file.
     * Uses IntelliJ's Structure View API to locate the function.
     * 
     * @return Pair of (startLine, endLine) or null if function not found
     */
    private fun findFunctionLocation(psiFile: PsiFile, functionName: String): Pair<Int, Int>? {
        // This method must be called inside a read action
        try {
            // Get the structure view builder for the file
            @Suppress("DEPRECATION")
            val builder = LanguageStructureViewBuilder.INSTANCE.getStructureViewBuilder(psiFile)
                ?: return null
    
            if (builder !is TreeBasedStructureViewBuilder) {
                return null
            }
    
            // Get the structure view model and root element
            val structureViewModel = builder.createStructureViewModel(null)
            val rootElement = structureViewModel.root
            
            // Log file type for debugging
            LOG.info("Finding function '${functionName}' in file of type ${psiFile.fileType.name}, language ${psiFile.language.displayName}")
            
            // Create multiple possible representations of the function name based on language patterns
            val possibleNames = setPossibleFunctionNames(functionName)
            
            // Add the exact function name to the set of possible names (in case it contains special syntax)
            val allPossibleNames = possibleNames.toMutableSet()
            allPossibleNames.add(functionName)
            
            // Search for the function in the structure tree with multiple possible name patterns
            val result = findFunctionInStructure(rootElement, allPossibleNames)
            
            // Clean up the structure view model
            structureViewModel.dispose()
            
            return result
        } catch (e: Exception) {
            LOG.error("Error in findFunctionLocation", e)
            return null
        }
    }
    
    /**
     * Creates a set of possible function name patterns based on common language representations
     */
    private fun setPossibleFunctionNames(functionName: String): Set<String> {
        // First clean the function name to get just the base name
        val baseName = sanitizeFunctionName(functionName)
        
        // If the baseName is different from the functionName, log it
        if (baseName != functionName) {
            LOG.info("Sanitized function name from '$functionName' to '$baseName'")
        }
        
        return setOf(
            baseName,                         // Basic name: "methodName"
            "$baseName()",                    // No params: "methodName()"
            "$baseName(...)",                 // With elided params: "methodName(...)"
            "$baseName: function",            // JavaScript object notation: "methodName: function"
            "$baseName(): string",            // TypeScript/JS with return type: "methodName(): string"
            "$baseName(): void",              // TypeScript/JS with void return type  
            "$baseName(): number",            // TypeScript/JS with number return type
            "$baseName(): boolean",           // TypeScript/JS with boolean return type
            "$baseName(): any",               // TypeScript/JS with any return type
            "$baseName: function()",          // Alt JavaScript object method
            "$baseName: () =>",               // JavaScript arrow function in object
            "$baseName() {",                  // JavaScript class method start
            "$baseName = function"            // JavaScript variable function assignment
        )
    }
    
    /**
     * Sanitizes the function name by extracting just the base name without any JS/TS syntax.
     * This handles cases like "functionName() {" or "functionName: () => {" to extract just "functionName".
     */
    private fun sanitizeFunctionName(rawFunctionName: String): String {
        return rawFunctionName
            .trim()
            .replace(Regex("""^\s*\w+\s+"""), "")  // Remove leading keywords like "function"
            .replace(Regex("""\s*\(.*\).*$"""), "") // Remove everything from opening parenthesis onward
            .replace(Regex("""\s*:.*$"""), "")      // Remove everything from colon onward
            .replace(Regex("""\s*=.*$"""), "")      // Remove everything from equals sign onward
            .replace(Regex("""[{;].*$"""), "")      // Remove everything from opening brace or semicolon onward
            .trim()
    }
    
    /**
     * Extracts the base function name from various formats found in structure view
     */
    private fun extractBaseFunctionName(elementName: String): String {
        return when {
            // Format: "functionName()" or "functionName(...)"
            elementName.contains("(") && elementName.contains(")") ->
                elementName.substringBefore("(").trim()
                
            // Format: "functionName(): returnType"
            elementName.contains("):") ->
                elementName.substringBefore("(").trim()
                
            // Format: "functionName: function"
            elementName.contains(": function") ->
                elementName.substringBefore(":").trim()
                
            // Format: "functionName = function"
            elementName.contains("= function") ->
                elementName.substringBefore("=").trim()
                
            // Format: "functionName: () =>"
            elementName.contains(": () =>") ->
                elementName.substringBefore(":").trim()
                
            // Default case
            else -> elementName.trim()
        }
    }
    
    /**
     * Recursively searches for a function with any of the given possible names in the structure tree.
     * 
     * @return Pair of (startLine, endLine) or null if function not found
     */
    private fun findFunctionInStructure(element: TreeElement, possibleNames: Set<String>): Pair<Int, Int>? {
        try {
            // Get children elements
            val children = element.children
            
            // Get element name and element type 
            val elementName = element.presentation.presentableText ?: ""
            val elementType = element.presentation.locationString ?: ""
            
            // Debug log
            LOG.info("Checking element: '$elementName' (type: $elementType)")
            
            // Get start and end lines first as they may be needed multiple times
            var startLine: Int? = null
            var endLine: Int? = null
            
            try {
                // Try to extract line numbers using multiple methods
                startLine = getElementStartLine(element)
                endLine = getElementEndLine(element)
                
                if (startLine != null && endLine != null) {
                    LOG.info("Found line numbers for '$elementName': $startLine-$endLine")
                } else {
                    LOG.warn("Could not extract line numbers for '$elementName'")
                }
            } catch (e: Exception) {
                LOG.warn("Error getting line numbers for element: $elementName", e)
            }
            
            // First, try direct matches with our predefined patterns
            if (possibleNames.contains(elementName)) {
                LOG.info("Found direct match: '$elementName'")
                
                if (startLine != null && endLine != null) {
                    LOG.info("Function found at lines $startLine-$endLine")
                    return Pair(startLine, endLine)
                }
            }
            
            // If no direct match, try more sophisticated pattern matching
            // First get the stripped name using our improved extractor
            val baseName = extractBaseFunctionName(elementName)
            
            LOG.info("Extracted base name: '$baseName'")
            
            // Check if the base name matches any of our possible names
            if (baseName.isNotEmpty() && possibleNames.contains(baseName)) {
                LOG.info("Found match with base name: '$baseName'")
                
                if (startLine != null && endLine != null) {
                    LOG.info("Function found at lines $startLine-$endLine")
                    return Pair(startLine, endLine)
                } else {
                    // If we found a match but couldn't get line numbers, dump the element for debugging
                    LOG.warn("Element details for debugging: $element")
                }
            }
            
            // Handle JavaScript class methods by checking parent class
            if (elementType.contains("class", ignoreCase = true) || 
                elementName.endsWith(".js") || elementName.endsWith(".ts")) {
                LOG.info("Checking inside class/JS file: $elementName")
                // In JS classes, we want to thoroughly check all children
            }
            
            // Recursively search in children
            for (child in children) {
                val result = findFunctionInStructure(child, possibleNames)
                if (result != null) {
                    return result
                }
            }
            
            // Function not found in this branch
            return null
        } catch (e: Exception) {
            LOG.error("Error searching for function in structure: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Helper method to get the start line of an element using multiple approaches
     */
    private fun getElementStartLine(element: TreeElement): Int? {
        return try {
            // Method 1: Try to get it from element properties as a string
            getPropertyFromElement(element, "startLine")?.toIntOrNull()
                // Method 2: Try to access a field directly using reflection
                ?: tryGetFieldValue(element, "startLine") as? Int
                // Method 3: Try to access a method
                ?: tryCallMethod(element, "getStartLine") as? Int
                // Method 4: Try the "line" property which some elements might have
                ?: getPropertyFromElement(element, "line")?.toIntOrNull()
                // Method 5: Try to get the first child's start line if this element has children
                ?: if (element.children.isNotEmpty()) {
                    getElementStartLine(element.children.first())
                } else null
        } catch (e: Exception) {
            LOG.warn("Error getting start line", e)
            null
        }
    }
    
    /**
     * Helper method to get the end line of an element using multiple approaches
     */
    private fun getElementEndLine(element: TreeElement): Int? {
        return try {
            // Method 1: Try to get it from element properties as a string
            getPropertyFromElement(element, "endLine")?.toIntOrNull()
                // Method 2: Try to access a field directly using reflection
                ?: tryGetFieldValue(element, "endLine") as? Int
                // Method 3: Try to access a method
                ?: tryCallMethod(element, "getEndLine") as? Int
                // Method 4: Try the "line" property as a fallback (for single-line elements)
                ?: getPropertyFromElement(element, "line")?.toIntOrNull()
                // Method 5: Try to get the last child's end line if this element has children
                ?: if (element.children.isNotEmpty()) {
                    getElementEndLine(element.children.last())
                } else null
        } catch (e: Exception) {
            LOG.warn("Error getting end line", e)
            null
        }
    }
    
    /**
     * Helper method to try to call a method on an object using reflection
     */
    private fun tryCallMethod(obj: Any, methodName: String): Any? {
        return try {
            val method = obj.javaClass.methods.find { it.name == methodName && it.parameterCount == 0 }
            method?.let {
                it.isAccessible = true
                it.invoke(obj)
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Helper method to try to get a field value from an object using reflection
     */
    private fun tryGetFieldValue(obj: Any, fieldName: String): Any? {
        return try {
            // Try to find the field in this class or its superclasses
            var clazz: Class<*>? = obj.javaClass
            while (clazz != null) {
                try {
                    val field = clazz.getDeclaredField(fieldName)
                    field.isAccessible = true
                    return field.get(obj)
                } catch (e: NoSuchFieldException) {
                    clazz = clazz.superclass
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Helper method to extract properties from TreeElement.
     * This is a simplified approach to avoid reflection complexities.
     */
    private fun getPropertyFromElement(element: TreeElement, propertyName: String): String? {
        val elementString = element.toString()
        
        // For implementations that expose these properties directly
        val regex = "\"$propertyName\":\\s*(\\d+)".toRegex()
        val match = regex.find(elementString)
        
        return match?.groupValues?.getOrNull(1)
    }
}
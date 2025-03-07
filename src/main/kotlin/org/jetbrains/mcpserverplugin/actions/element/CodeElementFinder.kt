package org.jetbrains.mcpserverplugin.actions.element

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * Utility for finding and working with code elements in editor
 */
object CodeElementFinder {
    
    /**
     * Finds the element at the current cursor position or selection
     */
    fun findElementAtCursor(editor: Editor, file: PsiFile): PsiElement? {
        val selectionModel = editor.selectionModel
        val elementAtCursor: PsiElement?
        
        if (selectionModel.hasSelection()) {
            // If there's a selection, get it
            val startOffset = selectionModel.selectionStart
            elementAtCursor = file.findElementAt(startOffset)
        } else {
            // If there's no selection, get the element at cursor
            val offset = editor.caretModel.offset
            elementAtCursor = file.findElementAt(offset)
        }
        
        if (elementAtCursor == null) return null
        
        // Try to find a meaningful parent element (method, class, etc.)
        return findMeaningfulParent(elementAtCursor)
    }
    
    /**
     * Finds a meaningful parent element (method, class, etc.)
     */
    @Suppress("UNCHECKED_CAST")
    fun findMeaningfulParent(element: PsiElement): PsiElement {
        // This looks for containing structural elements like methods, classes, fields, etc.
        // We use reflection to avoid direct dependencies on language-specific PSI elements
        
        // Try to find parents of various types - we need to use reflection to avoid
        // direct dependencies which might not be available at runtime
        val possibleParentTypes = listOf(
            "com.intellij.psi.PsiMethod",
            "com.intellij.psi.PsiClass",
            "com.intellij.psi.PsiField",
            "com.intellij.psi.PsiVariable", 
            "org.jetbrains.kotlin.psi.KtFunction",
            "org.jetbrains.kotlin.psi.KtClass",
            "org.jetbrains.kotlin.psi.KtProperty",
            "org.jetbrains.kotlin.psi.KtParameter"
        )
        
        // Try each type and return the first match
        for (typeName in possibleParentTypes) {
            try {
                val clazz = Class.forName(typeName)
                // Use as with @Suppress to silence the unchecked cast warning
                val parent = PsiTreeUtil.getParentOfType(element, clazz as Class<PsiElement>)
                if (parent != null) {
                    return parent
                }
            } catch (e: ClassNotFoundException) {
                // Type not available, try the next one
            } catch (e: ClassCastException) {
                // Shouldn't happen, but just in case
            }
        }
        
        // If no specific parent is found, try to get a reasonable container
        val container = PsiTreeUtil.getParentOfType(
            element,
            PsiElement::class.java,
            /* strict */ true
        )
        
        // Return the container, or if all else fails, the original element
        return container ?: element
    }
}

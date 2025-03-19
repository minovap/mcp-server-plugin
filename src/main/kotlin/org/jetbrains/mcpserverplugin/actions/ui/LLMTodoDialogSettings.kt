package org.jetbrains.mcpserverplugin.actions.ui

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Persistent settings for the LLMTodoDialog
 */
@Service
@State(
    name = "org.jetbrains.mcpserverplugin.actions.ui.LLMTodoDialogSettings",
    storages = [Storage("mcpServerplugin.xml")]
)
class LLMTodoDialogSettings : PersistentStateComponent<LLMTodoDialogSettings.State> {
    /**
     * State class to hold persistent dialog settings
     */
    data class State(
        var dialogWidth: Int = 800,
        var dialogHeight: Int = 600
    )
    
    // Current state
    private var myState = State()
    
    override fun getState(): State = myState
    
    override fun loadState(state: State) {
        myState = state
    }
    
    /**
     * Gets the saved dialog size
     */
    fun getDialogSize(): java.awt.Dimension {
        return java.awt.Dimension(myState.dialogWidth, myState.dialogHeight)
    }
    
    /**
     * Saves the dialog size
     */
    fun saveDialogSize(size: java.awt.Dimension) {
        myState.dialogWidth = size.width
        myState.dialogHeight = size.height
    }
    
    companion object {
        /**
         * Gets the service instance
         */
        fun getInstance(): LLMTodoDialogSettings {
            return service()
        }
    }
}
package com.github.capntrips.bootcontrol

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel constructor(context: Context) : ViewModel() {
    companion object {
        const val TAG: String = "BootControl/MainViewModel"
    }
    private val _isRefreshing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private lateinit var _uiState: MutableStateFlow<BootControl>
    private var _error: String? = null

    val isRefreshing: StateFlow<Boolean>
        get() = _isRefreshing.asStateFlow()
    val uiState: StateFlow<BootControl>
        get() = _uiState.asStateFlow()
    val hasError: Boolean
        get() = _error != null
    val error: String
        get() = _error ?: "Unknown Error"

    init {
        try {
            _uiState = MutableStateFlow(BootControl(context, _isRefreshing))
        } catch (e: Exception) {
            Log.e(TAG, e.message, e)
            _error = e.message
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.emit(true)
            block()
            _isRefreshing.emit(false)
        }
    }

    fun refresh(context: Context) {
        launch {
            try {
                uiState.value.refresh(context)
            } catch (e: Exception) {
                Log.e(TAG, e.message, e)
                _error = e.message
            }
        }
    }

    private fun log(context: Context, message: String, shouldThrow: Boolean = false) {
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        Log.d(TAG, message)
        if (shouldThrow) {
            throw Exception(message)
        }
    }

    fun activate(context: Context, slot: SlotState) {
        launch {
            val slotA = uiState.value.slotA.value
            val slotB = uiState.value.slotB.value
            if (slotA == slot) {
                Log.w(TAG, "activating slot a")
                uiState.value.setActiveBootSlot(0U)
            } else if (slotB == slot) {
                Log.w(TAG, "activating slot b")
                uiState.value.setActiveBootSlot(1U)
            } else {
                log(context, "Invalid slot", shouldThrow = true)
            }
            log(context, "slot activated")
            uiState.value.refresh(context)
        }
    }
}

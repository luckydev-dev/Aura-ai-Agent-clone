package com.example.core.lifecycle

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable application foreground state with a 300ms debounce to prevent flicker
 * during activity transitions.
 */
object AppForegroundState {
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private var activeActivities = 0
    private var debounceJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    fun onActivityStarted() {
        activeActivities++
        debounceJob?.cancel()
        _isForeground.value = true
    }

    fun onActivityStopped() {
        activeActivities = (activeActivities - 1).coerceAtLeast(0)
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(300)
            if (activeActivities == 0) {
                _isForeground.value = false
            }
        }
    }

    val current: Boolean get() = _isForeground.value
}

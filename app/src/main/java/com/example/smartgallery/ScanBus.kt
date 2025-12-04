package com.example.smartgallery

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// Singleton bus for in-process scan events. UI & Service/Worker collect from it.
object ScanBus {
    private val _events = MutableSharedFlow<ScanProgress>(replay = 50) // replay a bit for UI
    val events = _events.asSharedFlow()

    suspend fun emit(progress: ScanProgress) {
        _events.emit(progress)
    }
}

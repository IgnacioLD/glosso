package com.glosso.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Drives the optional on-device scoring pack download and removal. */
class ScoringViewModel(private val scorer: OnDeviceScoring) : ViewModel() {

    val state: StateFlow<OnDeviceScorerState> = scorer.state

    fun download() {
        viewModelScope.launch { scorer.download() }
    }

    fun delete() {
        viewModelScope.launch {
            scorer.delete()
            Analytics.track("model_deleted")
        }
    }

    fun dismissError() = scorer.dismissError()
}

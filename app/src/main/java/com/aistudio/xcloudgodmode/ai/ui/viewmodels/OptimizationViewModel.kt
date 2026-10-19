package com.aistudio.xcloudgodmode.ai.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.xcloudgodmode.ai.services.GeminiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OptimizationViewModel(private val geminiService: GeminiService) : ViewModel() {
    private val _recommendation = MutableStateFlow("Loading AI recommendation...")
    val recommendation: StateFlow<String> = _recommendation.asStateFlow()

    fun fetchRecommendation(gameTitle: String, networkType: String) {
        viewModelScope.launch {
            _recommendation.value = geminiService.getOptimizationRecommendation(gameTitle, networkType)
        }
    }
}

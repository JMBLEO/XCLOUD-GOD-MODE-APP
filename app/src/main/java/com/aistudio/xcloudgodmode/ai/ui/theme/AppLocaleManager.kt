package com.aistudio.xcloudgodmode.ai.ui.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Sealed class representing supported language states in God Mode.
 */
sealed class LanguageState(val code: String, val displayName: String, val nativeName: String) {
    object Auto : LanguageState("AUTO", "Automático (Sistema)", "System Default")
    object Spanish : LanguageState("es", "Español (Neutro)", "Español")
    object English : LanguageState("en", "English (United States)", "English")

    companion object {
        val all: List<LanguageState> = listOf(Auto, Spanish, English)

        fun fromCode(code: String): LanguageState {
            return when (code.lowercase().trim()) {
                "es" -> Spanish
                "en" -> English
                else -> Auto
            }
        }
    }
}

/**
 * AppLocaleManager handles dynamic switching between 'es' (Spanish) and 'en' (English),
 * broadcasting reactive updates via StateFlow to compose components.
 */
object AppLocaleManager {
    private val _currentLanguageState = MutableStateFlow<LanguageState>(LanguageState.Auto)
    val currentLanguageState: StateFlow<LanguageState> = _currentLanguageState.asStateFlow()

    private val _currentStrings = MutableStateFlow(AppStrings.get("AUTO"))
    val currentStrings: StateFlow<AppStringsData> = _currentStrings.asStateFlow()

    /**
     * Switch current language state.
     */
    fun setLanguage(state: LanguageState) {
        _currentLanguageState.value = state
        _currentStrings.value = AppStrings.get(state.code)
    }

    /**
     * Switch current language state by code ('es', 'en', 'AUTO').
     */
    fun setLanguageByCode(code: String) {
        val state = LanguageState.fromCode(code)
        setLanguage(state)
    }

    /**
     * Returns true if effective language is Spanish (either explicitly or via system default).
     */
    val isSpanish: Boolean
        get() = when (_currentLanguageState.value) {
            LanguageState.Spanish -> true
            LanguageState.English -> false
            LanguageState.Auto -> Locale.getDefault().language.lowercase().startsWith("es")
        }

    /**
     * Returns effective two-letter language code ('es' or 'en').
     */
    val effectiveCode: String
        get() = when (_currentLanguageState.value) {
            LanguageState.Spanish -> "es"
            LanguageState.English -> "en"
            LanguageState.Auto -> if (Locale.getDefault().language.lowercase().startsWith("es")) "es" else "en"
        }
}

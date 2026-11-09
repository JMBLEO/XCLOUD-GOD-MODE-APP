package com.xcloud.godmode.engine

import com.xcloud.godmode.models.EngineCatalogVisibility
import com.xcloud.godmode.models.EngineId
import com.xcloud.godmode.models.EngineMenuEntry

class EngineCatalog(
    private val availability: EngineAvailability
) {
    fun entries(
        visibility: EngineCatalogVisibility = EngineCatalogVisibility.IMPLEMENTED_ONLY
    ): List<EngineMenuEntry> {
        val entries = buildEntries()
        return when (visibility) {
            EngineCatalogVisibility.IMPLEMENTED_ONLY ->
                entries.filter(EngineMenuEntry::selectable)
            EngineCatalogVisibility.INCLUDE_UNAVAILABLE ->
                entries
        }
    }

    private fun buildEntries(): List<EngineMenuEntry> {
        return listOf(
            createEntry(
                engineId = EngineId.MEDIACODEC_DIRECT_SURFACE,
                title = "Superficie directa",
                description = "Decodificación por hardware hacia Surface.",
                supportsSpatialUpscaling = false,
                supportsTemporalUpscaling = false
            ),
            createEntry(
                engineId = EngineId.MEDIACODEC_EGL_EXTERNAL_TEXTURE,
                title = "Textura externa",
                description = "Salida del decodificador hacia una textura gráfica.",
                supportsSpatialUpscaling = true,
                supportsTemporalUpscaling = false
            ),
            createEntry(
                engineId = EngineId.MEDIACODEC_VULKAN_COMPUTE,
                title = "Procesamiento espacial",
                description = "Procesamiento gráfico mediante cómputo.",
                supportsSpatialUpscaling = true,
                supportsTemporalUpscaling = false
            )
        )
    }

    private fun createEntry(
        engineId: EngineId,
        title: String,
        description: String,
        supportsSpatialUpscaling: Boolean,
        supportsTemporalUpscaling: Boolean
    ): EngineMenuEntry {
        return EngineMenuEntry(
            engineId = engineId,
            title = title,
            description = description,
            readiness = availability.readiness(engineId),
            supportsSpatialUpscaling = supportsSpatialUpscaling,
            supportsTemporalUpscaling = supportsTemporalUpscaling
        )
    }
}

package com.pnprojects.tarangfx.data.db

import com.pnprojects.tarangfx.domain.model.EqPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** User-defined EQ presets persisted in Room (built-ins live in code). */
@Singleton
class EqPresetRepository @Inject constructor(private val dao: EqPresetDao) {
    private val listSer = ListSerializer(Float.serializer())

    val userPresets: Flow<List<EqPreset>> = dao.observeAll().map { rows ->
        rows.map { EqPreset(it.id, it.name, runCatching { Json.decodeFromString(listSer, it.bandGainsJson) }.getOrDefault(List(10) { 0f }), it.isBuiltIn) }
    }

    suspend fun save(name: String, gains: List<Float>) {
        dao.upsert(EqPresetEntity(name = name, isBuiltIn = false, bandGainsJson = Json.encodeToString(listSer, gains)))
    }

    suspend fun delete(preset: EqPreset) {
        if (!preset.builtIn && preset.id > 0) dao.delete(preset.id)
    }
}

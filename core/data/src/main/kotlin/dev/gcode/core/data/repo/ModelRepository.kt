package dev.gcode.core.data.repo

import dev.gcode.core.data.db.ModelDao
import dev.gcode.core.data.db.ModelEntity
import dev.gcode.core.data.settings.SettingsRepository
import dev.gcode.gemini.model.ModelInfo
import dev.gcode.gemini.select.ModelCatalog
import dev.gcode.gemini.transport.GeminiTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Caches `models.list` for the configured time (24 h by default). */
class ModelRepository(
    private val transport: GeminiTransport,
    private val dao: ModelDao,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val catalog: Flow<ModelCatalog> = dao.observeAll().map { rows -> ModelCatalog(rows.map(::toInfo)) }

    suspend fun current(): ModelCatalog = ModelCatalog(dao.all().map(::toInfo))

    /** Refreshes when forced, when empty, or when the cache is older than the configured age. */
    suspend fun refreshIfStale(force: Boolean = false): ModelCatalog {
        val rows = dao.all()
        val maxAge = settings.current().modelCacheHours * 3_600_000L
        val fresh = rows.isNotEmpty() && clock() - rows.minOf { it.fetchedAt } < maxAge
        if (fresh && !force) return ModelCatalog(rows.map(::toInfo))
        val models = transport.listModels()
        val now = clock()
        dao.replaceAll(models.map { toEntity(it, now) })
        return ModelCatalog(models)
    }

    private fun toEntity(m: ModelInfo, now: Long) = ModelEntity(
        id = m.id,
        displayName = m.displayName,
        description = m.description,
        inputTokenLimit = m.inputTokenLimit,
        outputTokenLimit = m.outputTokenLimit,
        methods = m.supportedGenerationMethods.joinToString(","),
        thinking = m.thinking,
        fetchedAt = now,
    )

    private fun toInfo(e: ModelEntity) = ModelInfo(
        name = "models/${e.id}",
        displayName = e.displayName,
        description = e.description,
        inputTokenLimit = e.inputTokenLimit,
        outputTokenLimit = e.outputTokenLimit,
        supportedGenerationMethods = e.methods.split(',').filter { it.isNotBlank() },
        thinking = e.thinking,
    )
}

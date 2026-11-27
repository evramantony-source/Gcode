package dev.gcode.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * App database. Schemas are exported to core/data/schemas; each later version adds an
 * AutoMigration so installed debug builds keep their data.
 */
@Database(
    entities = [SessionEntity::class, MessageEntity::class, ModelEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class GCodeDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun messages(): MessageDao
    abstract fun models(): ModelDao

    companion object {
        const val NAME = "gcode.db"
    }
}

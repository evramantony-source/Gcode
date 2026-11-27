package dev.gcode.core.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * App database. Schemas are exported to core/data/schemas; each later version adds an
 * AutoMigration so installed debug builds keep their data.
 */
@Database(
    entities = [
        SessionEntity::class, MessageEntity::class, ModelEntity::class,
        LocalRepoEntity::class, RemoteRepoEntity::class, CheckpointEntity::class, BuildEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class GCodeDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun messages(): MessageDao
    abstract fun models(): ModelDao
    abstract fun localRepos(): LocalRepoDao
    abstract fun remoteRepos(): RemoteRepoDao
    abstract fun checkpoints(): CheckpointDao
    abstract fun builds(): BuildDao

    companion object {
        const val NAME = "gcode.db"
    }
}

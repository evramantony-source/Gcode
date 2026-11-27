package dev.gcode.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.gcode.core.data.db.GCodeDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every schema version migrates to the latest without losing sessions (ARCHITECTURE.md section 9). */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GCodeDatabase::class.java)

    @Test
    fun migratesFromVersion1KeepingSessions() {
        helper.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO sessions (id, title, createdAt, updatedAt, modelId, repoFullName, branch, mode, todoJson, compactedUpTo, lastPromptTokens, contextLimit) VALUES ('s1', 'Hello', 1, 1, NULL, NULL, NULL, 'ask', '[]', 0, 0, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, LATEST, true)
        db.query("SELECT title FROM sessions WHERE id = 's1'").use { c ->
            c.moveToFirst()
            assertEquals("Hello", c.getString(0))
        }
    }

    private companion object {
        const val DB = "migration-test.db"
        const val LATEST = 3
    }
}

package dev.gcode.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gcode.core.data.db.GCodeDatabase
import dev.gcode.core.data.repo.ModelRepository
import dev.gcode.core.data.repo.SessionRepository
import dev.gcode.core.data.secret.EncryptedSecretStore
import dev.gcode.core.data.secret.KeystoreSecretCipher
import dev.gcode.core.data.secret.SecretKey
import dev.gcode.core.data.secret.SecretStore
import dev.gcode.core.data.settings.SettingsRepository
import dev.gcode.gemini.ResilientGemini
import dev.gcode.gemini.select.ModelHealth
import dev.gcode.gemini.select.ModelSelector
import dev.gcode.gemini.transport.GeminiTransport
import dev.gcode.gemini.transport.HttpGeminiTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SecretsStore

/**
 * One DataStore per file for the whole process. DataStore refuses two active instances on the same file,
 * and Hilt re-creates the singleton component for every instrumented test in the same process.
 */
internal object ProcessDataStores {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stores = HashMap<String, DataStore<Preferences>>()

    @Synchronized
    fun get(context: Context, name: String): DataStore<Preferences> = stores.getOrPut(name) {
        val app = context.applicationContext
        PreferenceDataStoreFactory.create(scope = scope) { app.preferencesDataStoreFile(name) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Thinking models can pause for minutes between streamed chunks.
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    @Provides
    @Singleton
    @SettingsStore
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> = ProcessDataStores.get(context, "settings")

    @Provides
    @Singleton
    @SecretsStore
    fun secretsDataStore(@ApplicationContext context: Context): DataStore<Preferences> = ProcessDataStores.get(context, "secrets")

    @Provides
    @Singleton
    fun settingsRepository(@SettingsStore dataStore: DataStore<Preferences>) = SettingsRepository(dataStore)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): GCodeDatabase =
        Room.databaseBuilder(context, GCodeDatabase::class.java, GCodeDatabase.NAME).build()

    @Provides
    @Singleton
    fun sessionRepository(db: GCodeDatabase) = SessionRepository(db)

    @Provides
    @Singleton
    fun modelRepository(transport: GeminiTransport, db: GCodeDatabase, settings: SettingsRepository) =
        ModelRepository(transport, db.models(), settings)

    @Provides
    @Singleton
    fun modelHealth() = ModelHealth()

    @Provides
    @Singleton
    fun modelSelector(health: ModelHealth) = ModelSelector(health)

    @Provides
    @Singleton
    fun resilientGemini(transport: GeminiTransport, health: ModelHealth) = ResilientGemini(transport, health)
}

/** Creates a Gemini transport for a key (used to verify a key before it is saved). */
fun interface GeminiTransportFactory {
    fun create(apiKey: suspend () -> String?): GeminiTransport
}

/** Replaced in instrumented tests by a module with an in-memory store. */
@Module
@InstallIn(SingletonComponent::class)
object SecretsModule {
    @Provides
    @Singleton
    fun secretStore(@SecretsStore dataStore: DataStore<Preferences>): SecretStore = EncryptedSecretStore(dataStore, KeystoreSecretCipher())
}

/** The provider layer (decision D1). Replaced in instrumented tests by a scripted fake. */
@Module
@InstallIn(SingletonComponent::class)
object GeminiModule {
    @Provides
    @Singleton
    fun transportFactory(client: OkHttpClient): GeminiTransportFactory = GeminiTransportFactory { key -> HttpGeminiTransport(client, key) }

    @Provides
    @Singleton
    fun geminiTransport(factory: GeminiTransportFactory, secrets: SecretStore): GeminiTransport =
        factory.create { secrets.get(SecretKey.GEMINI_API_KEY) }
}

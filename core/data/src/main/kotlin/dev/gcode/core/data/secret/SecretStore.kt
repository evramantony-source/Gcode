package dev.gcode.core.data.secret

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Base64

/** Names of the secrets GCode stores. Values never leave the device unencrypted and are never logged. */
enum class SecretKey(val id: String) {
    GEMINI_API_KEY("gemini_api_key"),
    GITHUB_TOKEN("github_token"),
    GITHUB_TOKEN_TYPE("github_token_type"),
    ;

    companion object {
        /** MCP server auth headers are stored per server id. */
        fun mcpHeader(serverId: String) = "mcp_header_$serverId"
    }
}

interface SecretStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)
    fun observePresent(key: String): Flow<Boolean>

    suspend fun get(key: SecretKey) = get(key.id)
    suspend fun put(key: SecretKey, value: String) = put(key.id, value)
    suspend fun remove(key: SecretKey) = remove(key.id)
    fun observePresent(key: SecretKey) = observePresent(key.id)
}

/** Encrypts and decrypts small values. The Android implementation keeps its key in the Keystore. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray
}

/** Stores only ciphertext (base64) in a dedicated DataStore file (ARCHITECTURE.md section 9). */
class EncryptedSecretStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) : SecretStore {
    private fun pref(key: String) = stringPreferencesKey(key)

    override suspend fun get(key: String): String? {
        val sealed = dataStore.data.first()[pref(key)] ?: return null
        return runCatching { String(cipher.decrypt(Base64.getDecoder().decode(sealed)), Charsets.UTF_8) }.getOrNull()
    }

    override suspend fun put(key: String, value: String) {
        val sealed = Base64.getEncoder().encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8)))
        dataStore.edit { it[pref(key)] = sealed }
    }

    override suspend fun remove(key: String) {
        dataStore.edit { it.remove(pref(key)) }
    }

    override fun observePresent(key: String): Flow<Boolean> = dataStore.data.map { it[pref(key)] != null }
}

package dev.gcode.app.agent

import dev.gcode.agent.tools.Tool
import dev.gcode.agent.tools.mcp.McpServerConfig
import dev.gcode.agent.tools.mcp.McpToolAdapter
import dev.gcode.app.BuildConfig
import dev.gcode.core.data.secret.SecretKey
import dev.gcode.core.data.secret.SecretStore
import dev.gcode.core.data.settings.McpServerEntry
import dev.gcode.core.data.settings.SettingsRepository
import dev.gcode.mcp.McpClient
import dev.gcode.mcp.McpServerInfo
import dev.gcode.mcp.McpTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** Connection state of one MCP server, shown in Settings. */
data class McpServerStatus(val info: McpServerInfo? = null, val tools: List<McpTool> = emptyList(), val error: String? = null, val checkedAt: Long = 0)

/**
 * MCP servers configured in Settings (ARCHITECTURE.md section 11): one client per server, tool lists cached
 * for a few minutes, and the tools wrapped for the agent. A failing server never breaks a turn.
 */
@Singleton
class McpManager @Inject constructor(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
) {
    private val clients = mutableMapOf<String, Pair<String, McpClient>>()
    private val lock = Mutex()
    private val _status = MutableStateFlow<Map<String, McpServerStatus>>(emptyMap())
    val status: StateFlow<Map<String, McpServerStatus>> = _status.asStateFlow()

    /** Tools of every enabled server (cached for [CACHE_MS]). */
    suspend fun tools(): List<Tool> {
        val servers = settings.current().mcpServers.filter { it.enabled }
        return servers.flatMap { entry ->
            val client = client(entry)
            val cached = _status.value[entry.id]
            val tools = if (cached != null && cached.error == null && System.currentTimeMillis() - cached.checkedAt < CACHE_MS) {
                cached.tools
            } else {
                refresh(entry, client).tools
            }
            val config = McpServerConfig(entry.id, entry.name, entry.url, entry.enabled, entry.trusted)
            tools.map { McpToolAdapter(config, client, it) }
        }
    }

    /** Connects and lists tools (Settings "Test" and cache refresh). */
    suspend fun refresh(entry: McpServerEntry, client: McpClient? = null): McpServerStatus {
        val c = client ?: client(entry)
        val status = try {
            val info = c.connect()
            McpServerStatus(info, c.listTools(), null, System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            McpServerStatus(error = e.message ?: e.toString(), checkedAt = System.currentTimeMillis())
        }
        _status.update { it + (entry.id to status) }
        return status
    }

    suspend fun forget(id: String) {
        lock.withLock { clients.remove(id) }?.second?.close()
        _status.update { it - id }
        secrets.remove(SecretKey.mcpHeader(id))
    }

    private suspend fun client(entry: McpServerEntry): McpClient = lock.withLock {
        clients[entry.id]?.takeIf { it.first == key(entry) }?.second ?: McpClient(
            http,
            entry.url,
            headers = {
                val value = secrets.get(SecretKey.mcpHeader(entry.id))
                if (entry.headerName.isNotBlank() && !value.isNullOrBlank()) mapOf(entry.headerName to value) else emptyMap()
            },
            clientName = "GCode",
            clientVersion = BuildConfig.VERSION_NAME,
        ).also { clients[entry.id] = key(entry) to it }
    }

    private fun key(entry: McpServerEntry) = "${entry.url}|${entry.headerName}"

    companion object {
        private const val CACHE_MS = 5 * 60_000L
    }
}

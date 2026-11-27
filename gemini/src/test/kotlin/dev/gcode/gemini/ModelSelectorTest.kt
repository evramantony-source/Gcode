package dev.gcode.gemini

import com.google.common.truth.Truth.assertThat
import dev.gcode.gemini.select.ModelCatalog
import dev.gcode.gemini.select.ModelHealth
import dev.gcode.gemini.select.ModelSelector
import dev.gcode.gemini.select.ParsedModelId
import org.junit.Test

class ModelSelectorTest {
    private val health = ModelHealth()
    private val selector = ModelSelector(health)

    @Test
    fun `picker shows every generateContent model and the agent list drops media models`() {
        val catalog = ModelCatalog(Fixtures.fullCatalog)
        assertThat(catalog.chatModels.map { it.id }).doesNotContain("gemini-embedding-2")
        assertThat(catalog.chatModels.map { it.id }).contains("gemma-4-31b-it")
        assertThat(catalog.agentModels.map { it.id }).containsNoneOf("gemini-3.8-flash-tts", "gemini-3.8-live", "gemma-4-31b-it")
    }

    @Test
    fun `newest flash and flash lite are computed from versions, aliases excluded`() {
        val catalog = ModelCatalog(Fixtures.fullCatalog)
        assertThat(catalog.newest(ParsedModelId.Variant.FLASH)).isEqualTo("gemini-3.8-flash")
        assertThat(catalog.newest(ParsedModelId.Variant.FLASH_LITE)).isEqualTo("gemini-3.5-flash-lite")
    }

    @Test
    fun `default model is used when available`() {
        val sel = selector.select(ModelCatalog(Fixtures.fullCatalog), "gemini-4-hydrogen", now = 0)!!
        assertThat(sel.modelId).isEqualTo("gemini-4-hydrogen")
        assertThat(sel.isFallback).isFalse()
    }

    @Test
    fun `falls back to newest flash when the default is missing for the key`() {
        val catalog = ModelCatalog(Fixtures.withoutGemini4)
        assertThat(selector.chain(catalog, "gemini-4-hydrogen")).containsExactly("gemini-3.8-flash", "gemini-3.5-flash-lite").inOrder()
        val sel = selector.select(catalog, "gemini-4-hydrogen", now = 0)!!
        assertThat(sel.modelId).isEqualTo("gemini-3.8-flash")
        assertThat(sel.isFallback).isTrue()
        assertThat(sel.reason).contains("not available")
    }

    @Test
    fun `then flash lite when flash is unavailable, and recovers after the block expires`() {
        val catalog = ModelCatalog(Fixtures.fullCatalog)
        health.markUnavailable("gemini-4-hydrogen", now = 0, reason = "zero quota")
        health.markRateLimited("gemini-3.8-flash", now = 0, cooldownMs = 1_000)
        assertThat(selector.select(catalog, "gemini-4-hydrogen", now = 10)!!.modelId).isEqualTo("gemini-3.5-flash-lite")
        assertThat(selector.select(catalog, "gemini-4-hydrogen", now = 2_000)!!.modelId).isEqualTo("gemini-3.8-flash")
        assertThat(selector.select(catalog, "gemini-4-hydrogen", now = ModelHealth.UNAVAILABLE_MS + 1)!!.modelId)
            .isEqualTo("gemini-4-hydrogen")
    }

    @Test
    fun `switch candidates start with the chain then the newest other models`() {
        val list = selector.switchCandidates(ModelCatalog(Fixtures.fullCatalog), "gemini-4-hydrogen")
        assertThat(list.take(3)).containsExactly("gemini-4-hydrogen", "gemini-3.8-flash", "gemini-3.5-flash-lite").inOrder()
        assertThat(list).contains("gemini-3.6-flash")
        assertThat(list.indexOf("gemini-3.6-flash")).isLessThan(list.indexOf("gemini-3-flash-preview"))
    }

    @Test
    fun `parsing ranks stable before preview at the same version`() {
        val stable = ParsedModelId.parse("gemini-3-flash")!!
        val preview = ParsedModelId.parse("gemini-3-flash-preview")!!
        assertThat(listOf(preview, stable).sorted().first()).isEqualTo(stable)
        assertThat(ParsedModelId.parse("gemini-flash-latest")).isNull()
        assertThat(ParsedModelId.parse("gemini-3.8-flash-tts")).isNull()
    }
}

package dev.gcode.gemini.model

import kotlinx.serialization.json.Json

/** JSON settings for the Gemini API: unknown fields are ignored, nulls are omitted. */
val GeminiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = false
}

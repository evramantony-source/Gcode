package dev.gcode.github.http

import kotlinx.serialization.json.Json

val GitHubJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    coerceInputValues = true
}

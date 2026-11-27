package dev.gcode.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds an OpenAPI-style object schema for `functionDeclarations[].parameters`. */
class SchemaBuilder {
    private val properties = linkedMapOf<String, JsonObject>()
    private val required = mutableListOf<String>()

    private fun prop(name: String, schema: JsonObject, isRequired: Boolean) {
        properties[name] = schema
        if (isRequired) required += name
    }

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) = prop(
        name,
        buildJsonObject {
            put("type", "string")
            put("description", description)
            enum?.let { values -> put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }) }
        },
        required,
    )

    fun integer(name: String, description: String, required: Boolean = false) =
        prop(name, buildJsonObject { put("type", "integer"); put("description", description) }, required)

    fun boolean(name: String, description: String, required: Boolean = false) =
        prop(name, buildJsonObject { put("type", "boolean"); put("description", description) }, required)

    fun stringArray(name: String, description: String, required: Boolean = false) = prop(
        name,
        buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", buildJsonObject { put("type", "string") })
        },
        required,
    )

    fun objectArray(name: String, description: String, required: Boolean = false, item: SchemaBuilder.() -> Unit) = prop(
        name,
        buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", SchemaBuilder().apply(item).build())
        },
        required,
    )

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }
}

fun schema(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build()

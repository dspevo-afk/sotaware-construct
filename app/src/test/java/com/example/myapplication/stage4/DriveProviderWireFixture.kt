package com.example.myapplication.stage4

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The logical fake server stores v3-shaped metadata for its existing listing
 * fixtures. Its conditional endpoint exposes the provider's distinct v2 wire
 * vocabulary. This fixture never calls the production response decoder.
 */
internal fun driveProviderWire(url: String, metadata: String, etag: String): String {
    if (!url.contains("/drive/v2/")) return metadata
    require(etag.startsWith('"') && etag.endsWith('"'))
    val logical = JsonParser.parseString(metadata).asJsonObject
    val wire = JsonObject()
    listOf("id", "mimeType", "headRevisionId", "version").forEach { name ->
        logical[name]?.let { wire.add(name, it.deepCopy()) }
    }
    wire.add("title", logical.get("name").deepCopy())
    logical["modifiedTime"]?.let { wire.add("modifiedDate", it.deepCopy()) }
    logical["size"]?.let { wire.addProperty("fileSize", it.asString) }
    logical["parents"]?.let { parents ->
        wire.add("parents", JsonArray().apply {
            parents.asJsonArray.forEach { add(JsonObject().apply { add("id", it.deepCopy()) }) }
        })
    }
    logical["appProperties"]?.let { properties ->
        wire.add("properties", JsonArray().apply {
            properties.asJsonObject.entrySet().forEach { (key, value) ->
                add(JsonObject().apply {
                    addProperty("key", key)
                    add("value", value.deepCopy())
                    addProperty("visibility", "PRIVATE")
                })
            }
        })
    }
    wire.addProperty("etag", etag)
    return wire.toString()
}

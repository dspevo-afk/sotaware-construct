package com.example.myapplication.stage4

import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.parseBoundedJsonObject
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpContent
import com.google.api.client.http.HttpHeaders
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpResponse
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.MultipartContent
import com.google.api.client.util.DateTime
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.IOException
import java.util.UUID

/** Metadata and its precondition must come from the same provider observation. */
internal data class ConditionalDriveFile(val file: File, val etag: String)

/**
 * The v3 File API neither returns an ETag nor enforces its If-Match header.
 * Only conditional mutations use v2, whose current/stale ETags are qualified
 * against the real provider. This is not a retired document-format reader.
 * Keep the v3 client and its existing generation-bound authorization initializer.
 */
internal class DriveConditionalWrites(private val service: Drive) {
    fun read(id: String): ConditionalDriveFile? {
        val response = configure(service.requestFactory.buildGetRequest(url(id, upload = false))).execute()
        return response.owned {
            if (response.statusCode == 404) null else {
                requireSuccess(response.statusCode)
                parse(id, response.content ?: throw IOException("Drive conditional metadata has no body"))
            }
        }
    }

    fun update(
        id: String,
        etag: String,
        properties: Map<String, String>,
        bytes: ByteArray? = null
    ): ConditionalDriveFile {
        requireEtag(etag)
        require(properties.size <= 100) { "Drive property count exceeds its limit" }
        require(bytes == null || bytes.size <= Stage5Limits.MAX_JSON_BYTES) { "Drive manifest exceeds its limit" }
        val entries = JsonArray()
        properties.toSortedMap().forEach { (key, value) ->
            require(key.matches(PROPERTY_KEY) && value.length <= Stage5Limits.MAX_STRING_CHARS) {
                "Drive private property is invalid"
            }
            entries.add(JsonObject().apply {
                addProperty("key", key)
                addProperty("value", value)
                addProperty("visibility", "PRIVATE")
            })
        }
        val metadata = ByteArrayContent.fromString("application/json; charset=UTF-8",
            JsonObject().apply { add("properties", entries) }.toString())
        val content: HttpContent = if (bytes == null) metadata else MultipartContent()
            .setBoundary("sotaware-" + UUID.randomUUID())
            .setParts(listOf(
                MultipartContent.Part(metadata),
                MultipartContent.Part(ByteArrayContent("application/json", bytes))
            ))
        val endpoint = url(id, upload = bytes != null)
        if (bytes != null) endpoint.set("uploadType", "multipart")
        val request = configure(service.requestFactory.buildRequest("PUT", endpoint, content))
        request.headers.ifMatch = etag
        val response = request.execute()
        return response.owned {
            requireSuccess(response.statusCode)
            parse(id, response.content ?: throw IOException("Drive conditional update has no body"))
        }
    }

    private fun url(id: String, upload: Boolean): GenericUrl {
        require(id.matches(RESOURCE_ID)) { "Drive resource ID is invalid" }
        return GenericUrl(service.rootUrl + (if (upload) "upload/" else "") + "drive/v2/files/" + id)
            .set("supportsAllDrives", true)
            .set("fields", "id,title,mimeType,parents(id),properties(key,value,visibility)," +
                "headRevisionId,modifiedDate,etag,version,fileSize,labels(trashed)")
    }

    private fun configure(request: HttpRequest): HttpRequest = request.apply {
        // A lost conditional response is reconciled by scoped readback in the
        // gateway, never by replaying the write or following authenticated redirects.
        numberOfRetries = 0
        retryOnExecuteIOException = false
        followRedirects = false
        throwExceptionOnExecuteError = false
        connectTimeout = 15_000
        readTimeout = 30_000
    }

    private inline fun <T> HttpResponse.owned(block: () -> T): T {
        var failure: Throwable? = null
        try {
            return block()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                disconnect()
            } catch (closeFailure: Throwable) {
                val primary = failure
                if (primary == null) throw closeFailure
                if (primary !== closeFailure) primary.addSuppressed(closeFailure)
            }
        }
    }

    private fun requireSuccess(status: Int) {
        if (status !in 200..299) {
            // Preserve typed 412 conflict handling without exposing provider bodies,
            // request credentials, URLs or arbitrary response headers in diagnostics.
            throw HttpResponseException.Builder(status, "Drive conditional request failed", HttpHeaders()).build()
        }
    }

    private fun parse(id: String, input: java.io.InputStream): ConditionalDriveFile {
        val json = input.use { parseBoundedJsonObject(it, 256 * 1024, "Drive conditional metadata") }
        fun string(obj: JsonObject, name: String, required: Boolean = true): String? {
            val value = obj[name] ?: if (required) throw IOException("Drive conditional field is missing: $name") else return null
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString ||
                value.asString.length > Stage5Limits.MAX_STRING_CHARS || (required && value.asString.isEmpty())
            ) throw IOException("Drive conditional field is invalid: $name")
            return value.asString
        }
        fun int64(name: String): Long? = string(json, name, required = false)?.let {
            if (!it.matches(DECIMAL)) throw IOException("Drive conditional int64 is invalid")
            it.toLongOrNull() ?: throw IOException("Drive conditional int64 exceeds its limit")
        }
        require(string(json, "id") == id) { "Drive conditional response changed resource ID" }
        val etag = requireNotNull(string(json, "etag"))
        requireEtag(etag)
        val file = File().setId(id).setName(string(json, "title"))
        string(json, "mimeType", required = false)?.let(file::setMimeType)
        string(json, "headRevisionId", required = false)?.let(file::setHeadRevisionId)
        string(json, "modifiedDate", required = false)?.let { file.modifiedTime = DateTime(it) }
        int64("version")?.let(file::setVersion)
        int64("fileSize")?.let(file::setSize)
        val parents = json["parents"]
        if (parents != null) {
            require(parents.isJsonArray && parents.asJsonArray.size() <= 100) { "Drive conditional parents are invalid" }
            val ids = parents.asJsonArray.map { value ->
                require(value.isJsonObject) { "Drive conditional parent is invalid" }
                requireNotNull(string(value.asJsonObject, "id")).also {
                    require(it.matches(RESOURCE_ID)) { "Drive conditional parent ID is invalid" }
                }
            }
            require(ids.distinct().size == ids.size) { "Drive conditional parent is duplicated" }
            file.parents = ids
        }
        val properties = linkedMapOf<String, String>()
        json["properties"]?.let { values ->
            require(values.isJsonArray && values.asJsonArray.size() <= 100) { "Drive conditional properties are invalid" }
            values.asJsonArray.forEach { value ->
                require(value.isJsonObject) { "Drive conditional property is invalid" }
                val property = value.asJsonObject
                val key = requireNotNull(string(property, "key"))
                val visibility = string(property, "visibility")
                val propertyValue = requireNotNull(string(property, "value", required = false))
                require(visibility == "PRIVATE" || visibility == "PUBLIC") { "Drive property visibility is invalid" }
                if (visibility == "PRIVATE") {
                    require(key.matches(PROPERTY_KEY) && properties.put(key, propertyValue) == null) {
                        "Drive private property is invalid or duplicated"
                    }
                }
            }
        }
        file.appProperties = properties
        json["labels"]?.let { labels ->
            require(labels.isJsonObject) { "Drive conditional labels are invalid" }
            labels.asJsonObject["trashed"]?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && !it.asBoolean) {
                    "Drive conditional resource is trashed or has invalid labels"
                }
                file.trashed = false
            }
        }
        return ConditionalDriveFile(file, etag)
    }

    private fun requireEtag(etag: String) {
        // Accept the provider's strong opaque quoted tag only. Never use an
        // inferred revision/time, a wildcard, a weak tag or a header injection.
        require(etag.length in 3..1026 && etag.first() == '"' && etag.last() == '"' &&
            etag.substring(1, etag.lastIndex).all { it.code == 0x21 || it.code in 0x23..0x7e }) {
            "Drive conditional ETag is missing or invalid"
        }
    }

    private companion object {
        val RESOURCE_ID = Regex("[A-Za-z0-9_-]{1,512}")
        val PROPERTY_KEY = Regex("[A-Za-z0-9_.-]{1,128}")
        val DECIMAL = Regex("0|[1-9][0-9]*")
    }
}

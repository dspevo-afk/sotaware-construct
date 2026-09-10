package com.example.myapplication.stage4

import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.api.services.drive.Drive
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Provider-format canaries, not a model produced by the implementation parser. */
class DriveConditionalWritesTest {
    private val wire = """{
      "id":"file-1","title":"annotations.json","mimeType":"application/json",
      "parents":[{"id":"folder-1"}],
      "properties":[{"key":"sotaware_document_id","value":"doc-1","visibility":"PRIVATE"}],
      "etag":"\"issued-r1\"","headRevisionId":"revision-1",
      "modifiedDate":"2026-09-10T04:14:51.198Z","version":"8","fileSize":"24",
      "labels":{"trashed":false}
    }"""

    private class Request(val method: String, url: String) : MockLowLevelHttpRequest(url) {
        val receivedHeaders = linkedMapOf<String, String>()
        override fun addHeader(name: String, value: String) {
            receivedHeaders[name.lowercase()] = value
            super.addHeader(name, value)
        }
        fun body(): String = ByteArrayOutputStream().also { streamingContent?.writeTo(it) }.toString("UTF-8")
    }

    private class Transport(val respond: (Request) -> MockLowLevelHttpResponse) : MockHttpTransport() {
        val requests = mutableListOf<Request>()
        override fun buildRequest(method: String, url: String): LowLevelHttpRequest = object : MockLowLevelHttpRequest(url) {
            private val request = Request(method, url).also { requests += it }
            override fun addHeader(name: String, value: String) { request.addHeader(name, value) }
            override fun execute(): LowLevelHttpResponse {
                request.streamingContent = streamingContent
                request.contentType = contentType
                return respond(request)
            }
        }
    }

    private fun helper(transport: Transport) = DriveConditionalWrites(
        Drive.Builder(transport, GsonFactory.getDefaultInstance(), null)
            .setApplicationName("Stage9B conditional fixture").build()
    )
    private fun response(body: String = wire, status: Int = 200) = MockLowLevelHttpResponse()
        .setStatusCode(status).setContentType("application/json").setContent(body)

    @Test fun providerBodyEtagAndMetadataAreObservedTogetherWithoutV3Header() {
        val transport = Transport { response() }
        val observed = requireNotNull(helper(transport).read("file-1"))
        assertEquals("\"issued-r1\"", observed.etag)
        assertEquals("file-1", observed.file.id)
        assertEquals("annotations.json", observed.file.name)
        assertEquals("revision-1", observed.file.headRevisionId)
        assertEquals(8L, observed.file.version)
        assertEquals(24L, observed.file.getSize())
        assertEquals(listOf("folder-1"), observed.file.parents)
        assertEquals(mapOf("sotaware_document_id" to "doc-1"), observed.file.appProperties)
        assertEquals(1789013691198L, observed.file.modifiedTime.value)
        assertTrue(transport.requests.single().url.startsWith("https://www.googleapis.com/drive/v2/files/file-1?"))
        assertEquals("GET", transport.requests.single().method)
    }

    @Test fun multipartUsesCurrentIssuedEtagAndPublishesPropertiesWithBytes() {
        val transport = Transport { request ->
            assertEquals("PUT", request.method)
            assertTrue(request.url.startsWith("https://www.googleapis.com/upload/drive/v2/files/file-1?"))
            assertTrue(request.url.contains("uploadType=multipart"))
            assertEquals("\"issued-r1\"", request.receivedHeaders["if-match"])
            val body = request.body()
            assertTrue(request.contentType.startsWith("multipart/related; boundary="))
            assertTrue(body.contains("\"key\":\"sotaware_document_id\""))
            assertTrue(body.contains("\"visibility\":\"PRIVATE\""))
            assertTrue(body.contains("{\"snapshot\":\"current\"}"))
            response(wire.replace("issued-r1", "issued-r2"))
        }
        val result = helper(transport).update("file-1", "\"issued-r1\"",
            mapOf("sotaware_document_id" to "doc-1"), "{\"snapshot\":\"current\"}".toByteArray())
        assertEquals("\"issued-r2\"", result.etag)
        assertEquals(1, transport.requests.size)
    }

    @Test fun metadataOnlyUsesConditionalPutWithoutMediaOrParentMutation() {
        val transport = Transport { request ->
            assertEquals("PUT", request.method)
            assertFalse(request.url.contains("upload/"))
            assertFalse(request.url.contains("uploadType"))
            assertEquals("\"issued-r1\"", request.receivedHeaders["if-match"])
            assertEquals("{\"properties\":[{\"key\":\"sotaware_document_id\",\"value\":\"doc-2\",\"visibility\":\"PRIVATE\"}]}", request.body())
            response()
        }
        helper(transport).update("file-1", "\"issued-r1\"", mapOf("sotaware_document_id" to "doc-2"))
        assertEquals(1, transport.requests.size)
    }

    @Test fun stalePreconditionIsTypedAndNeverRetried() {
        val transport = Transport { response("{\"error\":\"private provider text\"}", 412) }
        val error = assertThrows(HttpResponseException::class.java) {
            helper(transport).update("file-1", "\"issued-r1\"", emptyMap(), byteArrayOf(1))
        }
        assertEquals(412, error.statusCode)
        assertFalse(error.message.orEmpty().contains("private provider text"))
        assertEquals(1, transport.requests.size)
    }

    @Test fun redirectCannotForwardConditionalAuthorizationOrReplayWrite() {
        val transport = Transport { response("", 307).addHeader("Location", "https://untrusted.invalid/collect") }
        val error = assertThrows(HttpResponseException::class.java) {
            helper(transport).update("file-1", "\"issued-r1\"", emptyMap())
        }
        assertEquals(307, error.statusCode)
        assertEquals(1, transport.requests.size)
    }

    @Test fun serverFailureIsNotRetriedAsAnUnconditionalWrite() {
        val transport = Transport { response("{}", 500) }
        val error = assertThrows(HttpResponseException::class.java) {
            helper(transport).update("file-1", "\"issued-r1\"", emptyMap())
        }
        assertEquals(500, error.statusCode)
        assertEquals(1, transport.requests.size)
    }

    @Test fun metadata404IsAbsentButUpdate404IsAFailure() {
        val transport = Transport { response("{}", 404) }
        assertNull(helper(transport).read("file-1"))
        assertEquals(404, assertThrows(HttpResponseException::class.java) {
            helper(transport).update("file-1", "\"issued-r1\"", emptyMap())
        }.statusCode)
    }

    @Test fun onlyIssuedStrongQuotedEtagShapeMayReachTransport() {
        val transport = Transport { throw AssertionError("invalid precondition reached network") }
        listOf("", "*", "issued-r1", "W/\"issued-r1\"", "\"\"", "\"a\r\ninjected\"", "\"a\"b\"", "\"" + "a".repeat(1025) + "\"")
            .forEach { value -> assertThrows(IllegalArgumentException::class.java) {
                helper(transport).update("file-1", value, emptyMap())
            } }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun unsafeResourceIdsNeverReachAuthenticatedRequestFactory() {
        val transport = Transport { throw AssertionError("invalid resource ID reached network") }
        listOf("../file", "file?fields=permissions", "file#fragment", "file/id", "", "a".repeat(513))
            .forEach { id -> assertThrows(IllegalArgumentException::class.java) { helper(transport).read(id) } }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun wrongIdentityMissingEtagAndWrongWireTypesAreRejected() {
        val invalid = listOf(
            wire.replace("\"id\":\"file-1\"", "\"id\":\"file-2\""),
            wire.replace("\"etag\":\"\\\"issued-r1\\\"\",", ""),
            wire.replace("\"version\":\"8\"", "\"version\":8"),
            wire.replace("\"fileSize\":\"24\"", "\"fileSize\":\"9223372036854775808\""),
            wire.replace("\"title\":\"annotations.json\"", "\"title\":true"),
            wire.replace("\"trashed\":false", "\"trashed\":true"),
            wire.replace("\"visibility\":\"PRIVATE\"", "\"visibility\":\"UNKNOWN\""),
            wire.replace("\"value\":\"doc-1\"", "\"value\":false"),
            wire.replace("\"parents\":[{\"id\":\"folder-1\"}]", "\"parents\":[\"folder-1\"]"),
            wire.replace("\"id\":\"file-1\"", "\"id\":\"file-1\",\"id\":\"file-1\"")
        )
        invalid.forEach { body ->
            val transport = Transport { response(body) }
            assertThrows(Exception::class.java) { helper(transport).read("file-1") }
            assertEquals(1, transport.requests.size)
        }
    }

    @Test fun privatePropertiesCannotBeShadowedByPublicOrDuplicateEntries() {
        val property = "{\"key\":\"sotaware_document_id\",\"value\":\"doc-1\",\"visibility\":\"PRIVATE\"}"
        val public = property.replace("doc-1", "public-doc").replace("PRIVATE", "PUBLIC")
        val transport = Transport { response(wire.replace(property, "$property,$public")) }
        assertEquals("doc-1", helper(transport).read("file-1")!!.file.appProperties["sotaware_document_id"])
        val duplicate = Transport { response(wire.replace(property, "$property,$property")) }
        assertThrows(Exception::class.java) { helper(duplicate).read("file-1") }
    }

    @Test fun oversizedMalformedAndTruncatedUtf8ResponsesCloseTheirResources() {
        val bodies = listOf((" " .repeat(256 * 1024) + wire).toByteArray(),
            (wire + "{}").toByteArray(), byteArrayOf(0xc3.toByte(), 0x28))
        bodies.forEach { bytes ->
            var closed = false
            var disconnected = false
            val transport = Transport {
                object : MockLowLevelHttpResponse() {
                    override fun disconnect() { disconnected = true; super.disconnect() }
                }.setStatusCode(200).setContentType("application/json").setContent(
                    object : ByteArrayInputStream(bytes) {
                        override fun close() { closed = true; super.close() }
                    })
            }
            assertThrows(Exception::class.java) { helper(transport).read("file-1") }
            assertTrue(closed)
            assertTrue(disconnected)
        }
    }

    @Test fun disconnectFailureCannotReplaceAnExplicitConflict() {
        val transport = Transport {
            object : MockLowLevelHttpResponse() {
                override fun disconnect() { throw IOException("synthetic response close failure") }
            }.setStatusCode(412).setContent("{}")
        }
        val error = assertThrows(HttpResponseException::class.java) {
            helper(transport).update("file-1", "\"issued-r1\"", emptyMap())
        }
        assertEquals(412, error.statusCode)
        assertEquals(1, error.suppressed.size)
        assertEquals(1, transport.requests.size)
    }
}

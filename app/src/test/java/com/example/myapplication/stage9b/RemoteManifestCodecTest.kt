package com.example.myapplication.stage9b

import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.SyncScope
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** Strict current-format manifest boundary tests; no legacy migration is used. */
class RemoteManifestCodecTest {
    private val scope = SyncScope("account", "root", DocumentId.new())
    private val snapshot = DocumentSnapshotV1(
        DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
        1L,
        DocumentSourceIdentityV1("content://provider/plan", "plan.pdf"),
        mapOf(0 to PageSnapshotV1())
    )

    @Test
    fun encodeDecodeRoundTripUsesCanonicalBytesWithoutInlineContent() {
        val bytes = RemoteManifestCodec.encode(scope, "plan.pdf", snapshot, emptyMap())
        val decoded = RemoteManifestCodec.decode(bytes, scope)
        assertEquals(snapshot, decoded.manifest.snapshot)
        assertEquals(RemoteManifestCodec.snapshotDigest(snapshot), decoded.manifest.snapshotDigest)
        assertEquals(bytes.toList(), decoded.canonicalBytes.toList())
        assertFalse(String(bytes, Charsets.UTF_8).contains("base64", ignoreCase = true))
    }

    @Test
    fun unknownDuplicateAndRetiredVersionsFailBeforeMaterialization() {
        val encoded = RemoteManifestCodec.encode(scope, "plan.pdf", snapshot, emptyMap())
        val unknown = JsonParser.parseString(String(encoded, Charsets.UTF_8)).asJsonObject
            .apply { addProperty("unexpected", true) }
        assertThrows(RemoteManifestValidationException::class.java) {
            RemoteManifestCodec.decode(unknown.toString().toByteArray(), scope)
        }

        val duplicate = String(encoded, Charsets.UTF_8)
            .replaceFirst("\"manifestVersion\":3,", "\"manifestVersion\":3,\"manifestVersion\":3,")
        assertThrows(RemoteManifestValidationException::class.java) {
            RemoteManifestCodec.decode(duplicate.toByteArray(), scope)
        }

        val retired = JsonParser.parseString(String(encoded, Charsets.UTF_8)).asJsonObject
            .apply { addProperty("manifestVersion", 2) }
        assertThrows(RemoteManifestValidationException::class.java) {
            RemoteManifestCodec.decode(retired.toString().toByteArray(), scope)
        }
    }

    @Test
    fun snapshotDigestAndScopeAreBoundToTheRequestedDocument() {
        val encoded = RemoteManifestCodec.encode(scope, "plan.pdf", snapshot, emptyMap())
        val badDigest = JsonParser.parseString(String(encoded, Charsets.UTF_8)).asJsonObject
            .apply { addProperty("snapshotDigest", "0".repeat(64)) }
        assertThrows(RemoteManifestValidationException::class.java) {
            RemoteManifestCodec.decode(badDigest.toString().toByteArray(), scope)
        }
        val otherScope = SyncScope(scope.accountId, scope.backupRootId, DocumentId.new())
        assertThrows(RemoteManifestValidationException::class.java) {
            RemoteManifestCodec.decode(encoded, otherScope)
        }
    }

    @Test
    fun retiredDescriptorAliasAndMissingAssetsAreRejectedWithoutChangingInput() {
        val encoded = RemoteManifestCodec.encode(scope, "plan.pdf", snapshot, emptyMap())
        for (variant in listOf("alias-only", "both", "neither")) {
            val tree = JsonParser.parseString(encoded.toString(Charsets.UTF_8)).asJsonObject
            if (variant != "neither") tree.add("photoDescriptors", tree.get("assets").deepCopy())
            if (variant != "both") tree.remove("assets")
            val input = tree.toString().toByteArray(Charsets.UTF_8)
            val original = input.copyOf()
            assertThrows(RemoteManifestValidationException::class.java) {
                RemoteManifestCodec.decode(input, scope)
            }
            org.junit.Assert.assertArrayEquals(original, input)
        }
    }

    @Test
    fun fingerprintAlgorithmMustBeTheSupportedWireAlgorithm() {
        val fingerprint = com.example.myapplication.stage2.SourceFingerprint.fromBytes("source".toByteArray())
        val encoded = RemoteManifestCodec.encode(scope, "plan.pdf", snapshot, emptyMap(), fingerprint)
        assertEquals(fingerprint, RemoteManifestCodec.decode(encoded, scope, fingerprint).manifest.sourceFingerprint)
        for (algorithm in listOf("md5", "SHA-1", "sha-256", "")) {
            val tree = JsonParser.parseString(encoded.toString(Charsets.UTF_8)).asJsonObject
            tree.addProperty("sourceFingerprint", "$algorithm:${fingerprint.digestHex}:${fingerprint.byteCount}")
            val input = tree.toString().toByteArray(Charsets.UTF_8)
            val original = input.copyOf()
            assertThrows(RemoteManifestValidationException::class.java) {
                RemoteManifestCodec.decode(input, scope, fingerprint)
            }
            org.junit.Assert.assertArrayEquals(original, input)
        }
    }

    @Test
    fun fractionalGeometryRoundTripsWithoutBeingMistakenForUnknownFields() {
        for (coordinate in listOf(.1f, .2f, .33333334f, .99999994f)) {
            val populated = snapshot.copy(pages = mapOf(0 to PageSnapshotV1(
                notes = listOf(com.example.myapplication.stage1.NoteSnapshotV1(
                    coordinate, .2f, "fractional geometry", false, 13.7f, .05f, "fraction-note"
                ))
            )))
            val encoded = RemoteManifestCodec.encode(scope, "plan.pdf", populated, emptyMap())
            val decoded = RemoteManifestCodec.decode(encoded, scope)
            assertEquals(populated, decoded.manifest.snapshot)
            org.junit.Assert.assertArrayEquals(encoded, decoded.canonicalBytes)
            val unknown = JsonParser.parseString(encoded.toString(Charsets.UTF_8)).asJsonObject
            unknown.getAsJsonObject("snapshot").getAsJsonObject("pages").getAsJsonObject("0")
                .getAsJsonArray("notes")[0].asJsonObject.addProperty("retiredAbsoluteFontSize", 42)
            val rejected = unknown.toString().toByteArray(Charsets.UTF_8)
            val original = rejected.copyOf()
            assertThrows(RemoteManifestValidationException::class.java) {
                RemoteManifestCodec.decode(rejected, scope)
            }
            org.junit.Assert.assertArrayEquals(original, rejected)
        }
    }
}

package com.example.myapplication.stage9b

import com.example.myapplication.stage5.validatePhotoBytes
import java.io.InputStream

/** Explicit immutable synthetic source for JVM fixtures, never production capture. */
fun testPhotoAssets(input: Map<String, ByteArray>): PhotoAssetSet = PhotoAssetSet.of(
    input.mapValues { (_, bytes) ->
        val owned = bytes.copyOf()
        val verified = validatePhotoBytes(owned)
        object : PhotoAsset {
            override val descriptor = verified.descriptor
            override fun open(): InputStream = owned.inputStream()
        }
    }
)

/** Byte assertions may materialize synthetic assets; production callers must stream. */
fun PhotoAsset.readTestBytes(): ByteArray = open().use { it.readBytes() }

fun PhotoAssetSet.readTestBytes(): Map<String, ByteArray> = mapValues { it.value.readTestBytes() }

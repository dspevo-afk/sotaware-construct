package com.example.myapplication.stage5

import java.io.File
import java.util.UUID

/**
 * Isolated app-private storage for camera handoff files. This is the only
 * files-directory root exposed through the app's FileProvider.
 */
class CameraCaptureStore(
    filesDirectory: File
) : AutoCloseable {
    private val resolver = PhotoPathResolver(
        rootDirectory = File(filesDirectory, CameraCaptureFilePolicy.ROOT_DIRECTORY),
        createRoot = true,
        trustedRootDirectory = filesDirectory
    )

    fun newCaptureFile(): File {
        val file = resolver.root.resolve(
            "${CameraCaptureFilePolicy.FILE_PREFIX}${UUID.randomUUID()}${CameraCaptureFilePolicy.FILE_EXTENSION}"
        )
        resolver.openNewOutput(file.toPath(), "camera capture").use { it.force(true) }
        return file
    }

    fun discardCaptureFile(file: File) {
        require(CameraCaptureFilePolicy.isOwnedCaptureFileName(file.name)) {
            "unsafe camera capture file"
        }
        resolver.deletePath(file.toPath(), "camera capture")
    }

    override fun close() {
        resolver.close()
    }
}

/** Small pure policy seam shared by capture storage and configuration tests. */
object CameraCaptureFilePolicy {
    const val ROOT_DIRECTORY = "camera_captures"
    const val FILE_PREFIX = ".camera-capture-"
    const val FILE_EXTENSION = ".tmp"

    fun isOwnedCaptureFileName(name: String): Boolean =
        name.matches(
            Regex("\\.camera-capture-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.tmp")
        )
}

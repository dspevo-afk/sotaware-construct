package com.example.myapplication.stage4

import com.example.myapplication.stage0.HighResolutionPhonePhotoFixture
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Strict Stage 5 media validation requires real deterministic image bytes. */
internal object Stage4PhotoFixture {
    private val jpeg: ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        HighResolutionPhonePhotoFixture.jpegBytes()
    }
    private val previousJpeg: ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        smallJpeg(Color(25, 70, 125), Color(198, 218, 175))
    }
    private val incomingJpeg: ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        smallJpeg(Color(128, 42, 32), Color(236, 190, 72))
    }

    fun jpegBytes(): ByteArray = jpeg.copyOf()

    /** Distinct, decodable bytes for the old side of a durable photo tuple. */
    fun previousJpegBytes(): ByteArray = previousJpeg.copyOf()

    /** Distinct, decodable bytes for the incoming side of a durable photo tuple. */
    fun incomingJpegBytes(): ByteArray = incomingJpeg.copyOf()

    private fun smallJpeg(top: Color, bottom: Color): ByteArray {
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) {
            val fraction = y.toFloat() / (image.height - 1).toFloat()
            val red = (top.red + (bottom.red - top.red) * fraction).toInt()
            val green = (top.green + (bottom.green - top.green) * fraction).toInt()
            val blue = (top.blue + (bottom.blue - top.blue) * fraction).toInt()
            val rgb = Color(red, green, blue).rgb
            for (x in 0 until image.width) image.setRGB(x, y, rgb)
        }
        val output = ByteArrayOutputStream()
        check(ImageIO.write(image, "jpeg", output)) { "JVM JPEG writer is required" }
        return output.toByteArray()
    }
}

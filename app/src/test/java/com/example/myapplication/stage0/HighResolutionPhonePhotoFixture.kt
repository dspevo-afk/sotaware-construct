package com.example.myapplication.stage0

import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** JVM-only deterministic substitute for a committed multi-megapixel phone-photo binary. */
internal object HighResolutionPhonePhotoFixture {
    const val WIDTH = 4032
    const val HEIGHT = 3024

    fun jpegBytes(): ByteArray {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            graphics.paint = GradientPaint(0f, 0f, Color(28, 78, 121), 0f, HEIGHT.toFloat(), Color(214, 226, 184))
            graphics.fillRect(0, 0, WIDTH, HEIGHT)

            // Fixed geometry gives the generated fixture a photo-like scene while remaining reproducible.
            graphics.color = Color(232, 192, 101)
            graphics.fillOval(2940, 310, 410, 410)
            graphics.color = Color(70, 87, 68)
            graphics.fillRect(0, 2090, WIDTH, 934)
            graphics.color = Color(122, 93, 67)
            graphics.fillPolygon(
                intArrayOf(720, 1860, 3110),
                intArrayOf(2090, 1080, 2090),
                3
            )
            graphics.color = Color(185, 181, 167)
            graphics.fillRect(1700, 1590, 720, 500)
            graphics.color = Color(42, 52, 57)
            for (column in 0 until 6) {
                for (row in 0 until 3) {
                    graphics.fillRect(1760 + column * 108, 1650 + row * 126, 62, 74)
                }
            }
            graphics.color = Color(255, 255, 255, 170)
            graphics.drawString("SOTAWARE STAGE 0 PHONE PHOTO", 80, 2860)
        } finally {
            graphics.dispose()
        }

        val output = ByteArrayOutputStream()
        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        check(writers.hasNext()) { "JVM JPEG writer is required for the deterministic fixture" }
        val writer = writers.next()
        try {
            ImageIO.createImageOutputStream(output).use { imageOutput ->
                writer.output = imageOutput
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.82f
                }
                writer.write(null, javax.imageio.IIOImage(image, null, null), params)
            }
        } finally {
            writer.dispose()
        }
        return output.toByteArray()
    }
}

package app.still.gallery.similarity

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

/** Declared calibration only. S36's independently frozen scenes are never used for tuning. */
class SimilarityCalibrationTest {
    @Test fun declaredSceneReencodingResizeAndMildLightingAreRetrieved() {
        val original = scene(128, 0)
        val base = feature(128, original)
        val jpeg = ByteArrayOutputStream()
        val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, 128, 128, original, 0, 128)
        assertTrue(ImageIO.write(image, "jpeg", jpeg))
        val decoded = ImageIO.read(ByteArrayInputStream(jpeg.toByteArray()))
        val reencoded = decoded.getRGB(0, 0, 128, 128, null, 0, 128)
        assertNotNull("Declared JPEG positive", similarityDistance(base, feature(128, reencoded)))
        assertNotNull("Declared resized positive", similarityDistance(base, feature(192, scene(192, 0))))
        // Declared v2 neutral-exposure positives, independent of S36's unseen fixtures.
        for (exposure in listOf(-15, 13, 28)) {
            val adjusted = original.map { value ->
                val r = (((value ushr 16) and 255) + exposure).coerceIn(0, 255)
                val g = (((value ushr 8) and 255) + exposure).coerceIn(0, 255)
                val b = ((value and 255) + exposure).coerceIn(0, 255)
                (255 shl 24) or (r shl 16) or (g shl 8) or b
            }.toIntArray()
            assertNotNull("Declared neutral exposure $exposure positive", similarityDistance(base, feature(128, adjusted)))
        }
    }
    @Test fun declaredDifferentLayoutAndDifferentColorAreNotRetrieved() {
        val base = feature(128, scene(128, 0))
        assertNull(similarityDistance(base, feature(128, scene(128, 1))))
        val recolored = scene(128, 0).map { value -> (value and -0x1000000) or ((value and 255) shl 16) or (value and 0xff00) or ((value ushr 16) and 255) }.toIntArray()
        assertNull(similarityDistance(base, feature(128, recolored)))
        val original = scene(128, 0)
        val narrow = (similarityFeature(64, 128, IntArray(64 * 128) { original[it] }) as SimilarityPixelResult.Accepted).feature
        assertNull("Declared aspect negative", similarityDistance(base, narrow))
    }
    @Test fun declaredFlatPlaneIsNotAnAppearanceDescriptor() {
        val rejected = similarityFeature(128, 128, IntArray(128 * 128) { -0x777778 })
        assertTrue(rejected is SimilarityPixelResult.Rejected)
        assertEquals(SimilarityPixelSkip.LOW_INFORMATION, (rejected as SimilarityPixelResult.Rejected).reason)
    }
    private fun feature(size: Int, pixels: IntArray) = (similarityFeature(size, size, pixels) as SimilarityPixelResult.Accepted).feature
    private fun scene(size: Int, variant: Int): IntArray = IntArray(size * size) { index ->
        val x = index % size * 128 / size; val y = index / size * 128 / size
        val red: Int; val green: Int; val blue: Int
        if (variant == 0) {
            if (x in 20..57 && y in 39..101) { red = 195 + y / 8; green = 71 + x / 6; blue = 48 + y / 12 }
            else if ((x - 92) * (x - 92) + (y - 34) * (y - 34) < 19 * 19) { red = 225; green = 178; blue = 65 }
            else { red = 48 + y / 3; green = 95 + x / 4; blue = 155 + y / 4 }
        } else {
            if (x + y in 80..130) { red = 45; green = 190; blue = 70 }
            else { red = 165 + x / 4; green = 62 + y / 3; blue = 135 + x / 4 }
        }
        (255 shl 24) or (red shl 16) or (green shl 8) or blue
    }
}

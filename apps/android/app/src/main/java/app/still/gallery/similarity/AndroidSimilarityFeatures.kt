package app.still.gallery.similarity

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/** Trusted decoder seam; one captured byte array, no source reopen, files, caches or escaping pixels. */
fun interface SimilarityFeatureDecoder {
    fun decode(encoded: ByteArray, length: Int, checkCurrent: () -> Unit): SimilarityPixelResult
}

class AndroidSimilarityFeatures : SimilarityFeatureDecoder {
    override fun decode(encoded: ByteArray, length: Int, checkCurrent: () -> Unit): SimilarityPixelResult {
        require(length in 1..SimilarityBounds.MAX_INPUT_BYTES && length <= encoded.size && encoded.size <= SimilarityBounds.MAX_ENCODED_BUFFERS)
        var bitmap: Bitmap? = null
        try {
            checkCurrent()
            if (!similarityContainerSupported(encoded, length)) return SimilarityPixelResult.Rejected(SimilarityPixelSkip.UNSUPPORTED)
            val exif = ByteArrayInputStream(encoded, 0, length).use { ExifInterface(it) }
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            checkCurrent()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
            BitmapFactory.decodeByteArray(encoded, 0, length, bounds)
            val sample = try { similaritySampleSize(bounds.outWidth, bounds.outHeight) }
                catch (_: IllegalArgumentException) { return SimilarityPixelResult.Rejected(SimilarityPixelSkip.TINY) }
            // These containers may carry animation/sequences; omit rather than silently analyze frame zero.
            if (bounds.outMimeType !in setOf("image/jpeg", "image/png")) return SimilarityPixelResult.Rejected(SimilarityPixelSkip.UNSUPPORTED)
            checkCurrent()
            bitmap = BitmapFactory.decodeByteArray(encoded, 0, length, BitmapFactory.Options().apply {
                inScaled = false; inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: return SimilarityPixelResult.Rejected(SimilarityPixelSkip.INVALID)
            if (bitmap.config != Bitmap.Config.ARGB_8888 || !similarityBitmapFits(bitmap.width, bitmap.height, bitmap.allocationByteCount.toLong()))
                return SimilarityPixelResult.Rejected(SimilarityPixelSkip.INVALID)
            checkCurrent()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            checkCurrent()
            return similarityFeature(bitmap.width, bitmap.height, pixels, orientation).also { checkCurrent() }
        } catch (_: OutOfMemoryError) {
            return SimilarityPixelResult.Rejected(SimilarityPixelSkip.INVALID)
        } finally { bitmap?.recycle() }
    }
}

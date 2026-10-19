package app.still.gallery.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import java.io.InputStream

/** No cache. Closing drops this owner's reference; published bitmaps are left to Android/GC. */
class ComparePreview(bitmap: Bitmap) : AutoCloseable {
    var bitmap: Bitmap? = bitmap
        private set
    override fun close() { bitmap = null }
}

/** The opener can read ONLY one exact prepared member under its current task ticket. */
fun decodeComparePreview(openPrepared: () -> InputStream, checkCurrent: () -> Unit): ComparePreview {
    var decoded: Bitmap? = null
    var oriented: Bitmap? = null
    try {
        checkCurrent()
        val orientation = openPrepared().use { stream ->
            compareOrientation(ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        openPrepared().use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = compareSampleSize(bounds.outWidth, bounds.outHeight)
        checkCurrent()
        decoded = openPrepared().use { stream -> BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) } ?: throw IOException("Preview decoding is unavailable")
        check(decoded.config == Bitmap.Config.ARGB_8888) { "Unsupported preview allocation" }
        check(comparePreviewFits(decoded.width, decoded.height, decoded.allocationByteCount.toLong())) { "Preview exceeds its bounds" }
        checkCurrent()
        val matrix = Matrix().apply {
            setValues(floatArrayOf(orientation.xx.toFloat(), orientation.xy.toFloat(), 0f,
                orientation.yx.toFloat(), orientation.yy.toFloat(), 0f, 0f, 0f, 1f))
        }
        oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        check(comparePreviewFits(oriented.width, oriented.height, oriented.allocationByteCount.toLong())) { "Oriented preview exceeds its bounds" }
        checkCurrent()
        if (decoded !== oriented) decoded.recycle()
        decoded = null
        return ComparePreview(oriented).also { oriented = null }
    } catch (failure: OutOfMemoryError) {
        throw IOException("Preview allocation is unavailable", failure)
    } finally {
        if (decoded !== oriented) decoded?.recycle()
        oriented?.recycle()
    }
}

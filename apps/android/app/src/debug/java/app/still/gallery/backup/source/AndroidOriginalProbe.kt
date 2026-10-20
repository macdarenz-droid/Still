package app.still.gallery.backup.source

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Debug-only and no production callers. Cannot accept a URI, source handle or caller bytes. */
class AndroidOriginalProbe(context: Context) {
    private val application = context.applicationContext
    private val engine = OriginalProbeEngine()
    fun startGenerated(seed: Int = 0x57A11): OriginalProbeAttempt = engine.start(GeneratedMediaStoreSource(application, seed))
    /** Off-main-thread maintenance. A recreated controller settles the actual retained owner. */
    fun settle(): OriginalProbeCleanup = engine.settle()
    override fun toString() = "AndroidOriginalProbe(generatedFixtureOnly)"
}

private class GeneratedMediaStoreSource(private val context: Context, private val seed: Int) : OriginalProbeSource {
    private val resolver = context.contentResolver
    private val cancellation = CancellationSignal()
    private var fixture: Uri? = null
    override val supported get() = Build.VERSION.SDK_INT >= 29
    override fun locationGranted() = supported && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
    override fun prepare(resources: ProbeResources): ProbeExpected {
        if (Build.VERSION.SDK_INT < 29) throw ProbeFault(OriginalProbeFailure.UNSUPPORTED_API)
        val bytes = generatedJpeg(seed)
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Still-generated-original-probe.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val inserted = resolver.insert(collection, values) ?: throw IOException()
        fixture = inserted // Cleanup owns the exact insert receipt even if validation fails.
        // Never accept an arbitrary provider path, enumerate media, or publish this pending row.
        val row = runCatching { ContentUris.parseId(inserted) }.getOrNull()
        if (row == null || row < 0 || inserted != ContentUris.withAppendedId(collection, row)) throw IOException()
        val descriptor = resolver.openFileDescriptor(inserted, "w", cancellation) ?: throw IOException()
        var output: ParcelFileDescriptor.AutoCloseOutputStream? = null
        val owned = resources.own { val stream = output; if (stream == null) descriptor.close() else stream.close() }
        try {
            val stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor).also { output = it }
            stream.write(bytes)
            stream.flush()
            descriptor.fileDescriptor.sync()
        } finally { owned.close() }
        return ProbeExpected(bytes.size.toLong(), sha256(bytes))
    }
    override fun openOriginal(resources: ProbeResources): ProbeInput {
        if (Build.VERSION.SDK_INT < 29) throw ProbeFault(OriginalProbeFailure.UNSUPPORTED_API)
        if (!locationGranted()) throw ProbeFault(OriginalProbeFailure.LOCATION_PERMISSION_REQUIRED)
        val row = fixture ?: throw IOException()
        val original = MediaStore.setRequireOriginal(row)
        val descriptor = resolver.openFileDescriptor(original, "r", cancellation) ?: throw IOException()
        var input: ParcelFileDescriptor.AutoCloseInputStream? = null
        val owned = resources.own { val stream = input; if (stream == null) descriptor.close() else stream.close() }
        val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor).also { input = it }
        return ProbeInput(stream, owned)
    }
    override fun abort() { cancellation.cancel() }
    override fun cleanup() {
        val row = fixture ?: return
        val count = resolver.delete(row, null, null)
        if (count !in 0..1) throw IOException()
        // A delete acknowledgement alone is not verification that the generated row is gone.
        resolver.query(row, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { cursor ->
            if (cursor.count != 0) throw IOException()
        } ?: throw IOException()
        fixture = null
    }
    override fun toString() = "GeneratedMediaStoreSource(redacted)"
}

/** A small generated JPEG with artificial GPS; no library bytes or real coordinates enter. */
private fun generatedJpeg(seed: Int): ByteArray {
    val bitmap = createBitmap(64, 64)
    val jpeg = try {
        for (y in 0 until 64) for (x in 0 until 64) {
            val red = (x * 3 + seed) and 255; val green = (y * 3 + (seed ushr 8)) and 255; val blue = (x + y + (seed ushr 16)) and 255
            bitmap[x, y] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
        }
        ByteArrayOutputStream().use { out -> if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) throw IOException(); out.toByteArray() }
    } finally { bitmap.recycle() }
    if (jpeg.size !in 4..65_536 || jpeg[0] != 0xff.toByte() || jpeg[1] != 0xd8.toByte()) throw IOException()
    // Minimal little-endian TIFF: IFD0 -> GPS IFD, with fixed artificial N/E coordinates.
    val tiff = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
    tiff.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
    tiff.putShort(1).putShort(0x8825.toShort()).putShort(4).putInt(1).putInt(26).putInt(0)
    tiff.putShort(4)
    tiff.putShort(1).putShort(2).putInt(2).putInt('N'.code)
    tiff.putShort(2).putShort(5).putInt(3).putInt(80)
    tiff.putShort(3).putShort(2).putInt(2).putInt('E'.code)
    tiff.putShort(4).putShort(5).putInt(3).putInt(104).putInt(0)
    for (value in intArrayOf(1, 0, 0, 2, 0, 0)) tiff.putInt(value).putInt(1)
    val exif = byteArrayOf(69, 120, 105, 102, 0, 0) + tiff.array()
    val size = exif.size + 2
    val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), (size ushr 8).toByte(), size.toByte()) + exif + jpeg.copyOfRange(2, jpeg.size)
    if (ExifInterface(ByteArrayInputStream(bytes)).latLong == null) throw IOException()
    return bytes
}
private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

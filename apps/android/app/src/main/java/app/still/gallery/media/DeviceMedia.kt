package app.still.gallery.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

fun Context.mediaAccess(): MediaAccess {
    fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    return MediaAccess(
        sdk = Build.VERSION.SDK_INT,
        legacy = Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE),
        images = Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES),
        videos = Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_VIDEO),
        selected = Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
    )
}

fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

class DeviceMedia(private val context: Context) {
    private val resolver = context.contentResolver
    private val epoch = AccessEpoch()
    // Thumbnails are transient. Revoking access clears this cache before the next query.
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun clearThumbnails() { epoch.invalidate { cache.evictAll() } }

    suspend fun read(access: MediaAccess): List<MediaItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<MediaItem>()
        if (access.canReadImages) result += query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
        if (access.canReadVideos) result += query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        result.sortedByDescending { it.capturedAt }
    }

    private suspend fun query(collection: Uri, video: Boolean): List<MediaItem> {
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.Images.ImageColumns.DATE_TAKEN,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Images.ImageColumns.BUCKET_ID,
            MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
        )
        if (video) projection += MediaStore.Video.VideoColumns.DURATION
        else projection += MediaStore.Images.ImageColumns.ORIENTATION
        val items = mutableListOf<MediaItem>()
        resolver.query(collection, projection.toTypedArray(), null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            fun number(column: String): Long {
                val index = cursor.getColumnIndex(column)
                return if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L
            }
            fun text(column: String): String {
                val index = cursor.getColumnIndex(column)
                return if (index >= 0 && !cursor.isNull(index)) cursor.getString(index).orEmpty() else ""
            }
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val id = number(MediaStore.MediaColumns._ID)
                val date = number(MediaStore.Images.ImageColumns.DATE_TAKEN)
                    .takeIf { it > 0 } ?: (number(MediaStore.MediaColumns.DATE_ADDED) * 1000)
                items += MediaItem(
                    id = ContentUris.withAppendedId(collection, id).toString(),
                    name = text(MediaStore.MediaColumns.DISPLAY_NAME),
                    mimeType = text(MediaStore.MediaColumns.MIME_TYPE),
                    isVideo = video,
                    capturedAt = date,
                    width = number(MediaStore.MediaColumns.WIDTH).toInt(),
                    height = number(MediaStore.MediaColumns.HEIGHT).toInt(),
                    bytes = number(MediaStore.MediaColumns.SIZE),
                    durationMs = if (video) number(MediaStore.Video.VideoColumns.DURATION) else 0L,
                    albumId = text(MediaStore.Images.ImageColumns.BUCKET_ID),
                    albumName = text(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME),
                    orientationDegrees = if (video) 0 else number(MediaStore.Images.ImageColumns.ORIENTATION).toInt(),
                )
            }
        }
        return items
    }

    suspend fun thumbnail(item: MediaItem, edge: Int): Bitmap? = withContext(Dispatchers.IO) {
        val token = epoch.snapshot()
        val key = "${item.id}:$edge"
        cache.get(key)?.let { return@withContext it }
        val uri = item.id.toUri()
        try {
            val bitmap = when {
                Build.VERSION.SDK_INT >= 29 && edge <= 512 -> resolver.loadThumbnail(uri, Size(edge, edge), null)
                item.isVideo && Build.VERSION.SDK_INT >= 27 -> {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, edge, edge)
                    } finally { retriever.release() }
                }
                item.isVideo -> {
                    // API 26 has no scaled frame extractor; its provider mini thumbnail bounds memory.
                    MediaStore.Video.Thumbnails.getThumbnail(resolver, ContentUris.parseId(uri), MediaStore.Video.Thumbnails.MINI_KIND, null)
                }
                Build.VERSION.SDK_INT >= 28 -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    val ratio = (edge.toFloat() / maxOf(info.size.width, info.size.height)).coerceAtMost(1f)
                    decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
                }
                else -> {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (bounds.outWidth / sample > edge * 2 || bounds.outHeight / sample > edge * 2) sample *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }?.let { source ->
                        if (item.orientationDegrees == 0) source
                        else Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(item.orientationDegrees.toFloat()) }, true).also { if (it !== source) source.recycle() }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (epoch.commitIfCurrent(token) { if (bitmap != null) cache.put(key, bitmap) }) bitmap else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: RuntimeException) {
            // Some document/media providers throw runtime exceptions for missing or invalid content.
            null
        }
    }
}

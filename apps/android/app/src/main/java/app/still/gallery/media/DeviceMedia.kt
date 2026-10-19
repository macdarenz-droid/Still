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
import app.still.gallery.catalogue.CatalogueNamespace
import app.still.gallery.catalogue.CatalogueObservation
import app.still.gallery.catalogue.LocalCatalogue
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
    private val scanEpoch = AccessEpoch()
    private var activeCatalogue: LocalCatalogue? = null
    // Thumbnails are transient. Revoking access clears this cache before the next query.
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun clearThumbnails() { epoch.invalidate { cache.evictAll() } }

    /** No database work: immediately fence queries/index publication on refresh or lock disposal. */
    fun invalidateScans() { scanEpoch.invalidate { activeCatalogue?.invalidateScans() } }

    /**
     * Publish fresh authorized rows before SQLite work. The catalogue is never a
     * browsing fallback. Every database operation, including construction/close,
     * runs on IO, and its token starts before the provider snapshot is read.
     */
    suspend fun readAndIndex(access: MediaAccess, favoriteReferences: Set<String>, onLoaded: (List<MediaItem>, Map<String, String>) -> Unit): Boolean {
        val expected = scanEpoch.snapshot()
        return withContext(Dispatchers.IO) {
            var catalogue: LocalCatalogue? = null
            var indexed = false
            try {
                catalogue = try { LocalCatalogue(context) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                val database = catalogue
                var scan: app.still.gallery.catalogue.CatalogueScan? = null
                if (!scanEpoch.commitIfCurrent(expected) {
                    activeCatalogue?.invalidateScans()
                    activeCatalogue = database
                    scan = database?.beginScan()
                }) throw CancellationException("Library refresh was invalidated")
                val snapshot = read(access, favoriteReferences)
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main) {
                    if (!scanEpoch.commitIfCurrent(expected) { onLoaded(snapshot.items, snapshot.legacyFavoriteMatches) }) {
                        throw CancellationException("Library refresh was invalidated")
                    }
                }
                if (database != null && scan != null) {
                    indexed = try {
                        val jobContext = currentCoroutineContext()
                        database.reconcile(requireNotNull(scan), snapshot.items.map { it.catalogueObservation() }, snapshot.namespaces) {
                            jobContext.ensureActive()
                        }
                        true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                }
                currentCoroutineContext().ensureActive()
                if (!scanEpoch.commitIfCurrent(expected) { }) throw CancellationException("Library refresh was invalidated")
            } finally {
                catalogue?.invalidateScans()
                scanEpoch.commitIfCurrent(expected) { if (activeCatalogue === catalogue) activeCatalogue = null }
                try { catalogue?.close() } catch (_: Exception) { indexed = false }
            }
            indexed
        }
    }

    private suspend fun read(access: MediaAccess, favoriteReferences: Set<String>): DeviceMediaSnapshot = withContext(Dispatchers.IO) {
        val result = mutableListOf<MediaItem>()
        val volumes = if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(context).sorted() else listOf("external")
        val before = volumes.map { currentCoroutineContext().ensureActive(); namespace(it) }
        for (provider in before) {
            currentCoroutineContext().ensureActive()
            val volume = provider.volumeName
            val images = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(volume) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val videos = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            if (access.canReadImages) result += query(images, false, provider)
            if (access.canReadVideos) result += query(videos, true, provider)
        }
        // Exact legacy-URI proofs stay within the same reset/cancellation envelope.
        val aliases = resolveLegacyFavorites(favoriteReferences, result)
        // Recheck every namespace AFTER the whole enumeration, including empty
        // volumes. A reset of the first volume during the last query is fenced.
        val namespaces = volumes.map { currentCoroutineContext().ensureActive(); namespace(it) }
        before.zip(namespaces).forEach { (first, last) ->
            if (!providerReadRemainedCurrent(first.providerVersion, first.providerGeneration, last.providerVersion, last.providerGeneration)) {
                throw IllegalStateException("Media provider changed during the read")
            }
        }
        currentCoroutineContext().ensureActive()
        if (Build.VERSION.SDK_INT >= 29 && MediaStore.getExternalVolumeNames(context).sorted() != volumes) {
            throw IllegalStateException("Media volumes changed during the read")
        }
        val byVolume = namespaces.associateBy { it.volumeName }
        DeviceMediaSnapshot(result.map { it.copy(providerGeneration = byVolume.getValue(it.volumeName).providerGeneration) }, namespaces, aliases)
    }

    private suspend fun resolveLegacyFavorites(references: Set<String>, live: List<MediaItem>): Map<String, String> {
        if (Build.VERSION.SDK_INT < 29) return emptyMap() // Legacy URIs still directly match on API 26–28.
        val legacy = references.mapNotNull(::legacyFavoriteReference)
        if (legacy.isEmpty()) return emptyMap()
        val candidates = live.groupBy { it.isVideo to it.externalHandle }
        val matches = mutableMapOf<String, String>()
        for (reference in legacy) {
            currentCoroutineContext().ensureActive()
            val possible = candidates[reference.isVideo to reference.externalHandle].orEmpty()
            if (possible.size != 1) continue // No IO for missing/ambiguous rows; never guess a volume.
            val proof = readLegacyFavoriteProof(reference) ?: continue
            provenLegacyFavoriteMatch(reference, proof, possible)?.let { matches[reference.key] = it }
        }
        return matches
    }

    private fun readLegacyFavoriteProof(reference: LegacyFavoriteReference): LegacyFavoriteProof? {
        if (Build.VERSION.SDK_INT < 29) return null
        val projection = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.DATE_ADDED)
        if (Build.VERSION.SDK_INT >= 30) projection += MediaStore.MediaColumns.GENERATION_ADDED
        return try {
            resolver.query(reference.key.toUri(), projection.toTypedArray(), null, null, null)?.use { cursor ->
                if (cursor.count != 1 || !cursor.moveToFirst()) return@use null
                fun number(column: String): Long? {
                    val index = cursor.getColumnIndex(column)
                    return if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                }
                val volume = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                if (volume < 0 || cursor.isNull(volume)) return@use null
                val dateAdded = number(MediaStore.MediaColumns.DATE_ADDED) ?: return@use null
                val generationAdded = if (Build.VERSION.SDK_INT >= 30) {
                    number(MediaStore.MediaColumns.GENERATION_ADDED)?.takeIf { it >= 0 } ?: return@use null
                } else null
                if (generationAdded == null && dateAdded <= 0) return@use null
                LegacyFavoriteProof(
                    providerAuthority = reference.key.toUri().authority ?: return@use null,
                    volumeName = cursor.getString(volume),
                    externalHandle = number(MediaStore.MediaColumns._ID)?.toString() ?: return@use null,
                    isVideo = reference.isVideo,
                    dateAddedSeconds = dateAdded,
                    generationAdded = generationAdded,
                    birthGenerationRequired = Build.VERSION.SDK_INT >= 30,
                )
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RuntimeException) { null } // Retain unresolved reference; valid live browsing continues.
    }

    private fun namespace(volume: String): CatalogueNamespace {
        val version = when {
            Build.VERSION.SDK_INT >= 30 -> MediaStore.getVersion(context, volume)
            Build.VERSION.SDK_INT >= 29 -> MediaStore.getVersion(context)
            else -> null
        }
        val generation = if (Build.VERSION.SDK_INT >= 30) MediaStore.getGeneration(context, volume) else null
        return CatalogueNamespace(MediaStore.AUTHORITY, volume, version, generation)
    }

    private suspend fun query(collection: Uri, video: Boolean, namespace: CatalogueNamespace): List<MediaItem> {
        currentCoroutineContext().ensureActive()
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Images.ImageColumns.DATE_TAKEN,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Images.ImageColumns.BUCKET_ID,
            MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
        )
        if (video) projection += MediaStore.Video.VideoColumns.DURATION
        else projection += MediaStore.Images.ImageColumns.ORIENTATION
        if (Build.VERSION.SDK_INT >= 30) {
            projection += MediaStore.MediaColumns.GENERATION_ADDED
            projection += MediaStore.MediaColumns.GENERATION_MODIFIED
        }
        val items = mutableListOf<MediaItem>()
        val queried = resolver.query(collection, projection.toTypedArray(), null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            ?: throw IllegalStateException("Media provider returned no cursor")
        queried.use { cursor ->
            fun number(column: String): Long {
                val index = cursor.getColumnIndex(column)
                return if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L
            }
            fun text(column: String): String {
                val index = cursor.getColumnIndex(column)
                return if (index >= 0 && !cursor.isNull(index)) cursor.getString(index).orEmpty() else ""
            }
            fun optionalNumber(column: String): Long? {
                val index = cursor.getColumnIndex(column)
                return if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index).takeIf { it >= 0 } else null
            }
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                if (cursor.isNull(idColumn)) throw IllegalStateException("Media provider returned no source handle")
                val id = cursor.getLong(idColumn)
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
                    albumId = sourceAlbumId(namespace.providerAuthority, namespace.volumeName, text(MediaStore.Images.ImageColumns.BUCKET_ID)),
                    albumName = text(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME),
                    orientationDegrees = if (video) 0 else number(MediaStore.Images.ImageColumns.ORIENTATION).toInt(),
                    modifiedAt = number(MediaStore.MediaColumns.DATE_MODIFIED) * 1000L,
                    providerAuthority = collection.authority ?: throw IllegalStateException("Missing media authority"),
                    volumeName = namespace.volumeName,
                    providerVersion = namespace.providerVersion,
                    providerGeneration = namespace.providerGeneration,
                    externalHandle = id.toString(),
                    generationAdded = if (Build.VERSION.SDK_INT >= 30) optionalNumber(MediaStore.MediaColumns.GENERATION_ADDED) else null,
                    generationModified = if (Build.VERSION.SDK_INT >= 30) optionalNumber(MediaStore.MediaColumns.GENERATION_MODIFIED) else null,
                    dateAddedSeconds = number(MediaStore.MediaColumns.DATE_ADDED),
                )
            }
        }
        return items
    }

    suspend fun thumbnail(item: MediaItem, edge: Int): Bitmap? = withContext(Dispatchers.IO) {
        val token = epoch.snapshot()
        val key = previewFingerprint(item, edge)
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

private data class DeviceMediaSnapshot(val items: List<MediaItem>, val namespaces: List<CatalogueNamespace>, val legacyFavoriteMatches: Map<String, String>)

private fun MediaItem.catalogueObservation() = CatalogueObservation(
    providerAuthority, volumeName, providerVersion, providerGeneration, externalHandle,
    generationAdded, generationModified, dateAddedSeconds, id, name, mimeType, isVideo,
    capturedAt, width, height, bytes, durationMs, albumId, albumName, orientationDegrees, modifiedAt,
)

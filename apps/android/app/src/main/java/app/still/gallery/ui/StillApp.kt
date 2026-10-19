package app.still.gallery.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.net.toUri
import androidx.core.content.edit
import app.still.gallery.R
import app.still.gallery.media.AccessLevel
import app.still.gallery.media.DeviceMedia
import app.still.gallery.media.MediaAccess
import app.still.gallery.media.MediaItem
import app.still.gallery.media.mediaAccess
import app.still.gallery.media.mediaPermissions
import app.still.gallery.media.shareMimeType
import app.still.gallery.media.visibleSelection
import app.still.gallery.media.previewFingerprint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class Destination(val label: Int, val mark: Mark) {
    PHOTOS(R.string.photos, Mark.PHOTOS), ALBUMS(R.string.albums, Mark.ALBUMS),
    FAVORITES(R.string.favorites, Mark.HEART), SETTINGS(R.string.settings, Mark.SETTINGS),
}

@Composable
fun StillApp() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val device = remember { DeviceMedia(context.applicationContext) }
    val preferences = remember { context.getSharedPreferences("still-local", Context.MODE_PRIVATE) }
    var access by remember { mutableStateOf(context.mediaAccess()) }
    var media by remember { mutableStateOf(emptyList<MediaItem>()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var destinationName by rememberSaveable { mutableStateOf(Destination.PHOTOS.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var albumId by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf(preferences.getStringSet("favorites", emptySet()).orEmpty().toSet()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val shareLabel = stringResource(R.string.share_media)
    val shareFailure = stringResource(R.string.share_failed)
    val favoriteConfirmation = stringResource(R.string.favorites_saved)
    val destination = Destination.valueOf(destinationName)
    val photosPosition = rememberLazyGridState()
    val favoritesPosition = rememberLazyGridState()
    val albumsPosition = rememberLazyGridState()
    val albumPhotosPosition = rememberLazyGridState()

    fun reload() {
        val fresh = context.mediaAccess()
        if (fresh != access || fresh.level == AccessLevel.PARTIAL) {
            // Never keep an already-open item visible while reduced permissions are reconciled.
            openedId = null
            media = emptyList()
            selected = emptyList()
        }
        access = fresh
        loading = fresh.level != AccessLevel.NONE
        loadFailed = false
        device.clearThumbnails()
        refresh++
    }

    fun saveFavorites(next: Set<String>) {
        preferences.edit { putStringSet("favorites", next) }
        favorites = next
    }

    fun share(items: List<MediaItem>) {
        if (items.isEmpty()) return
        try {
            val uris = ArrayList(items.map { it.id.toUri() })
            val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = shareMimeType(items)
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = ClipData.newUri(context.contentResolver, shareLabel, uris.first()).also { clip ->
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, shareLabel))
        } catch (_: RuntimeException) {
            scope.launch { snackbar.showSnackbar(shareFailure) }
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }
    fun settings() { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }

    DisposableEffect(lifecycle, device, access) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) reload() }
        lifecycle.addObserver(observer)
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reload() }
        }
        var observing = false
        try {
            if (access.canReadImages) {
                context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, contentObserver)
                observing = true
            }
            if (access.canReadVideos) {
                context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, contentObserver)
                observing = true
            }
        } catch (_: SecurityException) {
            // Permission may be withdrawn between the check and observer registration.
        }
        reload()
        onDispose {
            lifecycle.removeObserver(observer)
            if (observing) context.contentResolver.unregisterContentObserver(contentObserver)
            device.clearThumbnails()
        }
    }

    LaunchedEffect(refresh) {
        loading = access.level != AccessLevel.NONE
        loadFailed = false
        if (access.level == AccessLevel.NONE) {
            media = emptyList()
            selected = emptyList()
            openedId = null
            return@LaunchedEffect
        }
        try {
            val loaded = device.read(access)
            media = loaded
            selected = visibleSelection(selected.toSet(), loaded).toList()
            if (openedId != null && loaded.none { it.id == openedId }) openedId = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            media = emptyList()
            selected = emptyList()
            openedId = null
            loadFailed = true
            access = context.mediaAccess()
        } finally {
            loading = false
        }
    }

    BackHandler(selecting || albumId != null) {
        if (selecting) { selecting = false; selected = emptyList() }
        else albumId = null
    }

    val filtered = remember(media, query, albumId, destination, favorites) {
        media.filter { item ->
            (albumId == null || item.albumId == albumId) &&
                (destination != Destination.FAVORITES || item.id in favorites) &&
                (query.isBlank() || item.name.contains(query.trim(), ignoreCase = true))
        }
    }
    val albumTitle = media.firstOrNull { it.albumId == albumId }?.albumName.orEmpty()
    val title = when {
        selecting -> pluralStringResource(R.plurals.selected_count, selected.size, selected.size)
        albumId != null -> albumTitle.ifBlank { stringResource(R.string.unknown_album) }
        destination == Destination.PHOTOS -> stringResource(R.string.app_name)
        else -> stringResource(destination.label)
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (albumId != null && !selecting) {
                        IconButton(onClick = { albumId = null }) { MarkIcon(Mark.BACK, stringResource(R.string.back)) }
                    } else if (!selecting && destination == Destination.PHOTOS) {
                        Image(painterResource(R.drawable.ic_still), null, Modifier.size(38.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!selecting && destination != Destination.SETTINGS) {
                            Text(pluralStringResource(R.plurals.media_count, filtered.size, filtered.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (selecting) TextButton(onClick = { selecting = false; selected = emptyList() }) { Text(stringResource(R.string.cancel)) }
                    else if (destination != Destination.SETTINGS && destination != Destination.ALBUMS && media.isNotEmpty()) {
                        TextButton(onClick = { selecting = true }) { Text(stringResource(R.string.select)) }
                    }
                }
                if (destination != Destination.SETTINGS && access.level != AccessLevel.NONE) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.search_media)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { MarkIcon(Mark.CLOSE, stringResource(R.string.clear_search)) } }) else null,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
        },
        bottomBar = {
            if (selecting) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { share(media.filter { it.id in selected }) }, enabled = selected.isNotEmpty()) { MarkIcon(Mark.SHARE, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.share)) }
                    TextButton(onClick = {
                        saveFavorites(favorites + selected)
                        scope.launch { snackbar.showSnackbar(favoriteConfirmation) }
                    }, enabled = selected.isNotEmpty()) { MarkIcon(Mark.HEART, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.favorites)) }
                }
            } else NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Destination.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = destination == entry,
                        onClick = { destinationName = entry.name; albumId = null; query = "" },
                        icon = { MarkIcon(entry.mark, null) },
                        label = { Text(stringResource(entry.label)) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (destination == Destination.SETTINGS) {
                SettingsContent(access, { permissions.launch(mediaPermissions()) }, ::settings, ::reload)
            } else if (access.level == AccessLevel.NONE) {
                MessagePanel(R.string.access_title, R.string.access_body) {
                    Button(onClick = { permissions.launch(mediaPermissions()) }) { Text(stringResource(R.string.choose_access)) }
                    TextButton(onClick = ::settings) { Text(stringResource(R.string.open_settings)) }
                }
            } else {
                if (access.level == AccessLevel.PARTIAL) {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.limited_title), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.limited_body), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                            TextButton(onClick = { permissions.launch(mediaPermissions()) }) { Text(stringResource(R.string.change_access)) }
                        }
                    }
                }
                when {
                    loading && media.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    loadFailed -> MessagePanel(R.string.load_failed, R.string.permission_settings_note) {
                        Button(onClick = ::reload) { Text(stringResource(R.string.retry)) }
                    }
                    filtered.isEmpty() -> {
                        val emptyTitle = when { query.isNotBlank() -> R.string.no_results_title; destination == Destination.FAVORITES -> R.string.no_favorites_title; else -> R.string.empty_title }
                        val emptyBody = when { query.isNotBlank() -> R.string.no_results_body; destination == Destination.FAVORITES -> R.string.no_favorites_body; else -> R.string.empty_body }
                        MessagePanel(emptyTitle, emptyBody) {
                            if (query.isBlank() && destination != Destination.FAVORITES) TextButton(onClick = { permissions.launch(mediaPermissions()) }) { Text(stringResource(R.string.change_access)) }
                        }
                    }
                    destination == Destination.ALBUMS && albumId == null -> AlbumGrid(filtered, device, refresh, albumsPosition) { id -> albumId = id }
                    else -> PhotoGrid(filtered, device, refresh, selected.toSet(), selecting,
                        position = if (albumId != null) albumPhotosPosition else if (destination == Destination.FAVORITES) favoritesPosition else photosPosition,
                        onOpen = { openedId = it.id },
                        onToggle = { item -> selected = if (item.id in selected) selected - item.id else selected + item.id },
                        onStartSelection = { item -> selecting = true; if (item.id !in selected) selected = selected + item.id },
                    )
                }
            }
        }
    }

    media.firstOrNull { it.id == openedId }?.let { item ->
        MediaViewer(item, device, refresh, item.id in favorites,
            onClose = { openedId = null },
            onShare = { share(listOf(item)) },
            onFavorite = { saveFavorites(if (item.id in favorites) favorites - item.id else favorites + item.id) },
        )
    }
}

@Composable
private fun MessagePanel(title: Int, body: Int, actions: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 48.dp), verticalArrangement = Arrangement.Center) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Text(stringResource(body), Modifier.padding(top = 12.dp, bottom = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}

@Composable
private fun PhotoGrid(items: List<MediaItem>, device: DeviceMedia, generation: Int, selected: Set<String>, selecting: Boolean, position: LazyGridState, onOpen: (MediaItem) -> Unit, onToggle: (MediaItem) -> Unit, onStartSelection: (MediaItem) -> Unit) {
    val groups = remember(items) { items.groupBy { monthKey(it.capturedAt) } }
    LazyVerticalGrid(columns = GridCells.Adaptive(108.dp), state = position, contentPadding = PaddingValues(start = 3.dp, end = 3.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        groups.forEach { (month, media) ->
            item(key = "month:$month", span = { GridItemSpan(maxLineSpan) }) {
                Text(month.ifBlank { stringResource(R.string.unknown_date) }, Modifier.padding(start = 17.dp, top = 24.dp, bottom = 12.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            }
            items(media, key = { it.id }) { item ->
                val isSelected = item.id in selected
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(3.dp)).semantics { this.selected = isSelected }
                    .combinedClickable(
                        onClick = { if (selecting) onToggle(item) else onOpen(item) },
                        onLongClickLabel = stringResource(R.string.select),
                        onLongClick = { onStartSelection(item) },
                    ).then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)) else Modifier)) {
                    LibraryImage(item, device, generation, Modifier.fillMaxSize())
                    if (item.isVideo) Text(durationLabel(item.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp))
                    if (selecting) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(26.dp).background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = .35f), RoundedCornerShape(13.dp)).border(1.dp, Color.White, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                        if (isSelected) MarkIcon(Mark.SELECT, null, Modifier.size(19.dp), Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumGrid(items: List<MediaItem>, device: DeviceMedia, generation: Int, position: LazyGridState, onOpen: (String) -> Unit) {
    val albums = remember(items) { items.groupBy { it.albumId }.entries.toList() }
    LazyVerticalGrid(columns = GridCells.Adaptive(148.dp), state = position, contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        items(albums, key = { it.key }) { (id, media) ->
            Column(Modifier.clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = { onOpen(id) })) {
                LibraryImage(media.first(), device, generation, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)))
                Text(media.first().albumName.ifBlank { stringResource(R.string.unknown_album) }, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(pluralStringResource(R.plurals.media_count, media.size, media.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun LibraryImage(item: MediaItem, device: DeviceMedia, generation: Int, modifier: Modifier = Modifier, edge: Int = 360, contentScale: ContentScale = ContentScale.Crop, backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant) {
    val fingerprint = previewFingerprint(item, edge)
    key(fingerprint, generation) {
    val preview by produceState(ThumbnailPreview(), fingerprint, generation) {
        value = ThumbnailPreview(bitmap = device.thumbnail(item, edge), loading = false)
    }
    val description = stringResource(R.string.media_description, stringResource(if (item.isVideo) R.string.video else R.string.photo), item.name)
    Box(modifier.background(backgroundColor), contentAlignment = Alignment.Center) {
        if (preview.bitmap != null) Image(preview.bitmap!!.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = contentScale)
        else if (preview.loading) MarkIcon(Mark.PHOTOS, description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MarkIcon(Mark.PHOTOS, stringResource(R.string.media_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (edge > 512) Text(stringResource(R.string.media_unavailable), modifier = Modifier.padding(24.dp), color = Color(0xFFF0F1EB), style = MaterialTheme.typography.bodyMedium)
        }
    }
    }
}

private data class ThumbnailPreview(val bitmap: android.graphics.Bitmap? = null, val loading: Boolean = true)

@Composable
private fun SettingsContent(access: MediaAccess, changeAccess: () -> Unit, settings: () -> Unit, refresh: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.development_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.development_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(stringResource(when (access.level) { AccessLevel.FULL -> R.string.access_full; AccessLevel.PARTIAL -> R.string.access_partial; AccessLevel.NONE -> R.string.access_none }), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.permission_settings_note), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = changeAccess) { Text(stringResource(R.string.change_access)) }
        TextButton(onClick = settings) { Text(stringResource(R.string.open_settings)) }
        TextButton(onClick = refresh) { Text(stringResource(R.string.refresh)) }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.privacy_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.favorites_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.favorites_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.version), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun monthKey(milliseconds: Long): String = if (milliseconds <= 0) "" else
    DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()).format(Instant.ofEpochMilli(milliseconds).atZone(ZoneId.systemDefault()))

fun durationLabel(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

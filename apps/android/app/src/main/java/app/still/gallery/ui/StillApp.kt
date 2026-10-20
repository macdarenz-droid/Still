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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
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
import app.still.gallery.media.DriftNavigationSession
import app.still.gallery.media.DriftSnapshot
import app.still.gallery.media.DriftTarget
import app.still.gallery.media.AccessLevel
import app.still.gallery.media.DeviceMedia
import app.still.gallery.media.MediaAccess
import app.still.gallery.media.MediaItem
import app.still.gallery.media.mediaAccess
import app.still.gallery.media.mediaPermissions
import app.still.gallery.media.shareMimeType
import app.still.gallery.media.previewFingerprint
import app.still.gallery.media.MediaFilter
import app.still.gallery.media.MediaSort
import app.still.gallery.media.libraryOrder
import app.still.gallery.media.reconcileMediaTokens
import app.still.gallery.media.selectedMediaInView
import app.still.gallery.media.sourceUiToken
import app.still.gallery.media.favoriteKeysForSource
import app.still.gallery.media.visibleFavoriteSourceIds
import app.still.gallery.media.unresolvedFavoriteKeys
import app.still.gallery.media.galleryVisibleMedia
import app.still.gallery.media.galleryActionItems
import app.still.gallery.media.GalleryActionLifetime
import app.still.gallery.media.AndroidCleanupSourceReader
import app.still.gallery.media.cleanupSnapshotMatchesGallery
import app.still.gallery.cleanup.AndroidContentProtections
import app.still.gallery.security.AppLockUiState
import app.still.gallery.security.LockAction
import app.still.gallery.similarity.SimilarityResult
import app.still.gallery.similarity.SimilarityIndexStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class Destination(val label: Int, val mark: Mark) {
    PHOTOS(R.string.photos, Mark.PHOTOS), ALBUMS(R.string.albums, Mark.ALBUMS),
    FAVORITES(R.string.favorites, Mark.HEART), CLEANUP(R.string.cleanup_title, Mark.CLEANUP), SETTINGS(R.string.settings, Mark.SETTINGS),
}

@Composable
fun StillApp(lockState: AppLockUiState, authenticateLock: (LockAction) -> Unit, securitySettings: () -> Unit, canAccessGallery: () -> Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val device = remember { DeviceMedia(context.applicationContext) }
    val actionLifetime = remember { GalleryActionLifetime(canAccessGallery) }
    val preferences = remember { context.getSharedPreferences("still-local", Context.MODE_PRIVATE) }
    var access by remember { mutableStateOf(context.mediaAccess()) }
    var media by remember { mutableStateOf(emptyList<MediaItem>()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var indexFailed by remember { mutableStateOf(false) }
    var indexing by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var destinationName by rememberSaveable { mutableStateOf(Destination.PHOTOS.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(MediaFilter.ALL.name) }
    var sortName by rememberSaveable { mutableStateOf(MediaSort.NEWEST.name) }
    var albumId by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var viewMode by rememberSaveable { mutableStateOf("RIBBON") }
    val options = remember(actionLifetime) { DriftOverlaySession { actionLifetime.isActive } }
    val menu = remember(actionLifetime) { DriftOverlaySession { actionLifetime.isActive } }
    var optionsRequest by remember { mutableStateOf<DriftOverlayRequest?>(null) }
    var menuRequest by remember { mutableStateOf<DriftOverlayRequest?>(null) }
    var initializedProjection by remember { mutableStateOf<DriftSnapshot?>(null) }
    var viewRevision by remember { mutableIntStateOf(0) }
    var focusRevision by remember { mutableIntStateOf(0) }
    val focusMemory = remember { DriftFocusMemory() }
    var viewerRevision by remember { mutableIntStateOf(0) }
    val drift = remember(actionLifetime) { DriftNavigationSession { actionLifetime.isActive && !loading && !loadFailed } }
    val driftInteraction = remember(actionLifetime) { DriftInteraction { actionLifetime.isActive && !loading && !loadFailed } }
    val viewers = remember(actionLifetime) { DriftViewerSession { actionLifetime.isActive && !loading && !loadFailed } }
    var favorites by remember { mutableStateOf(preferences.getStringSet("favorites", emptySet()).orEmpty().toSet()) }
    var legacyFavoriteMatches by remember { mutableStateOf(emptyMap<String, String>()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val cleanup = remember(device, actionLifetime) {
        CleanupCoordinator(scope, actionLifetime,
            { granted -> AndroidCleanupSourceReader(context.applicationContext, device, granted) },
            { AndroidContentProtections(context.applicationContext) })
    }
    val comparison = remember(device, actionLifetime) {
        CompareCoordinator<ComparePreview>(scope, { actionLifetime.isActive }, { recoverCompareStorage(context.applicationContext, device) })
    }
    LaunchedEffect(comparison) { comparison.initialize() }
    val similarityCompare = remember(actionLifetime) { SimilarityCompareBinding { actionLifetime.isActive } }
    val similarity = remember(device, actionLifetime) {
        SimilarityCoordinator<SimilarityResult>(scope, { actionLifetime.isActive },
            { recoverSimilarityReads(AndroidCleanupSourceReader(context.applicationContext, device, context.mediaAccess())) },
            { request -> if (similarityCompare.invalidate(request)) comparison.invalidate() })
    }
    LaunchedEffect(similarity) { similarity.initialize() }
    val similarityIndex = remember(device, actionLifetime) { SimilarityIndexCoordinator(scope, context.applicationContext, { actionLifetime.isActive }) }
    val shareLabel = stringResource(R.string.share_media)
    val shareFailure = stringResource(R.string.share_failed)
    val favoriteConfirmation = stringResource(R.string.favorites_saved)
    val destination = Destination.valueOf(destinationName)
    val mediaFilter = MediaFilter.entries.firstOrNull { it.name == filterName } ?: MediaFilter.ALL
    val mediaSort = MediaSort.entries.firstOrNull { it.name == sortName } ?: MediaSort.NEWEST
    val photosPosition = rememberLazyGridState()
    val favoritesPosition = rememberLazyGridState()
    val albumsPosition = rememberLazyGridState()
    val albumPhotosPosition = rememberLazyGridState()

    fun invalidateSnapshot() {
        similarity.invalidate()
        similarityIndex.stop()
        comparison.invalidate()
        cleanup.invalidate()
        options.invalidate(); menu.invalidate()
        optionsRequest = null; menuRequest = null
        focusMemory.capture(driftInteraction.focusedToken)
        drift.invalidate()
        driftInteraction.invalidate()
        viewers.invalidate()
        viewerRevision++
        val fresh = context.mediaAccess()
        if (fresh != access || fresh.level == AccessLevel.PARTIAL) {
            // Never keep an already-open item visible while reduced permissions are reconciled.
            selected = emptyList()
        }
        // Every provider invalidation hides the old snapshot before async read/proof.
        // Only UI tokens may survive to be reconciled against the fresh result.
        media = emptyList()
        legacyFavoriteMatches = emptyMap()
        access = fresh
        loading = fresh.level != AccessLevel.NONE
        loadFailed = false
        indexFailed = false
        indexing = false
        device.invalidateScans()
        device.clearThumbnails()
        refresh++
    }

    fun reload() { if (actionLifetime.isActive) invalidateSnapshot() }

    fun changeBrowseScope(expectedOptions: DriftOverlayRequest? = null, change: () -> Unit) {
        if (!actionLifetime.isActive || expectedOptions != null && !options.isCurrent(expectedOptions)) return
        similarity.invalidate()
        comparison.invalidate()
        if (expectedOptions == null) { options.invalidate(); optionsRequest = null }
        menu.invalidate(); menuRequest = null
        focusMemory.capture(driftInteraction.focusedToken)
        drift.invalidate()
        driftInteraction.invalidate()
        viewers.invalidate()
        viewerRevision++
        viewRevision++
        change()
    }

    fun openViewer(item: MediaItem, projection: DriftSnapshot? = null) {
        galleryActionItems(listOf(item), media, loading, loadFailed, actionLifetime).singleOrNull()?.let {
            if (projection != null) {
                if (!driftRememberOpenedSource(drift, projection, driftInteraction, focusMemory, sourceUiToken(it))) return@let
                focusRevision++
            }
            if (viewers.open(sourceUiToken(it)) != null) viewerRevision++
        }
    }

    fun openOptions() {
        if (!actionLifetime.isActive) return
        menu.invalidate(); menuRequest = null
        optionsRequest = options.open()
    }

    fun openMenu() {
        if (!actionLifetime.isActive) return
        options.invalidate(); optionsRequest = null
        menuRequest = menu.open()
    }

    fun saveFavorites(next: Set<String>) {
        similarity.invalidate()
        comparison.invalidate()
        if (destination == Destination.FAVORITES) {
            drift.invalidate()
            driftInteraction.invalidate()
            viewers.invalidate()
            viewerRevision++
            viewRevision++
        }
        preferences.edit { putStringSet("favorites", next) }
        favorites = next
        cleanup.invalidate()
    }

    fun share(items: List<MediaItem>) {
        val eligible = galleryActionItems(items, media, loading, loadFailed, actionLifetime)
        if (eligible.isEmpty()) return
        try {
            val uris = ArrayList(eligible.map { it.id.toUri() })
            val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = shareMimeType(eligible)
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
    fun changeAccess() { if (actionLifetime.isActive) permissions.launch(mediaPermissions()) }
    fun settings() {
        if (actionLifetime.isActive) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
    }

    DisposableEffect(actionLifetime, device) {
        onDispose {
            similarity.dispose()
            similarityIndex.dispose()
            comparison.dispose()
            drift.invalidate()
            driftInteraction.invalidate()
            viewers.invalidate()
            options.invalidate(); menu.invalidate()
            actionLifetime.invalidate()
            cleanup.invalidate()
            device.invalidateScans()
            device.clearThumbnails()
        }
    }

    DisposableEffect(lifecycle, device, access) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { similarity.invalidate(); similarityIndex.stop(); comparison.invalidate() }
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) invalidateSnapshot()
        }
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
            device.invalidateScans()
            device.clearThumbnails()
        }
    }

    LaunchedEffect(refresh) {
        if (!actionLifetime.isActive) return@LaunchedEffect
        val refreshAtStart = refresh
        loading = access.level != AccessLevel.NONE
        loadFailed = false
        if (access.level == AccessLevel.NONE) {
            media = emptyList()
            legacyFavoriteMatches = emptyMap()
            selected = emptyList()
            device.clearSimilarityIndex()
            return@LaunchedEffect
        }
        try {
            val saved = device.readAndIndex(access, favorites) { loaded, aliases ->
                if (!actionLifetime.isActive) throw CancellationException("Gallery access ended")
                media = loaded
                legacyFavoriteMatches = aliases
                selected = reconcileMediaTokens(selected.toSet(), loaded).toList()
                focusMemory.reconcile(loaded)
                loading = false
                indexing = true
            }
            if (refreshAtStart == refresh && actionLifetime.isActive) indexFailed = !saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            if (refreshAtStart == refresh && actionLifetime.isActive) {
                media = emptyList()
                legacyFavoriteMatches = emptyMap()
                selected = emptyList()
                loadFailed = true
                access = context.mediaAccess()
            }
        } finally {
            if (refreshAtStart == refresh && actionLifetime.isActive) { loading = false; indexing = false }
        }
    }

    BackHandler(selecting || albumId != null) {
        changeBrowseScope { if (selecting) { selecting = false; selected = emptyList() } else albumId = null }
    }

    LaunchedEffect(destination) {
        if (destination == Destination.SETTINGS) similarityIndex.initialize() else similarityIndex.stop()
        if (destination == Destination.CLEANUP && actionLifetime.isActive) cleanup.loadProtections()
    }

    val currentlyAuthorized = actionLifetime.isActive
    val visibleMedia = remember(media, loading, loadFailed, currentlyAuthorized) { galleryVisibleMedia(media, loading || !currentlyAuthorized, loadFailed) }
    val favoriteIds = remember(visibleMedia, favorites, legacyFavoriteMatches) { visibleFavoriteSourceIds(favorites, legacyFavoriteMatches, visibleMedia) }
    val unresolvedFavorites = remember(visibleMedia, favorites, legacyFavoriteMatches) { unresolvedFavoriteKeys(favorites, legacyFavoriteMatches, visibleMedia) }
    val filtered = remember(visibleMedia, query, albumId, destination, favoriteIds, mediaFilter, mediaSort) {
        libraryOrder(visibleMedia.filter { item ->
            (albumId == null || item.albumId == albumId) &&
                (destination != Destination.FAVORITES || item.id in favoriteIds) &&
                (query.isBlank() || item.name.contains(query.trim(), ignoreCase = true))
        }, mediaFilter, mediaSort)
    }
    val selectedInView = remember(selected, filtered) { selectedMediaInView(selected.toSet(), filtered) }
    val albumTitle = visibleMedia.firstOrNull { it.albumId == albumId }?.albumName.orEmpty()
    val title = when {
        selecting -> pluralStringResource(R.plurals.selected_count, selectedInView.size, selectedInView.size)
        albumId != null -> albumTitle.ifBlank { stringResource(R.string.unknown_album) }
        destination == Destination.PHOTOS -> stringResource(R.string.app_name)
        else -> stringResource(destination.label)
    }

    val zone = ZoneId.systemDefault()
    // Refresh and every view-scope choice are explicit keys, including equal-shaped results.
    val driftSnapshot = remember(filtered, refresh, viewRevision, destination, albumId, query, mediaFilter, mediaSort, viewMode, selecting, zone) {
        drift.replace(filtered, mediaSort, zone)
    }
    LaunchedEffect(driftSnapshot) {
        if (!drift.isCurrent(driftSnapshot)) return@LaunchedEffect
        val oldFocus = focusMemory.token ?: driftInteraction.focusedToken
        val current = oldFocus?.let { drift.target(driftSnapshot, it) } ?: drift.first(driftSnapshot)
        driftInteraction.replace(driftSnapshot, current?.sourceUiToken)
        focusMemory.settle(current?.sourceUiToken, loading || loadFailed)
        initializedProjection = driftSnapshot
        focusRevision++
    }
    val focusedIntent = remember(driftSnapshot, focusRevision, initializedProjection) {
        if (initializedProjection === driftSnapshot) driftInteraction.currentFocus else null
    }
    val focused = focusedIntent?.sourceToken?.let { drift.target(driftSnapshot, it) }
    val railStart = remember(driftSnapshot, focusRevision) { driftInteraction.railStart }
    val navigationReady = initializedProjection === driftSnapshot && drift.isCurrent(driftSnapshot)
    fun focusedCurrent(): Boolean = focused != null && drift.isCurrent(driftSnapshot) && driftInteraction.isCurrent(driftSnapshot, focusedIntent)
    fun focus(target: DriftTarget?): Boolean {
        if (target == null || focused == null || !drift.isCurrent(driftSnapshot)) return false
        if (!driftInteraction.focus(driftSnapshot, focusedIntent, target.sourceUiToken)) return false
        focusMemory.capture(target.sourceUiToken)
        focusRevision++
        return true
    }
    val canBrowse = destination != Destination.SETTINGS && destination != Destination.CLEANUP
    val filterLabel = stringResource(when (mediaFilter) { MediaFilter.ALL -> R.string.filter_all; MediaFilter.PHOTOS -> R.string.filter_photos; MediaFilter.VIDEOS -> R.string.filter_videos })
    val sortLabel = stringResource(when (mediaSort) { MediaSort.NEWEST -> R.string.sort_newest; MediaSort.OLDEST -> R.string.sort_oldest; MediaSort.NAME -> R.string.sort_name })

    val compactChrome = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() < 480.dp } || LocalDensity.current.fontScale > 1.5f
    val capturedComparePair = selectedInView.toList()
    val compareAction = comparison.actionIdentity
    val similarityAction = similarity.actionIdentity
    val capturedSimilarityScope = selectedInView.toList()

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                DriftHeader(title = title,
                    subtitle = if (!selecting && canBrowse) pluralStringResource(R.plurals.media_count, filtered.size, filtered.size) else null,
                    selecting = selecting, canBrowse = canBrowse && access.level != AccessLevel.NONE && !compactChrome,
                    showBack = albumId != null && !selecting,
                    onBack = { changeBrowseScope { albumId = null } },
                    onSearch = ::openOptions,
                    onOptions = ::openOptions,
                    onSettings = { changeBrowseScope { destinationName = Destination.SETTINGS.name; albumId = null; selecting = false; selected = emptyList() } },
                    onCancelSelection = { changeBrowseScope { selecting = false; selected = emptyList() } },
                )
                if (canBrowse && !selecting && access.level != AccessLevel.NONE) {
                    TextButton(onClick = { openOptions() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.drift_scope_summary, filterLabel, sortLabel,
                            stringResource(if (viewMode == "GRID" || destination == Destination.ALBUMS && albumId == null) R.string.drift_grid else R.string.drift_ribbon)) + if (query.isNotBlank()) " · " + stringResource(R.string.drift_search_active) else "")
                    }
                }
            }
        },
        bottomBar = {
            if (selecting) {
                FlowRow(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { if (!loading && !loadFailed) share(driftBatchItems(drift, driftSnapshot, selected.toSet(), filtered)) }, enabled = !loading && !loadFailed && selectedInView.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { MarkIcon(Mark.SHARE, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.share)) }
                    TextButton(onClick = {
                        val chosen = galleryActionItems(driftBatchItems(drift, driftSnapshot, selected.toSet(), filtered), media, loading, loadFailed, actionLifetime)
                        if (chosen.isNotEmpty()) {
                            saveFavorites(favorites + chosen.map { it.id })
                            scope.launch { snackbar.showSnackbar(favoriteConfirmation) }
                        }
                    }, enabled = !loading && !loadFailed && selectedInView.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { MarkIcon(Mark.HEART, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.favorites)) }
                    if (capturedComparePair.size == 2 && capturedComparePair.none { it.isVideo }) {
                        TextButton(onClick = {
                            val startRefresh = refresh
                            val startView = viewRevision
                            val granted = access
                            fun currentPair(): Boolean = actionLifetime.isActive && refresh == startRefresh && viewRevision == startView &&
                                drift.isCurrent(driftSnapshot) && !loading && !loadFailed && context.mediaAccess() == granted &&
                                compareSelectedPhotos(capturedComparePair, selected.toSet(), filtered).size == 2 &&
                                galleryActionItems(capturedComparePair, media, loading, loadFailed, actionLifetime).size == 2
                            if (currentPair() && comparison.actionIdentity === compareAction && comparison.canStart) {
                                similarityCompare.invalidate()
                                viewers.invalidate(); viewerRevision++
                                comparison.start(compareAction, capturedComparePair.map { it.name }, ::currentPair) {
                                    ComparePreparedWork(context.applicationContext, device, capturedComparePair, granted, favorites.toSet(), ::currentPair)
                                }
                            }
                        }, enabled = comparison.canStart && !loading && !loadFailed, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.compare_title)) }
                    }
                    TextButton(onClick = {
                        val startRefresh = refresh
                        val startView = viewRevision
                        val granted = access
                        val selectionProjection = SimilaritySelectionProjection(capturedSimilarityScope, selected, filtered, media)
                        fun currentScope(): Boolean = actionLifetime.isActive && refresh == startRefresh && viewRevision == startView &&
                            drift.isCurrent(driftSnapshot) && !loading && !loadFailed && context.mediaAccess() == granted &&
                            selectionProjection.isCurrent(selected, media)
                        if (similarity.actionIdentity === similarityAction && similarity.canStart &&
                            capturedSimilarityScope.size in 2..200 && capturedSimilarityScope.none { it.isVideo } && currentScope()) {
                            comparison.invalidate(); viewers.invalidate(); viewerRevision++
                            similarity.start(similarityAction, capturedSimilarityScope.size, ::currentScope) {
                                SimilarityScannerWork(AndroidCleanupSourceReader(context.applicationContext, device, granted),
                                    capturedSimilarityScope, favorites.toSet(), {
                                        if (!currentScope()) throw CancellationException("Photo selection changed")
                                    }, { fresh ->
                                        if (!currentScope()) throw CancellationException("Photo selection changed")
                                        if (!cleanupSnapshotMatchesGallery(fresh.items, media)) {
                                            invalidateSnapshot()
                                            throw CancellationException("The gallery changed")
                                        }
                                    }, indexStore = SimilarityIndexStore(context.applicationContext))
                            }
                        }
                    }, enabled = similarity.canStart && !loading && !loadFailed && capturedSimilarityScope.size in 2..200 && capturedSimilarityScope.none { it.isVideo },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.similarity_find)) }
                    Text(stringResource(R.string.similarity_selection_limit), style = MaterialTheme.typography.bodySmall)
                }
            } else if (compactChrome) {
                TextButton(onClick = { openMenu() }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min = 48.dp)) { Text(stringResource(R.string.drift_navigate)) }
            } else DriftDock(
                entries = Destination.entries.filter { it != Destination.SETTINGS }.map { DriftDockEntry(it.name, stringResource(it.label), it.mark, it == destination) },
                onNavigate = { name -> changeBrowseScope { destinationName = name; albumId = null; query = ""; selected = emptyList() } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val storageAction = comparison.actionIdentity
            CompareStorageNotice(comparison.state, onRetry = { comparison.retryCleanup(storageAction) })
            val readAction = similarity.actionIdentity
            SimilarityReadNotice(similarity.state, onRetry = { similarity.retry(readAction) })
            if (destination == Destination.CLEANUP) {
                val expectedReview = cleanup.currentReview
                val expectedScan = cleanup.currentScan
                val expectedAction = cleanup.actionIdentity
                val expectedRevision = cleanup.protectionRevision
                CleanupScreen(
                    state = cleanup.display(access, !loading && !loadFailed && !indexing),
                    onStart = {
                        val startedAt = refresh
                        val startedAccess = access
                        cleanup.start(expectedAction, CleanupStartBindings(
                            access = startedAccess, favorites = favorites,
                            isCurrent = { actionLifetime.isActive && refresh == startedAt && context.mediaAccess() == startedAccess && !loading && !loadFailed && !indexing },
                            acceptSnapshot = { fresh ->
                                if (!cleanupSnapshotMatchesGallery(fresh.items, media)) {
                                    invalidateSnapshot()
                                    false
                                } else {
                                    legacyFavoriteMatches = fresh.legacyFavoriteMatches
                                    true
                                }
                            },
                            currentFavorites = { favorites }, currentAliases = { legacyFavoriteMatches },
                            openMember = { item -> if (destinationName == Destination.CLEANUP.name) openViewer(item) },
                        ))
                    },
                    onCancel = { cleanup.stop(expectedScan) },
                    onKeeperChange = { group, member, keep -> cleanup.keep(expectedReview, group, member, keep) },
                    onProtectionChange = { group, protect -> cleanup.protect(expectedReview, group, protect, expectedRevision) },
                    onOpenMember = { group, member -> cleanup.open(expectedReview, group, member) },
                    onChangeAccess = ::changeAccess,
                    onRefresh = ::reload,
                    onRetryProtection = { cleanup.loadProtections() },
                )
            } else if (destination == Destination.SETTINGS) {
                val indexState = similarityIndex.state
                SettingsContent(access, ::changeAccess, ::settings, ::reload, lockState,
                    { if (actionLifetime.isActive) authenticateLock(it) },
                    { if (actionLifetime.isActive) securitySettings() }, indexState,
                    canStartIndex = similarityIndex.canStart && access.level != AccessLevel.NONE,
                    canClearIndex = similarityIndex.canClear,
                    onIndexStart = { expectedIndexAction ->
                        val startRefresh = refresh
                        val granted = access
                        val galleryAtStart = media.toList()
                        fun currentIndexScope() = actionLifetime.isActive && refresh == startRefresh &&
                            destinationName == Destination.SETTINGS.name && !loading && !loadFailed && context.mediaAccess() == granted
                        if (granted.level != AccessLevel.NONE && currentIndexScope()) {
                            similarityIndex.start(expectedIndexAction,
                                AndroidCleanupSourceReader(context.applicationContext, device, granted), favorites.toSet(), ::currentIndexScope) { fresh ->
                                    if (!currentIndexScope()) throw CancellationException("Similarity index scope changed")
                                    if (!cleanupSnapshotMatchesGallery(fresh.items, galleryAtStart)) {
                                        invalidateSnapshot()
                                        throw CancellationException("The gallery changed")
                                    }
                                }
                        }
                    },
                    onIndexStop = { similarityIndex.stop() },
                    onIndexRetry = { expected -> similarityIndex.retryClose(expected) },
                    onIndexClear = { expected -> similarityIndex.clear(expected) })
            } else if (access.level == AccessLevel.NONE) {
                MessagePanel(R.string.access_title, R.string.access_body) {
                    Button(onClick = ::changeAccess) { Text(stringResource(R.string.choose_access)) }
                    TextButton(onClick = ::settings) { Text(stringResource(R.string.open_settings)) }
                }
            } else {
                if (destination == Destination.FAVORITES && !loading && unresolvedFavorites.isNotEmpty()) {
                    Text(stringResource(R.string.favorites_unresolved), Modifier.padding(horizontal = 20.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (indexFailed && !loadFailed) {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.catalogue_failed), style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            TextButton(onClick = ::reload) { Text(stringResource(R.string.retry)) }
                        }
                    }
                } else if (indexing) {
                    Text(stringResource(R.string.catalogue_updating), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (access.level == AccessLevel.PARTIAL) {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.limited_title), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.limited_body), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                            TextButton(onClick = ::changeAccess) { Text(stringResource(R.string.change_access)) }
                        }
                    }
                }
                when {
                    loading && (media.isEmpty() || destination == Destination.FAVORITES) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    loadFailed -> MessagePanel(R.string.load_failed, R.string.permission_settings_note) {
                        Button(onClick = ::reload) { Text(stringResource(R.string.retry)) }
                    }
                    !driftSnapshot.isUsable -> MessagePanel(R.string.load_failed, R.string.permission_settings_note) {
                        Button(onClick = ::reload) { Text(stringResource(R.string.retry)) }
                    }
                    !navigationReady && filtered.isNotEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                    filtered.isEmpty() -> {
                        val unmatchedFavorites = destination == Destination.FAVORITES && favoriteIds.isEmpty() && unresolvedFavorites.isNotEmpty()
                        val emptyTitle = when { query.isNotBlank() -> R.string.no_results_title; mediaFilter == MediaFilter.VIDEOS -> R.string.no_videos_title; mediaFilter == MediaFilter.PHOTOS -> R.string.no_photos_title; unmatchedFavorites -> R.string.favorites_unavailable_title; destination == Destination.FAVORITES -> R.string.no_favorites_title; else -> R.string.empty_title }
                        val emptyBody = when { query.isNotBlank() -> R.string.no_results_body; mediaFilter != MediaFilter.ALL -> R.string.filter_empty_body; unmatchedFavorites -> R.string.favorites_unavailable_body; destination == Destination.FAVORITES -> R.string.no_favorites_body; else -> R.string.empty_body }
                        MessagePanel(emptyTitle, emptyBody) {
                            if (mediaFilter != MediaFilter.ALL) TextButton(onClick = { changeBrowseScope { filterName = MediaFilter.ALL.name; selected = emptyList() } }) { Text(stringResource(R.string.show_all_media)) }
                            else if (unmatchedFavorites) TextButton(onClick = ::reload) { Text(stringResource(R.string.refresh)) }
                            else if (query.isBlank() && destination != Destination.FAVORITES) TextButton(onClick = ::changeAccess) { Text(stringResource(R.string.change_access)) }
                        }
                    }
                    destination == Destination.ALBUMS && albumId == null -> AlbumGrid(filtered, device, refresh, albumsPosition) { id ->
                        if (drift.isCurrent(driftSnapshot)) changeBrowseScope { albumId = id }
                    }
                    !selecting && viewMode == "RIBBON" && focused != null && driftSnapshot.isUsable && drift.isCurrent(driftSnapshot) -> key(driftSnapshot) {
                        val current = filtered[focused.index]
                        DriftRibbon(filtered, driftSnapshot, focusedIntent, focused, device, refresh, current.id in favoriteIds,
                            heading = if (albumId != null) albumTitle else stringResource(if (destination == Destination.FAVORITES) R.string.favorites else R.string.photos),
                            day = drift.dayOf(driftSnapshot, focused.sourceUiToken),
                            rail = drift.rail(driftSnapshot, focused.sourceUiToken, railStart),
                            hasUnknownDate = drift.jump(driftSnapshot, null) != null,
                            canInteract = ::focusedCurrent,
                            onMove = { direction -> if (focusedCurrent()) focus(drift.move(driftSnapshot, focused.sourceUiToken, direction)) },
                            onJump = { date -> if (focusedCurrent()) focus(drift.jump(driftSnapshot, date)) else false },
                            onPage = { start ->
                                if (focusedCurrent() && drift.rail(driftSnapshot, focused.sourceUiToken, start) != null && driftInteraction.page(driftSnapshot, focusedIntent, start)) focusRevision++
                            },
                            onOpen = { if (focusedCurrent()) openViewer(current, driftSnapshot) },
                            onFavorite = {
                                if (focusedCurrent()) galleryActionItems(listOf(current), media, loading, loadFailed, actionLifetime).singleOrNull()?.let {
                                    val matched = favoriteKeysForSource(it.id, favorites, legacyFavoriteMatches)
                                    saveFavorites(if (matched.isEmpty()) favorites + it.id else favorites - matched)
                                }
                            },
                            onSelect = { if (focusedCurrent()) galleryActionItems(listOf(current), media, loading, loadFailed, actionLifetime).singleOrNull()?.let {
                                changeBrowseScope { viewMode = "GRID"; selecting = true; selected = listOf(sourceUiToken(it)) }
                            } },
                            onGrid = { if (focusedCurrent()) changeBrowseScope { viewMode = "GRID" } },
                        )
                    }
                    else -> PhotoGrid(filtered, device, refresh, selectedInView.mapTo(mutableSetOf()) { it.id }, selecting, groupByMonth = mediaSort != MediaSort.NAME,
                        position = if (albumId != null) albumPhotosPosition else if (destination == Destination.FAVORITES) favoritesPosition else photosPosition,
                        onOpen = { item -> if (drift.isCurrent(driftSnapshot)) openViewer(item, driftSnapshot) },
                        onToggle = { item -> if (drift.isCurrent(driftSnapshot)) galleryActionItems(listOf(item), media, loading, loadFailed, actionLifetime).singleOrNull()?.let { current -> similarity.invalidate(); comparison.invalidate(); val token = sourceUiToken(current); selected = if (token in selected) selected - token else selected + token } },
                        onStartSelection = { item -> if (drift.isCurrent(driftSnapshot)) galleryActionItems(listOf(item), media, loading, loadFailed, actionLifetime).singleOrNull()?.let { current ->
                            changeBrowseScope { selecting = true; viewMode = "GRID"; val token = sourceUiToken(current); if (token !in selected) selected = selected + token }
                        } },
                    )
                }
            }
        }
    }

    val activeMenu = menuRequest
    if (activeMenu != null && menu.isCurrent(activeMenu)) {
        AlertDialog(onDismissRequest = { if (menu.close(activeMenu)) menuRequest = null },
            title = { Text(stringResource(R.string.drift_navigate)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Destination.entries.forEach { entry ->
                        TextButton(onClick = {
                            if (menu.close(activeMenu)) changeBrowseScope { destinationName = entry.name; albumId = null; query = ""; selected = emptyList() }
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(entry.label)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { if (menu.close(activeMenu)) menuRequest = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.close)) } },
        )
    }

    val activeOptions = optionsRequest
    if (activeOptions != null && options.isCurrent(activeOptions) && canBrowse) {
        fun editScope(change: () -> Unit) { options.edit(activeOptions) { changeBrowseScope(activeOptions, change) } }
        AlertDialog(onDismissRequest = { if (options.close(activeOptions)) optionsRequest = null },
            title = { Text(stringResource(R.string.drift_options)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = query, onValueChange = { next -> editScope { query = next; selected = emptyList() } },
                        label = { Text(stringResource(R.string.search_media)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { editScope { query = ""; selected = emptyList() } }) { MarkIcon(Mark.CLOSE, stringResource(R.string.clear_search)) } }) else null,
                        modifier = Modifier.fillMaxWidth())
                    LibraryControls(mediaFilter, mediaSort,
                        onFilter = { next -> if (next != mediaFilter) editScope { filterName = next.name; selected = emptyList() } },
                        onSort = { next -> if (next != mediaSort) editScope { sortName = next.name } })
                    if (destination != Destination.ALBUMS || albumId != null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = viewMode == "RIBBON", onClick = { editScope { viewMode = "RIBBON"; selecting = false; selected = emptyList() } }, label = { Text(stringResource(R.string.drift_ribbon)) }, modifier = Modifier.heightIn(min = 48.dp))
                            FilterChip(selected = viewMode == "GRID", onClick = { editScope { viewMode = "GRID" } }, label = { Text(stringResource(R.string.drift_grid)) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                        TextButton(onClick = {
                            if (!loading && !loadFailed && visibleMedia.isNotEmpty() && options.close(activeOptions)) changeBrowseScope { selecting = true; viewMode = "GRID" }
                        }, enabled = !loading && !loadFailed && filtered.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.select)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { if (options.close(activeOptions)) optionsRequest = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.done)) } },
        )
    }

    val viewerEntry = remember(viewerRevision) { viewers.current }
    if (viewerEntry != null && viewers.isCurrent(viewerEntry)) visibleMedia.firstOrNull { sourceUiToken(it) == viewerEntry.sourceToken }?.let { item ->
        key(viewerEntry) {
            MediaViewer(item, device, refresh, item.id in favoriteIds,
                onClose = { if (viewers.close(viewerEntry)) viewerRevision++ },
                onShare = { if (viewers.isCurrent(viewerEntry)) share(listOf(item)) },
                onFavorite = {
                    if (viewers.isCurrent(viewerEntry)) galleryActionItems(listOf(item), media, loading, loadFailed, actionLifetime).singleOrNull()?.let { current ->
                        val matched = favoriteKeysForSource(current.id, favorites, legacyFavoriteMatches)
                        saveFavorites(if (matched.isEmpty()) favorites + current.id else favorites - matched)
                    }
                },
                canPlay = { viewers.isCurrent(viewerEntry) && galleryActionItems(listOf(item), media, loading, loadFailed, actionLifetime).isNotEmpty() },
            )
        }
    }
    val similarState = similarity.state
    val similarResult = similarState.result
    val similarRequest = similarState.request
    // One authorized metadata pass per result/gallery projection, rather than per displayed pair.
    val pairDisplays = remember(similarRequest, similarResult, media, loading, loadFailed) {
        if (similarResult == null || loading || loadFailed) emptyList() else {
            val expected = similarResult.pairs.flatMap { listOf(it.first, it.second) }.distinctBy(::sourceUiToken)
            val current = similarityCurrentPhotos(expected, media).associateBy(::sourceUiToken)
            similarResult.pairs.mapNotNull { pair ->
                val first = current[sourceUiToken(pair.first)]
                val second = current[sourceUiToken(pair.second)]
                if (first != null && second != null) SimilarityPairDisplay(pair, first, second) else null
            }
        }
    }
    val compareState = comparison.state
    val originCompareAction = comparison.actionIdentity
    val reviewReadAction = similarity.actionIdentity
    if (similarState.visible) key(similarRequest, similarResult) {
        SimilarityScreen(similarState, pairDisplays, device, refresh, comparison.canStart,
            onCompare = { pair ->
                if (similarRequest != null && similarResult != null) {
                    val granted = access
                    similarityCompare.startComparison(similarRequest, similarResult, pair, comparison, originCompareAction,
                        { similarity.isCurrent(similarRequest, similarResult) },
                        { similarity.resolve(similarRequest, similarResult, pair)?.let { galleryActionItems(it, media, loading, loadFailed, actionLifetime) }?.takeIf { it.size == 2 } }) { photos, checkOrigin ->
                        ComparePreparedWork(context.applicationContext, device, photos, granted, favorites.toSet(), checkOrigin)
                    }
                }
            },
            onClose = { similarity.close(similarRequest) }, onStop = { similarity.stop(similarRequest) },
            onRetry = { similarity.retry(reviewReadAction) },
            comparisonNotice = { CompareStorageNotice(compareState, onRetry = { comparison.retryCleanup(originCompareAction) }) },
        )
    }
    if (compareState.visible) {
        val request = compareState.request
        CompareScreen(compareState,
            onClose = { comparison.close(request) },
            onStop = { comparison.stop(request) },
            onChange = { zoom, x, y -> comparison.change(request, zoom, x, y) },
            onReset = { comparison.reset(request) },
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
private fun PhotoGrid(items: List<MediaItem>, device: DeviceMedia, generation: Int, selected: Set<String>, selecting: Boolean, groupByMonth: Boolean, position: LazyGridState, onOpen: (MediaItem) -> Unit, onToggle: (MediaItem) -> Unit, onStartSelection: (MediaItem) -> Unit) {
    val groups = remember(items, groupByMonth) { if (groupByMonth) items.groupBy { monthKey(it.capturedAt) } else linkedMapOf("" to items) }
    LazyVerticalGrid(columns = GridCells.Adaptive(108.dp), state = position, contentPadding = PaddingValues(start = 3.dp, end = 3.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        groups.forEach { (month, media) ->
            if (groupByMonth) item(key = "month:$month", span = { GridItemSpan(maxLineSpan) }) {
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
                        if (isSelected) MarkIcon(Mark.SELECT, null, Modifier.size(19.dp), MaterialTheme.colorScheme.onPrimary)
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
private fun SettingsContent(
    access: MediaAccess, changeAccess: () -> Unit, settings: () -> Unit, refresh: () -> Unit,
    lockState: AppLockUiState, authenticateLock: (LockAction) -> Unit, securitySettings: () -> Unit,
    indexState: SimilarityIndexUiState, canStartIndex: Boolean, canClearIndex: Boolean,
    onIndexStart: (SimilarityIndexAction) -> Unit, onIndexStop: () -> Unit,
    onIndexRetry: (SimilarityIndexAction) -> Unit, onIndexClear: (SimilarityIndexAction) -> Unit,
) {
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
        SimilarityIndexSettings(indexState, canStartIndex, canClearIndex,
            onStart = onIndexStart, onStop = onIndexStop, onRetryClose = onIndexRetry, onClear = onIndexClear)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        AppLockSettings(lockState, authenticateLock, securitySettings)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.privacy_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.catalogue_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

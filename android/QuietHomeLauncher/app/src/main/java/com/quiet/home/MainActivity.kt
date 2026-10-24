package com.quiet.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.graphics.TransformOrigin
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private data class LauncherApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap
)

private enum class AppLayout(val title: String) {
    ORBIT("Orbit"),
    GRID("Grid"),
    LIST("List")
}

private enum class HomeLook(
    val title: String,
    val background: Color,
    val foreground: Color,
    val muted: Color,
    val accent: Color,
    val surface: Color,
    val orb: Color,
    val orbInk: Color,
    val lightSystemBars: Boolean
) {
    STILL(
        "Still", Color(0xFF191B1A), Color(0xFFF0EEE9), Color(0xFFAAA8A2),
        Color(0xFFC4B9E3), Color(0xFF252726), Color(0xFFD8D1E7),
        Color(0xFF29272E), false
    ),
    PAPER(
        "Paper", Color(0xFFF0ECE3), Color(0xFF292822), Color(0xFF817C71),
        Color(0xFFA96042), Color(0xFFE8E1D7), Color(0xFF292A26),
        Color(0xFFF2EEE5), true
    ),
    GROVE(
        "Grove", Color(0xFF1B2922), Color(0xFFE9ECDF), Color(0xFFA4AFA0),
        Color(0xFFC2D1AE), Color(0xFF26332C), Color(0xFFC9D5B9),
        Color(0xFF243026), false
    )
}

class MainActivity : ComponentActivity() {
    private var resumeSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        setContent {
            MaterialTheme {
                QuietHome(
                    refreshSignal = resumeSignal,
                    onOpenApp = { packageName ->
                        try {
                            packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
                                startActivity(intent)
                                true
                            } ?: false
                        } catch (_: ActivityNotFoundException) {
                            false
                        } catch (_: SecurityException) {
                            false
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeSignal += 1
    }
}

@Composable
private fun QuietHome(refreshSignal: Int, onOpenApp: (String) -> Boolean) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("quiet_home_preferences", Context.MODE_PRIVATE)
    }
    var apps by remember { mutableStateOf(emptyList<LauncherApp>()) }
    var favoritePackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var favoritesLoaded by remember { mutableStateOf(false) }
    var layout by remember(preferences) {
        mutableStateOf(
            runCatching {
                AppLayout.valueOf(preferences.getString("app_layout", AppLayout.ORBIT.name) ?: AppLayout.ORBIT.name)
            }.getOrDefault(AppLayout.ORBIT)
        )
    }
    var look by remember(preferences) {
        mutableStateOf(
            runCatching {
                HomeLook.valueOf(preferences.getString("home_look", HomeLook.STILL.name) ?: HomeLook.STILL.name)
            }.getOrDefault(HomeLook.STILL)
        )
    }
    var drawerOpen by remember { mutableStateOf(false) }
    var drawerPinned by remember { mutableStateOf(false) }
    var hoverSuppressed by remember { mutableStateOf(false) }
    var highlightedPackage by remember { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var launchInProgress by remember { mutableStateOf(false) }
    val drawerVisibilityState = remember { MutableTransitionState(false) }
    var wallpaperUri by remember(preferences) {
        mutableStateOf(preferences.getString("wallpaper_uri", null))
    }
    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val coroutineScope = rememberCoroutineScope()
    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val image = withContext(Dispatchers.IO) { loadWallpaper(context, uri.toString()) }
                if (image == null) {
                    Toast.makeText(context, "Couldn't load that image.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val grantTaken = runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }.isSuccess
                if (!grantTaken) {
                    Toast.makeText(context, "Couldn't keep access to that image.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val previousUri = wallpaperUri?.let(Uri::parse)
                if (previousUri != null && previousUri != uri) {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            previousUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
                wallpaper = image
                wallpaperUri = uri.toString()
                preferences.edit().putString("wallpaper_uri", wallpaperUri).apply()
            }
        }
    }

    val appHitBounds = remember { mutableStateMapOf<String, Rect>() }
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var orbCenter by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    LaunchedEffect(refreshSignal) {
        if (refreshSignal == 0) return@LaunchedEffect
        val refreshedApps = withContext(Dispatchers.IO) {
            loadLauncherApps(context)
        }
        if (refreshedApps.map { it.packageName } != apps.map { it.packageName }) {
            appHitBounds.clear()
        }
        apps = refreshedApps
    }
    LaunchedEffect(apps) {
        if (apps.isNotEmpty() && !favoritesLoaded) {
            val availablePackages = apps.mapTo(mutableSetOf()) { it.packageName }
            val saved = preferences.getStringSet("favorite_packages", null)
            favoritePackages = if (saved == null) {
                apps.take(9).mapTo(mutableSetOf()) { it.packageName }
            } else {
                saved.filterTo(mutableSetOf()) { it in availablePackages }
            }
            favoritesLoaded = true
            preferences.edit().putStringSet("favorite_packages", favoritePackages.toSet()).apply()
        } else if (apps.isNotEmpty() && favoritesLoaded) {
            val availablePackages = apps.mapTo(mutableSetOf()) { it.packageName }
            val validFavorites = favoritePackages.intersect(availablePackages)
            if (validFavorites != favoritePackages) {
                favoritePackages = validFavorites
                preferences.edit().putStringSet("favorite_packages", validFavorites.toSet()).apply()
            }
        }
    }
    LaunchedEffect(wallpaperUri) {
        wallpaper = wallpaperUri?.let { uri ->
            withContext(Dispatchers.IO) { loadWallpaper(context, uri) }
        }
    }

    val configuration = LocalConfiguration.current
    val launcherWidthDp = configuration.screenWidthDp - 44
    val orbitVisibleLimit = when {
        launcherWidthDp < 190 -> 3
        else -> 9
    }
    val visibleApps by remember(apps, favoritePackages, layout, orbitVisibleLimit) {
        derivedStateOf {
            if (layout == AppLayout.ORBIT) {
                apps.sortedWith(
                    compareByDescending<LauncherApp> { it.packageName in favoritePackages }
                        .thenBy { it.label.lowercase(Locale.getDefault()) }
                ).take(orbitVisibleLimit)
            } else {
                apps
            }
        }
    }
    val compact = configuration.screenHeightDp < 700
    val stageHeight = when {
        configuration.screenHeightDp < 470 -> 220.dp
        compact -> 244.dp
        else -> 320.dp
    }
    val stageTopGap = (configuration.screenHeightDp - 660).coerceIn(8, 94).dp

    fun setLayout(next: AppLayout) {
        appHitBounds.clear()
        layout = next
        preferences.edit().putString("app_layout", next.name).apply()
        drawerOpen = false
        drawerPinned = false
        highlightedPackage = null
        hoverSuppressed = false
    }

    fun openSettings() {
        drawerOpen = false
        drawerPinned = false
        highlightedPackage = null
        settingsOpen = true
    }

    fun launchSelectedApp(packageName: String) {
        if (launchInProgress) return
        launchInProgress = true
        val foldOrbit = layout == AppLayout.ORBIT && !settingsOpen
        drawerOpen = false
        drawerPinned = false
        highlightedPackage = packageName
        hoverSuppressed = true
        coroutineScope.launch {
            if (foldOrbit) {
                snapshotFlow {
                    drawerVisibilityState.isIdle && !drawerVisibilityState.currentState
                }.first { it }
            }
            if (!onOpenApp(packageName)) {
                Toast.makeText(context, "This app can't be opened right now.", Toast.LENGTH_SHORT).show()
            }
            highlightedPackage = null
            launchInProgress = false
        }
    }

    BackHandler(enabled = settingsOpen || layout != AppLayout.ORBIT || drawerOpen) {
        if (settingsOpen) {
            settingsOpen = false
        } else if (layout != AppLayout.ORBIT) {
            setLayout(AppLayout.ORBIT)
        } else if (drawerOpen) {
            drawerOpen = false
            drawerPinned = false
            highlightedPackage = null
            hoverSuppressed = true
        }
    }

    val timeText = remember(now) {
        SimpleDateFormat("H:mm", Locale.getDefault()).format(Date(now))
    }
    val dateText = remember(now) {
        SimpleDateFormat("EEEE · d MMMM", Locale.getDefault()).format(Date(now))
    }

    val currentOrbCenter = rememberUpdatedState(orbCenter)
    val currentAppBounds = rememberUpdatedState(appHitBounds.toMap())
    val currentDrawerOpen = rememberUpdatedState(drawerOpen)
    val currentDrawerPinned = rememberUpdatedState(drawerPinned)
    val currentCanUseOrbit = rememberUpdatedState(layout == AppLayout.ORBIT && !settingsOpen && !launchInProgress)
    val openForGesture = rememberUpdatedState<(Boolean) -> Unit> { wasAlreadyOpen ->
        drawerOpen = true
        drawerPinned = true
        if (!wasAlreadyOpen) highlightedPackage = null
    }
    val highlightFromGesture = rememberUpdatedState<(String?) -> Unit> { packageName ->
        highlightedPackage = packageName
    }
    val finishGesture = rememberUpdatedState<(String?, Boolean, Boolean) -> Unit> { packageName, startedOnOrb, wasAlreadyOpen ->
        if (packageName != null) {
            launchSelectedApp(packageName)
        } else {
            highlightedPackage = null
            drawerOpen = !wasAlreadyOpen
            drawerPinned = drawerOpen
        }
    }
    val cancelGesture = rememberUpdatedState<(Boolean, Boolean) -> Unit> { wasAlreadyOpen, wasPinned ->
        highlightedPackage = null
        drawerOpen = wasAlreadyOpen
        drawerPinned = wasPinned
    }

    val density = LocalDensity.current
    val orbHitRadius = with(density) { 47.dp.toPx() }
    val appHitPadding = with(density) { 12.dp.toPx() }
    val screenView = LocalView.current
    SideEffect {
        drawerVisibilityState.targetState = drawerOpen
        val window = (screenView.context as? Activity)?.window
        if (window != null) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            val controller = WindowInsetsControllerCompat(window, screenView)
            controller.isAppearanceLightStatusBars = look.lightSystemBars
            controller.isAppearanceLightNavigationBars = look.lightSystemBars
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(look.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .onGloballyPositioned { coordinates ->
                if (rootCoordinates !== coordinates) rootCoordinates = coordinates
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                    if (!currentCanUseOrbit.value) return@awaitEachGesture
                    val currentCenter = currentOrbCenter.value
                    val startedOnOrb = currentCenter?.let { center ->
                        val dx = down.position.x - center.x
                        val dy = down.position.y - center.y
                        dx * dx + dy * dy <= orbHitRadius * orbHitRadius
                    } == true
                    if (!startedOnOrb) return@awaitEachGesture

                    val wasAlreadyOpen = currentDrawerOpen.value
                    val wasPinned = currentDrawerPinned.value
                    openForGesture.value(wasAlreadyOpen)
                    down.consume()
                    var resolved = false
                    try {
                        while (!resolved) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val additionalPointers = event.changes.filter { it.id != down.id }
                            additionalPointers.forEach { it.consume() }
                            if (additionalPointers.any { it.pressed }) {
                                event.changes.forEach { it.consume() }
                                cancelGesture.value(wasAlreadyOpen, wasPinned)
                                resolved = true
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (event.type == PointerEventType.Release && change.previousPressed) {
                                    val nearest = nearestApp(
                                        point = change.position,
                                        bounds = currentAppBounds.value,
                                        padding = appHitPadding
                                    )
                                    finishGesture.value(nearest, startedOnOrb, wasAlreadyOpen)
                                } else {
                                    cancelGesture.value(wasAlreadyOpen, wasPinned)
                                }
                                change.consume()
                                resolved = true
                            } else {
                                val nearest = nearestApp(
                                    point = change.position,
                                    bounds = currentAppBounds.value,
                                    padding = appHitPadding
                                )
                                highlightFromGesture.value(nearest)
                                change.consume()
                            }
                        }
                    } finally {
                        if (!resolved) cancelGesture.value(wasAlreadyOpen, wasPinned)
                    }
                }
            }
    ) {
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper!!,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.fillMaxSize().background(look.background.copy(alpha = .68f)))
        }

        when {
            settingsOpen -> SettingsScreen(
                layout = layout,
                look = look,
                wallpaper = wallpaper,
                wallpaperSelected = wallpaperUri != null,
                onBack = { settingsOpen = false },
                onLayoutSelected = {
                    setLayout(it)
                    settingsOpen = false
                },
                onLookSelected = {
                    look = it
                    preferences.edit().putString("home_look", it.name).apply()
                },
                onChooseWallpaper = { wallpaperPicker.launch(arrayOf("image/*")) },
                onClearWallpaper = {
                    wallpaperUri?.let { oldUri ->
                        runCatching {
                            context.contentResolver.releasePersistableUriPermission(
                                Uri.parse(oldUri),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                    }
                    wallpaperUri = null
                    wallpaper = null
                    preferences.edit().remove("wallpaper_uri").apply()
                }
            )

            layout != AppLayout.ORBIT -> AppBrowserScreen(
                apps = apps,
                layout = layout,
                favoritePackages = favoritePackages,
                look = look,
                onBack = { setLayout(AppLayout.ORBIT) },
                onSettings = ::openSettings,
                onSelect = ::launchSelectedApp,
                onToggleFavorite = { packageName ->
                    val next = favoritePackages.toMutableSet()
                    if (packageName in next) {
                        next.remove(packageName)
                    } else if (next.size < 9) {
                        next.add(packageName)
                    } else {
                        Toast.makeText(context, "Orbit can hold nine favourites.", Toast.LENGTH_SHORT).show()
                    }
                    favoritePackages = next
                    preferences.edit().putStringSet("favorite_packages", next.toSet()).apply()
                }
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 15.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Column(Modifier.blur(if (drawerOpen) 7.dp else 0.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = dateText.uppercase(Locale.getDefault()),
                            color = look.muted,
                            fontSize = 9.sp,
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "QUIET MODE",
                                color = look.muted,
                                fontSize = 8.sp,
                                letterSpacing = 1.3.sp,
                                modifier = Modifier
                                    .border(1.dp, look.muted.copy(alpha = .24f), CircleShape)
                                    .padding(horizontal = 9.dp, vertical = 5.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            SettingsButton(look = look, onClick = ::openSettings)
                        }
                    }

                    Text(
                        text = timeText,
                        color = look.foreground,
                        fontSize = if (compact) 51.sp else 62.sp,
                        lineHeight = if (compact) 55.sp else 66.sp,
                        letterSpacing = (-4).sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.padding(top = if (compact) 11.dp else 21.dp)
                    )
                    Text(
                        text = "A little space for today",
                        color = look.muted,
                        fontSize = 11.sp,
                        letterSpacing = .7.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (compact) 14.dp else 22.dp)
                            .border(1.dp, look.muted.copy(alpha = .22f), RoundedCornerShape(17.dp))
                            .background(look.surface.copy(alpha = .52f), RoundedCornerShape(17.dp))
                            .padding(horizontal = 16.dp, vertical = if (compact) 11.dp else 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("A MOMENT TO YOURSELF", color = look.muted, fontSize = 8.sp, letterSpacing = 1.5.sp)
                            Text(
                                "Take the day as it comes.",
                                color = look.foreground,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Text(
                                "Nothing urgent. Just the essentials.",
                                color = look.muted,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(top = 5.dp)
                            )
                        }
                        Text(
                            "↗",
                            color = look.accent,
                            fontSize = 19.sp,
                            modifier = Modifier
                                .size(34.dp)
                                .border(1.dp, look.muted.copy(alpha = .3f), CircleShape)
                                .wrapContentSize()
                        )
                    }
                    Spacer(Modifier.height(stageTopGap))
                }

                AppStage(
                    apps = visibleApps,
                    drawerVisibilityState = drawerVisibilityState,
                    look = look,
                    stageHeight = stageHeight,
                    drawerOpen = drawerOpen,
                    drawerPinned = drawerPinned,
                    highlightedPackage = highlightedPackage,
                    rootCoordinates = rootCoordinates,
                    appHitBounds = appHitBounds,
                    onOrbPosition = { orbCenter = it },
                    onHover = { packageName, hovering ->
                        if (hovering) highlightedPackage = packageName
                        else if (highlightedPackage == packageName) highlightedPackage = null
                        if (hovering) drawerOpen = true
                    },
                    onHoverLauncher = { hovering ->
                        if (hovering) {
                            if (!drawerPinned && !hoverSuppressed) drawerOpen = true
                        } else {
                            hoverSuppressed = false
                            if (!drawerPinned) {
                                drawerOpen = false
                                highlightedPackage = null
                            }
                        }
                    },
                    onSelect = ::launchSelectedApp,
                    onToggleDrawer = {
                        drawerOpen = !drawerOpen
                        drawerPinned = drawerOpen
                        highlightedPackage = null
                        hoverSuppressed = !drawerOpen
                    }
                )
            }
        }
    }
}

@Composable
private fun AppStage(
    apps: List<LauncherApp>,
    drawerVisibilityState: MutableTransitionState<Boolean>,
    look: HomeLook,
    stageHeight: Dp,
    drawerOpen: Boolean,
    drawerPinned: Boolean,
    highlightedPackage: String?,
    rootCoordinates: LayoutCoordinates?,
    appHitBounds: MutableMap<String, Rect>,
    onOrbPosition: (Offset) -> Unit,
    onHover: (String, Boolean) -> Unit,
    onHoverLauncher: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onToggleDrawer: () -> Unit
) {
    val orbInteraction = remember { MutableInteractionSource() }
    val orbHovered by orbInteraction.collectIsHoveredAsState()
    val menuInteraction = remember { MutableInteractionSource() }
    val menuHovered by menuInteraction.collectIsHoveredAsState()
    LaunchedEffect(orbHovered, menuHovered, drawerPinned) {
        if (orbHovered || menuHovered) {
            onHoverLauncher(true)
        } else {
            delay(140)
            onHoverLauncher(false)
        }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(stageHeight)
            .animateContentSize()
    ) {
        val maxHeight = maxHeight
        val compactOrbit = maxHeight < 270.dp
        val orbitScale = if (maxWidth < 190.dp) 1.16f else 1.26f
        val orbitItemWidth = ((maxWidth - 12.dp) / 5 / orbitScale)
            .coerceAtLeast(28.dp)
            .coerceAtMost(48.dp)
        val orbitIconSize = (orbitItemWidth - 4.dp)
            .coerceAtLeast(24.dp)
            .coerceAtMost(if (compactOrbit) 37.dp else 43.dp)
        val horizontalSpread = ((maxWidth - orbitItemWidth * orbitScale) / 2 - 2.dp)
            .coerceAtLeast(0.dp)
            .coerceAtMost(150.dp)
        val peakLift = if (compactOrbit) maxHeight - 70.dp else 222.dp
        val compactEdgeY = if (maxHeight < 230.dp) 78.dp else 95.dp
        val edgeLift = if (compactOrbit) maxHeight - compactEdgeY else 157.dp
        val orbitPoints = apps.mapIndexed { index, _ ->
            if (index < 5) {
                val primaryCount = minOf(apps.size, 5)
                val normalized = if (primaryCount == 1) 0f else
                    (index.toFloat() / (primaryCount - 1)) * 2f - 1f
                val spreadFactor = when (primaryCount) {
                    1 -> 0f
                    2 -> .54f
                    3 -> .76f
                    4 -> .90f
                    else -> 1f
                }
                val progress = abs(normalized) * spreadFactor
                val x = horizontalSpread * normalized * spreadFactor
                val lift = peakLift - ((peakLift - edgeLift) * progress)
                x to (maxHeight - lift)
            } else {
                val upperCount = minOf(apps.size - 5, 4)
                val upperIndex = index - 5
                val normalized = if (upperCount == 1) 0f else
                    (upperIndex.toFloat() / (upperCount - 1)) * 2f - 1f
                val lift = 1.dp + (5.dp * (1f - abs(normalized)))
                (horizontalSpread * normalized * .82f) to lift
            }
        }

        AnimatedVisibility(
            visibleState = drawerVisibilityState,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(155)) + scaleIn(
                initialScale = .08f,
                animationSpec = spring(dampingRatio = .78f, stiffness = 260f),
                transformOrigin = TransformOrigin(.5f, 1f)
            ),
            exit = fadeOut(tween(245)) + scaleOut(
                targetScale = .055f,
                animationSpec = tween(280),
                transformOrigin = TransformOrigin(.5f, 1f)
            )
        ) {
            Box(Modifier.fillMaxSize().hoverable(menuInteraction)) {
                Box(Modifier.fillMaxSize().background(look.background.copy(alpha = .18f)))
                if (apps.isEmpty()) {
                    Text(
                        "Apps are loading.",
                        color = look.muted,
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp)
                    )
                } else {
                    apps.forEachIndexed { index, app ->
                        val (x, y) = orbitPoints[index]
                        AppOrbitItem(
                            app = app,
                            look = look,
                            selected = highlightedPackage == app.packageName,
                            onHover = onHover,
                            onSelect = onSelect,
                            itemWidth = orbitItemWidth,
                            iconSize = orbitIconSize,
                            hoverScale = orbitScale,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(x = x, y = y),
                            onPositioned = { coordinates ->
                                saveBounds(rootCoordinates, appHitBounds, app.packageName, coordinates)
                            }
                        )
                    }
                }
            }
        }

        OrbButton(
            look = look,
            expanded = drawerOpen,
            interaction = orbInteraction,
            onClick = onToggleDrawer,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .onGloballyPositioned { coordinates ->
                    val root = rootCoordinates
                    if (root != null && root.isAttached) {
                        val center = root.localPositionOf(
                            coordinates,
                            Offset(coordinates.size.width / 2f, coordinates.size.height / 2f)
                        )
                        onOrbPosition(center)
                    }
                }
        )
    }
}

@Composable
private fun AppBrowserScreen(
    apps: List<LauncherApp>,
    layout: AppLayout,
    favoritePackages: Set<String>,
    look: HomeLook,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onSelect: (String) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    var query by remember(layout) { mutableStateOf("") }
    val shownApps = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 15.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹",
                color = look.foreground,
                fontSize = 32.sp,
                lineHeight = 34.sp,
                modifier = Modifier
                    .size(46.dp)
                    .semantics { contentDescription = "Back to home"; role = Role.Button }
                    .clickable(role = Role.Button, onClick = onBack)
                    .wrapContentSize(Alignment.CenterStart)
            )
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text("All apps", color = look.foreground, fontSize = 23.sp, fontWeight = FontWeight.Light)
                Text(
                    if (layout == AppLayout.GRID) "GRID" else "LIST",
                    color = look.muted,
                    fontSize = 8.sp,
                    letterSpacing = 1.7.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            SettingsButton(look = look, onClick = onSettings)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(look.surface.copy(alpha = .8f))
                .border(1.dp, look.muted.copy(alpha = .12f), RoundedCornerShape(15.dp))
                .padding(horizontal = 15.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (query.isEmpty()) {
                Text("Search apps", color = look.muted, fontSize = 13.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = look.foreground, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Search apps"
                }
            )
        }

        if (shownApps.isEmpty()) {
            EmptyAppsMessage(look, Modifier.weight(1f).wrapContentSize(Alignment.Center))
        } else if (layout == AppLayout.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(76.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 13.dp),
                contentPadding = PaddingValues(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(shownApps, key = { it.packageName }) { app ->
                    BrowserGridItem(
                        app = app,
                        look = look,
                        favorite = app.packageName in favoritePackages,
                        onSelect = { onSelect(app.packageName) },
                        onToggleFavorite = { onToggleFavorite(app.packageName) }
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 13.dp),
                contentPadding = PaddingValues(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(shownApps, key = { it.packageName }) { app ->
                    BrowserListItem(
                        app = app,
                        look = look,
                        favorite = app.packageName in favoritePackages,
                        onSelect = { onSelect(app.packageName) },
                        onToggleFavorite = { onToggleFavorite(app.packageName) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserGridItem(
    app: LauncherApp,
    look: HomeLook,
    favorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val favoriteInteraction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        if (hovered) 1.09f else 1f,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "browser-grid-scale"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, onClick = onSelect),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterEnd) {
            Text(
                if (favorite) "★" else "☆",
                color = if (favorite) look.accent else look.muted,
                fontSize = 12.sp,
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = if (favorite) "Remove ${app.label} from favourites" else "Add ${app.label} to favourites"
                        role = Role.Button
                    }
                    .clickable(interactionSource = favoriteInteraction, indication = null) { onToggleFavorite() }
                    .wrapContentSize(Alignment.Center)
            )
        }
        AppIcon(app, look, size = 50.dp, corner = 15.dp)
        Text(
            app.label,
            color = if (hovered) look.accent else look.foreground,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun BrowserListItem(
    app: LauncherApp,
    look: HomeLook,
    favorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val favoriteInteraction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        if (hovered) 1.025f else 1f,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "browser-list-scale"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(RoundedCornerShape(16.dp))
            .background(if (hovered) look.surface else look.surface.copy(alpha = .67f))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, onClick = onSelect)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app, look, size = 40.dp, corner = 12.dp)
        Text(
            app.label,
            color = if (hovered) look.accent else look.foreground,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp)
        )
        Text(
            if (favorite) "★" else "☆",
            color = if (favorite) look.accent else look.muted,
            fontSize = 17.sp,
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = if (favorite) "Remove ${app.label} from favourites" else "Add ${app.label} to favourites"
                    role = Role.Button
                }
                .clickable(interactionSource = favoriteInteraction, indication = null) { onToggleFavorite() }
                .wrapContentSize(Alignment.Center)
        )
    }
}

@Composable
private fun SettingsScreen(
    layout: AppLayout,
    look: HomeLook,
    wallpaper: ImageBitmap?,
    wallpaperSelected: Boolean,
    onBack: () -> Unit,
    onLayoutSelected: (AppLayout) -> Unit,
    onLookSelected: (HomeLook) -> Unit,
    onChooseWallpaper: () -> Unit,
    onClearWallpaper: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                color = look.foreground,
                fontSize = 32.sp,
                lineHeight = 34.sp,
                modifier = Modifier
                    .size(46.dp)
                    .semantics { contentDescription = "Back"; role = Role.Button }
                    .clickable(role = Role.Button, onClick = onBack)
                    .wrapContentSize(Alignment.CenterStart)
            )
            Text("Settings", color = look.foreground, fontSize = 23.sp, fontWeight = FontWeight.Light)
        }

        Text(
            "APP VIEW",
            color = look.muted,
            fontSize = 9.sp,
            letterSpacing = 1.8.sp,
            modifier = Modifier.padding(top = 24.dp, bottom = 9.dp)
        )
        Column(Modifier.selectableGroup()) {
            AppLayout.entries.forEach { option ->
                val description = when (option) {
                    AppLayout.ORBIT -> "A fan of apps around the circle"
                    AppLayout.GRID -> "A full screen grid"
                    AppLayout.LIST -> "A full screen list"
                }
                val interaction = remember(option) { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 7.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (layout == option) look.surface else look.surface.copy(alpha = .42f))
                        .border(1.dp, look.muted.copy(alpha = if (layout == option) .30f else .12f), RoundedCornerShape(16.dp))
                        .selectable(
                            selected = layout == option,
                            interactionSource = interaction,
                            indication = null,
                            role = Role.RadioButton,
                            onClick = { onLayoutSelected(option) }
                        )
                        .padding(horizontal = 15.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(option.title, color = look.foreground, fontSize = 14.sp)
                        Text(description, color = look.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    if (layout == option) Text("✓", color = look.accent, fontSize = 16.sp)
                }
            }
        }

        Text(
            "APPEARANCE",
            color = look.muted,
            fontSize = 9.sp,
            letterSpacing = 1.8.sp,
            modifier = Modifier.padding(top = 21.dp, bottom = 9.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            HomeLook.entries.forEach { option ->
                ChoicePill(
                    label = option.title,
                    selected = look == option,
                    look = option,
                    onClick = { onLookSelected(option) }
                )
            }
        }

        Text(
            "WALLPAPER",
            color = look.muted,
            fontSize = 9.sp,
            letterSpacing = 1.8.sp,
            modifier = Modifier.padding(top = 23.dp, bottom = 9.dp)
        )
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper,
                contentDescription = "Selected wallpaper",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(142.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, look.muted.copy(alpha = .18f), RoundedCornerShape(16.dp))
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsAction("Choose wallpaper", look, onChooseWallpaper, Modifier.weight(1f))
            if (wallpaperSelected) {
                SettingsAction("Remove", look, onClearWallpaper, Modifier)
            }
        }
    }
}

@Composable
private fun SettingsAction(
    label: String,
    look: HomeLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(look.surface)
            .border(1.dp, look.muted.copy(alpha = .17f), RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = look.foreground, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun SettingsButton(look: HomeLook, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = "Settings"; role = Role.Button }
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(18.dp)) {
            val color = look.muted
            val width = 1.dp.toPx()
            listOf(.2f, .5f, .8f).forEachIndexed { index, y ->
                val centerY = size.height * y
                drawLine(color.copy(alpha = .9f), Offset(0f, centerY), Offset(size.width, centerY), width)
                val centerX = size.width * listOf(.35f, .7f, .48f)[index]
                drawCircle(color, radius = 2.dp.toPx(), center = Offset(centerX, centerY))
            }
        }
    }
}

@Composable
private fun AppOrbitItem(
    app: LauncherApp,
    look: HomeLook,
    selected: Boolean,
    onHover: (String, Boolean) -> Unit,
    onSelect: (String) -> Unit,
    itemWidth: Dp = 60.dp,
    iconSize: Dp = 43.dp,
    hoverScale: Float = 1.17f,
    modifier: Modifier = Modifier,
    onPositioned: (LayoutCoordinates?) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val currentOnPositioned = rememberUpdatedState(onPositioned)
    val scale by animateFloatAsState(
        targetValue = if (selected || hovered) hoverScale else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "orbit-icon-scale"
    )
    LaunchedEffect(hovered) { onHover(app.packageName, hovered) }
    DisposableEffect(app.packageName) {
        onDispose { currentOnPositioned.value(null) }
    }

    Column(
        modifier = modifier
            .width(itemWidth)
            .zIndex(if (selected || hovered) 1f else 0f)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .onGloballyPositioned { onPositioned(it) }
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { onSelect(app.packageName) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AppIcon(app, look, size = iconSize)
        Text(
            app.label,
            color = if (selected || hovered) look.accent else look.muted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun AppIcon(
    app: LauncherApp,
    look: HomeLook,
    size: Dp = 43.dp,
    corner: Dp = 13.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(look.surface)
            .border(1.dp, look.muted.copy(alpha = .2f), RoundedCornerShape(corner)),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Image(
            bitmap = app.icon,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .padding(5.dp)
        )
    }
}

@Composable
private fun OrbButton(
    look: HomeLook,
    expanded: Boolean,
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (expanded) 1.055f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "orb-scale"
    )
    val pulse = rememberInfiniteTransition(label = "orb-pulse")
    val pulseProgress by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2300), RepeatMode.Restart),
        label = "orb-pulse-progress"
    )
    Box(
        modifier = modifier.size(88.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pulseAlpha = if (expanded) .08f else .23f * (1f - pulseProgress)
            val radius = size.minDimension * (.39f + .08f * pulseProgress)
            drawCircle(
                color = look.accent.copy(alpha = pulseAlpha),
                radius = radius,
                style = Stroke(width = 1.1.dp.toPx())
            )
        }
        Box(
            modifier = Modifier
                .size(68.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .shadow(18.dp, CircleShape, ambientColor = look.accent.copy(alpha = .13f))
                .clip(CircleShape)
                .background(look.orb)
                .border(1.dp, look.foreground.copy(alpha = .23f), CircleShape)
                .semantics {
                    contentDescription = if (expanded) "Close app picker" else "Open app picker"
                    role = Role.Button
                }
                .hoverable(interaction)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OrbDot(look.orbInk, expanded)
                    OrbDot(look.orbInk, expanded)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OrbDot(look.orbInk, expanded)
                    OrbDot(look.orbInk, expanded)
                }
            }
        }
    }
}

@Composable
private fun OrbDot(color: Color, expanded: Boolean) {
    val dotScale by animateFloatAsState(
        targetValue = if (expanded) .82f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "orb-dot"
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .graphicsLayer(scaleX = dotScale, scaleY = dotScale)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun ChoicePill(
    label: String,
    selected: Boolean,
    look: HomeLook,
    onClick: () -> Unit
) {
    val color = if (selected) look.foreground else look.muted
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .defaultMinSize(minHeight = 48.dp)
            .background(if (selected) look.surface else Color.Transparent)
            .border(1.dp, look.muted.copy(alpha = if (selected) .3f else .16f), CircleShape)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(horizontal = 13.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = color, fontSize = 12.sp, letterSpacing = .25.sp)
    }
}

@Composable
private fun EmptyAppsMessage(look: HomeLook, modifier: Modifier = Modifier) {
    Text(
        "Apps will appear here.",
        color = look.muted,
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(20.dp)
    )
}

private fun saveBounds(
    root: LayoutCoordinates?,
    bounds: MutableMap<String, Rect>,
    packageName: String,
    coordinates: LayoutCoordinates?
) {
    if (coordinates == null) {
        bounds.remove(packageName)
        return
    }
    if (root == null || !root.isAttached || !coordinates.isAttached) return
    val localBounds = root.localBoundingBoxOf(coordinates)
    if (bounds[packageName] != localBounds) bounds[packageName] = localBounds
}

private fun nearestApp(
    point: Offset,
    bounds: Map<String, Rect>,
    padding: Float
): String? {
    return bounds.entries
        .filter { (_, rect) ->
            point.x >= rect.left - padding && point.x <= rect.right + padding &&
                point.y >= rect.top - padding && point.y <= rect.bottom + padding
        }
        .minByOrNull { entry ->
            val dx = entry.value.center.x - point.x
            val dy = entry.value.center.y - point.y
            dx * dx + dy * dy
        }
        ?.key
}

private suspend fun loadLauncherApps(context: Context): List<LauncherApp> {
    return withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.queryIntentActivities(launchIntent, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .mapNotNull { resolveInfo ->
                runCatching {
                    LauncherApp(
                        packageName = resolveInfo.activityInfo.packageName,
                        label = resolveInfo.loadLabel(packageManager).toString(),
                        icon = resolveInfo.loadIcon(packageManager).toBitmap(96, 96).asImageBitmap()
                    )
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .toList()
    }
}

private fun loadWallpaper(context: Context, uriString: String): ImageBitmap? {
    val uri = Uri.parse(uriString)
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sampleSize = 1
        while ((bounds.outWidth / sampleSize > 2048 || bounds.outHeight / sampleSize > 2048) && sampleSize < 1024) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) }
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                android.media.ExifInterface(input).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: android.media.ExifInterface.ORIENTATION_NORMAL
        val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return@runCatching null
        val transform = Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1f, -1f)
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                transform.setRotate(90f)
                transform.postScale(-1f, 1f)
            }
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90f)
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                transform.setRotate(270f)
                transform.postScale(-1f, 1f)
            }
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(270f)
        }
        val oriented = if (transform.isIdentity) decoded else {
            android.graphics.Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                transform,
                true
            )
        }
        oriented.asImageBitmap()
    }.getOrNull()
}


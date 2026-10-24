package com.quiet.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.getDistance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.graphics.TransformOrigin
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var helperMessage by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

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
                apps.take(5).mapTo(mutableSetOf()) { it.packageName }
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
            selectedPackage?.let { if (it !in availablePackages) selectedPackage = null }
        }
    }

    val configuration = LocalConfiguration.current
    val launcherWidthDp = configuration.screenWidthDp - 44
    val orbitVisibleLimit = when {
        launcherWidthDp < 146 -> 1
        launcherWidthDp < 190 -> 3
        else -> 5
    }
    val visibleApps by remember(apps, favoritePackages, layout, orbitVisibleLimit) {
        derivedStateOf {
            if (layout == AppLayout.ORBIT) {
                apps.filter { it.packageName in favoritePackages }.take(orbitVisibleLimit)
            } else {
                apps
            }
        }
    }
    val compact = configuration.screenHeightDp < 700
    val stageHeight = when {
        configuration.screenHeightDp < 470 -> 190.dp
        compact -> 204.dp
        else -> 264.dp
    }
    val stageTopGap = (configuration.screenHeightDp - 675).coerceIn(12, 108).dp

    BackHandler(enabled = drawerOpen || selectedPackage != null) {
        if (drawerOpen) {
            drawerOpen = false
            drawerPinned = false
            highlightedPackage = null
            hoverSuppressed = true
        } else {
            selectedPackage = null
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
            selectedPackage = packageName
            highlightedPackage = packageName
            drawerOpen = false
            drawerPinned = false
            helperMessage = null
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
    val appHitPadding = 0f
    val screenView = LocalView.current
    SideEffect {
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
                    val currentCenter = currentOrbCenter.value
                    val startedOnOrb = currentCenter != null &&
                        (down.position - currentCenter).getDistance() <= orbHitRadius
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 15.dp),
            horizontalAlignment = Alignment.Start
        ) {
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
                Text(
                    text = "QUIET MODE",
                    color = look.muted,
                    fontSize = 8.sp,
                    letterSpacing = 1.3.sp,
                    modifier = Modifier
                        .border(1.dp, look.muted.copy(alpha = .24f), CircleShape)
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                )
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
                    Text(
                        "A MOMENT TO YOURSELF",
                        color = look.muted,
                        fontSize = 8.sp,
                        letterSpacing = 1.5.sp
                    )
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

            if (selectedPackage != null) {
                val selected = apps.firstOrNull { it.packageName == selectedPackage }
                if (selected != null) {
                    SelectedAppCard(
                        app = selected,
                        look = look,
                        helperMessage = helperMessage,
                        onOpen = {
                            if (!onOpenApp(selected.packageName)) {
                                helperMessage = "This app can't be opened right now."
                            }
                        },
                        onDismiss = {
                            selectedPackage = null
                            helperMessage = null
                        }
                    )
                }
            } else if (helperMessage != null) {
                Text(
                    text = helperMessage.orEmpty(),
                    color = look.muted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 7.dp)
                )
            }

            AppStage(
                apps = visibleApps,
                allApps = apps,
                favoritePackages = favoritePackages,
                layout = layout,
                look = look,
                stageHeight = stageHeight,
                drawerOpen = drawerOpen,
                drawerPinned = drawerPinned,
                highlightedPackage = highlightedPackage,
                rootCoordinates = rootCoordinates,
                appHitBounds = appHitBounds,
                onOrbPosition = { orbCenter = it },
                onHover = { packageName, hovering ->
                    if (hovering) {
                        highlightedPackage = packageName
                    } else if (highlightedPackage == packageName) {
                        highlightedPackage = null
                    }
                    if (hovering && packageName != null) drawerOpen = true
                },
                onHoverLauncher = { hovering ->
                    if (hovering) {
                        if (layout == AppLayout.ORBIT && !drawerPinned && !hoverSuppressed) drawerOpen = true
                    } else {
                        hoverSuppressed = false
                        if (layout == AppLayout.ORBIT && !drawerPinned) {
                            drawerOpen = false
                            highlightedPackage = null
                        }
                    }
                },
                onSelect = { packageName ->
                    selectedPackage = packageName
                    highlightedPackage = packageName
                    drawerOpen = false
                    drawerPinned = false
                    hoverSuppressed = true
                    helperMessage = null
                },
                onToggleFavorite = { packageName ->
                    val next = favoritePackages.toMutableSet()
                    if (packageName in next) {
                        next.remove(packageName)
                        helperMessage = null
                    } else if (next.size < 5) {
                        next.add(packageName)
                        helperMessage = null
                    } else {
                        helperMessage = "Orbit holds up to five favourites."
                    }
                    favoritePackages = next
                    preferences.edit().putStringSet("favorite_packages", next.toSet()).apply()
                },
                onToggleDrawer = {
                    drawerOpen = !drawerOpen
                    drawerPinned = drawerOpen
                    highlightedPackage = null
                    hoverSuppressed = !drawerOpen
                }
            )

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppLayout.entries.forEach { option ->
                    ChoicePill(
                        label = option.title,
                        selected = layout == option,
                        look = look,
                        onClick = {
                            appHitBounds.clear()
                            layout = option
                            preferences.edit().putString("app_layout", option.name).apply()
                            drawerOpen = option != AppLayout.ORBIT
                            drawerPinned = drawerOpen
                            hoverSuppressed = false
                            highlightedPackage = null
                        }
                    )
                    if (option != AppLayout.entries.last()) Spacer(Modifier.width(6.dp))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .padding(top = 10.dp, bottom = 5.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                HomeLook.entries.forEach { option ->
                    ChoicePill(
                        label = option.title,
                        selected = look == option,
                        look = option,
                        onClick = {
                            look = option
                            preferences.edit().putString("home_look", option.name).apply()
                        }
                    )
                    if (option != HomeLook.entries.last()) Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}

@Composable
private fun AppStage(
    apps: List<LauncherApp>,
    allApps: List<LauncherApp>,
    favoritePackages: Set<String>,
    layout: AppLayout,
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
    onToggleFavorite: (String) -> Unit,
    onToggleDrawer: () -> Unit
) {
    val orbInteraction = remember { MutableInteractionSource() }
    val orbHovered by orbInteraction.collectIsHoveredAsState()
    val menuInteraction = remember { MutableInteractionSource() }
    val menuHovered by menuInteraction.collectIsHoveredAsState()
    LaunchedEffect(orbHovered, menuHovered, layout, drawerPinned) {
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
        val compactOrbit = maxHeight < 220.dp
        val orbitScale = if (maxWidth < 146.dp) 1.14f else if (maxWidth < 190.dp) 1.08f else 1.14f
        val orbitItemWidth = ((maxWidth - 12.dp) / 5 / orbitScale)
            .coerceAtLeast(28.dp)
            .coerceAtMost(48.dp)
        val orbitIconSize = (orbitItemWidth - 4.dp)
            .coerceAtLeast(24.dp)
            .coerceAtMost(43.dp)
        val horizontalSpread = ((maxWidth - orbitItemWidth * orbitScale) / 2 - 2.dp)
            .coerceAtLeast(0.dp)
            .coerceAtMost(110.dp)
        val peakLift = if (compactOrbit) 174.dp else 212.dp
        val edgeLift = if (compactOrbit) 135.dp else 155.dp
        val spreadFactor = when (apps.size) {
            0, 1 -> 0f
            2 -> .54f
            3 -> .76f
            4 -> .90f
            else -> 1f
        }
        val orbitPoints = if (layout == AppLayout.ORBIT && apps.isNotEmpty()) {
            apps.indices.map { index ->
                val normalized = if (apps.size == 1) 0f else
                    (index.toFloat() / (apps.size - 1)) * 2f - 1f
                val progress = abs(normalized) * spreadFactor
                val x = horizontalSpread * (normalized * spreadFactor)
                val lift = peakLift - ((peakLift - edgeLift) * progress)
                x to (maxHeight - lift)
            }
        } else {
            emptyList()
        }

        AnimatedVisibility(
            visible = drawerOpen,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn() + scaleIn(
                initialScale = .76f,
                transformOrigin = TransformOrigin(.5f, 1f)
            ),
            exit = fadeOut() + scaleOut(
                targetScale = .96f,
                transformOrigin = TransformOrigin(.5f, 1f)
            )
        ) {
            Box(Modifier.fillMaxSize().hoverable(menuInteraction)) {
            when (layout) {
                AppLayout.ORBIT -> {
                    if (apps.isEmpty()) {
                        Text(
                            "Add favourites in Grid or List.",
                            color = look.muted,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = maxHeight - 190.dp)
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
                AppLayout.GRID -> {
                    if (allApps.isEmpty()) {
                        EmptyAppsMessage(look = look, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 78.dp))
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp)
                                .padding(bottom = 73.dp)
                                .heightIn(max = (maxHeight - 78.dp).coerceAtLeast(100.dp)),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(allApps, key = { it.packageName }) { app ->
                                AppGridItem(
                                    app = app,
                                    look = look,
                                    favorite = app.packageName in favoritePackages,
                                    selected = highlightedPackage == app.packageName,
                                    onHover = onHover,
                                    onSelect = onSelect,
                                    onToggleFavorite = { onToggleFavorite(app.packageName) },
                                    onPositioned = { coordinates ->
                                        saveBounds(rootCoordinates, appHitBounds, app.packageName, coordinates)
                                    }
                                )
                            }
                        }
                    }
                }
                AppLayout.LIST -> {
                    if (allApps.isEmpty()) {
                        EmptyAppsMessage(look = look, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 78.dp))
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp)
                                .padding(bottom = 73.dp)
                                .heightIn(max = (maxHeight - 78.dp).coerceAtLeast(100.dp)),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(allApps, key = { it.packageName }) { app ->
                                AppListItem(
                                    app = app,
                                    look = look,
                                    favorite = app.packageName in favoritePackages,
                                    selected = highlightedPackage == app.packageName,
                                    onHover = onHover,
                                    onSelect = onSelect,
                                    onToggleFavorite = { onToggleFavorite(app.packageName) },
                                    onPositioned = { coordinates ->
                                        saveBounds(rootCoordinates, appHitBounds, app.packageName, coordinates)
                                    }
                                )
                            }
                        }
                    }
                }
            }
            }
        }

        if (!drawerOpen) {
            Text(
                "Hold · move · release",
                color = look.muted,
                fontSize = 12.sp,
                letterSpacing = .7.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 81.dp)
            )
        }

        OrbButton(
            look = look,
            expanded = drawerOpen,
            layout = layout,
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
private fun AppGridItem(
    app: LauncherApp,
    look: HomeLook,
    favorite: Boolean,
    selected: Boolean,
    onHover: (String, Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onPositioned: (LayoutCoordinates?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(114.dp)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Text(
                text = if (favorite) "★" else "☆",
                color = if (favorite) look.accent else look.muted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = if (favorite) "Remove ${app.label} from favourites" else "Add ${app.label} to favourites"
                        role = Role.Button
                    }
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onToggleFavorite)
                    .padding(top = 1.dp)
            )
        }
        AppOrbitItem(
            app = app,
            look = look,
            selected = selected,
            onHover = onHover,
            onSelect = onSelect,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            onPositioned = onPositioned
        )
    }
}

@Composable
private fun AppListItem(
    app: LauncherApp,
    look: HomeLook,
    favorite: Boolean,
    selected: Boolean,
    onHover: (String, Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onPositioned: (LayoutCoordinates?) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val currentOnPositioned = rememberUpdatedState(onPositioned)
    val scale by animateFloatAsState(
        targetValue = if (selected || hovered) 1.025f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "list-row-scale"
    )
    LaunchedEffect(hovered) { onHover(app.packageName, hovered) }
    DisposableEffect(app.packageName) {
        onDispose { currentOnPositioned.value(null) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .onGloballyPositioned { onPositioned(it) }
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) look.surface else look.surface.copy(alpha = .62f))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { onSelect(app.packageName) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app, look, size = 31.dp, corner = 10.dp)
        Text(
            app.label,
            color = if (selected || hovered) look.accent else look.foreground,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
                .semantics { contentDescription = app.label }
        )
        Text(
            text = if (favorite) "★" else "☆",
            color = if (favorite) look.accent else look.muted,
            fontSize = 15.sp,
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = if (favorite) "Remove ${app.label} from favourites" else "Add ${app.label} to favourites"
                    role = Role.Button
                }
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onToggleFavorite)
                .wrapContentSize()
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
    layout: AppLayout,
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (expanded) 1.055f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "orb-scale"
    )
    Box(
        modifier = modifier
            .size(68.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .shadow(18.dp, CircleShape, ambientColor = look.accent.copy(alpha = .13f))
            .clip(CircleShape)
            .background(look.orb)
            .border(1.dp, look.foreground.copy(alpha = .23f), CircleShape)
            .semantics {
                contentDescription = if (expanded) {
                    "Close app picker"
                } else when (layout) {
                    AppLayout.ORBIT -> "Open favourites"
                    AppLayout.GRID, AppLayout.LIST -> "Open all apps"
                }
                role = Role.Button
            }
            .hoverable(interaction)
            .clickable(interactionSource = interaction, role = Role.Button, onClick = onClick),
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
private fun SelectedAppCard(
    app: LauncherApp,
    look: HomeLook,
    helperMessage: String?,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(look.surface)
            .padding(start = 11.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ImageBitmapIcon(app.icon, app.label, look)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                helperMessage ?: app.label + " selected",
                color = look.foreground,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Text("Tap Open when you're ready.", color = look.muted, fontSize = 10.sp)
        }
        Text(
            "Open",
            color = look.orbInk,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(look.orb)
                .clickable(role = Role.Button, onClick = onOpen)
                .defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
                .padding(horizontal = 14.dp, vertical = 9.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "×",
            color = look.muted,
            fontSize = 18.sp,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .semantics { contentDescription = "Dismiss selected app"; role = Role.Button }
                .clickable(role = Role.Button, onClick = onDismiss)
                .wrapContentSize()
        )
    }
}

@Composable
private fun ImageBitmapIcon(icon: ImageBitmap, label: String, look: HomeLook) {
    androidx.compose.foundation.Image(
        bitmap = icon,
        contentDescription = label,
        modifier = Modifier
            .size(35.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(look.background)
            .padding(4.dp)
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
        .minByOrNull { (it.value.center - point).getDistance() }
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


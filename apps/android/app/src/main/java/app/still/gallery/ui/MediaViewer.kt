package app.still.gallery.ui

import android.text.format.Formatter
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.net.toUri
import app.still.gallery.R
import app.still.gallery.media.DeviceMedia
import app.still.gallery.media.MediaItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun MediaViewer(item: MediaItem, device: DeviceMedia, generation: Int, favorite: Boolean, onClose: () -> Unit, onShare: () -> Unit, onFavorite: () -> Unit) {
    var details by remember(item.id) { mutableStateOf(false) }
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var panX by remember(item.id) { mutableFloatStateOf(0f) }
    var panY by remember(item.id) { mutableFloatStateOf(0f) }
    val white = Color(0xFFF0F1EB)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onClose)
        Column(Modifier.fillMaxSize().background(Color(0xFF171C1A)).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { MarkIcon(Mark.CLOSE, stringResource(R.string.close), color = white) }
                Text(item.name, Modifier.weight(1f).padding(horizontal = 8.dp), color = white, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { details = true }) { MarkIcon(Mark.INFO, stringResource(R.string.information), color = white) }
            }
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds(), contentAlignment = Alignment.Center) {
                if (item.isVideo) VideoPlayer(item)
                else LibraryImage(item, device, generation,
                    Modifier.fillMaxSize().pointerInput(item.id) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            val maxX = size.width * (scale - 1f) / 2
                            val maxY = size.height * (scale - 1f) / 2
                            panX = (panX + pan.x).coerceIn(-maxX, maxX)
                            panY = (panY + pan.y).coerceIn(-maxY, maxY)
                        }
                    }.graphicsLayer(scaleX = scale, scaleY = scale, translationX = panX, translationY = panY),
                    edge = 1600, contentScale = ContentScale.Fit, backgroundColor = Color(0xFF171C1A),
                )
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = onShare) { MarkIcon(Mark.SHARE, stringResource(R.string.share), color = white) }
                IconButton(onClick = onFavorite) { MarkIcon(Mark.HEART, stringResource(if (favorite) R.string.unfavorite else R.string.favorite), color = if (favorite) Color(0xFFB3CADB) else white) }
                if (!item.isVideo) {
                    IconButton(onClick = { scale = (scale - .5f).coerceAtLeast(1f); panX = 0f; panY = 0f }, enabled = scale > 1f) { MarkIcon(Mark.MINUS, stringResource(R.string.zoom_out), color = if (scale > 1f) white else white.copy(alpha = .3f)) }
                    IconButton(onClick = { scale = (scale + .5f).coerceAtMost(5f) }, enabled = scale < 5f) { MarkIcon(Mark.PLUS, stringResource(R.string.zoom_in), color = if (scale < 5f) white else white.copy(alpha = .3f)) }
                }
            }
        }
        if (details) {
            val context = LocalContext.current
            AlertDialog(onDismissRequest = { details = false },
                title = { Text(stringResource(if (item.isVideo) R.string.video_details_title else R.string.details_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(item.name)
                        Text(if (item.capturedAt <= 0) stringResource(R.string.unknown_date) else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(item.capturedAt).atZone(ZoneId.systemDefault())))
                        Text(if (item.width > 0 && item.height > 0) stringResource(R.string.dimensions, item.width, item.height) else stringResource(R.string.unknown_dimensions))
                        Text(Formatter.formatShortFileSize(context, item.bytes))
                        if (item.isVideo) Text(durationLabel(item.durationMs))
                    }
                },
                confirmButton = { TextButton(onClick = { details = false }) { Text(stringResource(R.string.done)) } },
            )
        }
    }
}

@Composable
private fun VideoPlayer(item: MediaItem) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var failed by remember(item.id) { mutableStateOf(false) }
    val video = remember(item.id) { VideoView(context) }
    DisposableEffect(video, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) video.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); video.stopPlayback() }
    }
    if (failed) Text(stringResource(R.string.video_error), color = Color.White, modifier = Modifier.padding(32.dp))
    else AndroidView(factory = {
        video.apply {
            setMediaController(MediaController(context).also { it.setAnchorView(this) })
            setOnErrorListener { _, _, _ -> failed = true; true }
            setOnPreparedListener { player -> player.isLooping = false; seekTo(1) }
            try { setVideoURI(item.id.toUri()) } catch (_: RuntimeException) { failed = true }
        }
    }, modifier = Modifier.fillMaxSize())
}

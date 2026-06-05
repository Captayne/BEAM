package de.systragon.beam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import de.systragon.beam.core.TorrentManager
import de.systragon.beam.media.TorrentDataSource

/**
 * Vollbild-Player, der ein Video aus dem noch laufenden Download streamt (über [TorrentDataSource]).
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(infoHash: String, onClose: () -> Unit) {
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val entry = TorrentManager.get(infoHash)
            val handle = entry?.handle
            val ti = handle?.torrentFile()
            if (entry != null && handle != null && ti != null) {
                val factory = TorrentDataSource.Factory(
                    handle, entry.cachedFile, ti.pieceLength(), ti.totalSize()
                )
                val source = ProgressiveMediaSource.Factory(factory)
                    .createMediaSource(MediaItem.fromUri("beam://$infoHash"))
                setMediaSource(source)
                prepare()
                playWhenReady = true
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer } },
            modifier = Modifier.fillMaxSize()
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        }
    }
}

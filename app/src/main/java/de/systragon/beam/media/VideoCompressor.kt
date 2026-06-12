package de.systragon.beam.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File

/**
 * Komprimiert ein Video vor dem Senden über media3 Transformer (Auflösung + Bitrate je Stufe).
 * Muss auf einem Thread mit Looper laufen (→ Main-Thread). Nur eine Komprimierung gleichzeitig.
 */
@UnstableApi
object VideoCompressor {

    /**
     * `height` ist eine **Obergrenze**: eine Stufe greift nur, wenn sie *kleiner* als die kürzere
     * Seite der Quelle ist (siehe [needsCompression]) — nie hochskaliert. FPS bleibt Original.
     * Muss identisch zur Desktop-Leiter bleiben (`beam-desktop/.../VideoCompressor.kt`).
     */
    enum class CompressionLevel(
        val label: String,
        val tag: String,
        val height: Int,
        val bitrate: Int
    ) {
        ORIGINAL("Original", "", 0, 0),
        UHD4K("4K", "4K", 2160, 16_000_000),
        FHD("FHD", "FHD", 1080, 5_000_000),
        HD720("720p", "720p", 720, 2_500_000),
        SD360("360p", "360p", 360, 600_000),
        PREVIEW("180p", "180p", 180, 200_000)
    }

    /** Kürzere Seite (die „p"-Zahl, orientierungsunabhängig) der Quelle. Null, wenn nicht lesbar. */
    fun sourceShortSide(context: Context, uri: Uri): Int? {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (w != null && h != null) minOf(w, h) else (h ?: w)
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** True nur, wenn eine echte Stufe gewählt ist UND sie kleiner als die Quelle ist (sonst Original). */
    fun needsCompression(context: Context, uri: Uri, level: CompressionLevel): Boolean {
        if (level == CompressionLevel.ORIGINAL) return false
        val short = sourceShortSide(context, uri) ?: return true   // unlesbar → Wunsch respektieren
        return level.height < short
    }

    private var transformer: Transformer? = null
    private var progressHandler: Handler? = null

    fun compress(
        context: Context,
        source: Uri,
        level: CompressionLevel,
        outFile: File,
        onProgress: (Float) -> Unit,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder().setBitrate(level.bitrate).build()
                )
                .build()

            val t = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        stopProgress()
                        transformer = null
                        onSuccess(outFile)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        stopProgress()
                        transformer = null
                        onError(exportException.message ?: "unknown error")
                    }
                })
                .build()

            val edited = EditedMediaItem.Builder(MediaItem.fromUri(source))
                .setEffects(
                    Effects(emptyList(), listOf<Effect>(Presentation.createForHeight(level.height)))
                )
                .build()

            transformer = t
            t.start(edited, outFile.absolutePath)
            startProgress(onProgress)
        } catch (e: Exception) {
            stopProgress()
            transformer = null
            onError(e.message ?: "could not start compression")
        }
    }

    private fun startProgress(onProgress: (Float) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        progressHandler = handler
        val holder = ProgressHolder()
        handler.postDelayed(object : Runnable {
            override fun run() {
                val t = transformer ?: return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress / 100f)
                }
                handler.postDelayed(this, 200)
            }
        }, 200)
    }

    private fun stopProgress() {
        progressHandler?.removeCallbacksAndMessages(null)
        progressHandler = null
    }

    /** Laufende Komprimierung abbrechen (z. B. wenn der Nutzer den Vorgang verlässt). */
    fun cancel() {
        try { transformer?.cancel() } catch (_: Exception) {}
        stopProgress()
        transformer = null
    }
}

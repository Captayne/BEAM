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

    enum class CompressionLevel(
        val label: String,
        val tag: String,
        val height: Int,
        val bitrate: Int
    ) {
        ORIGINAL("Original (no compression)", "", 0, 0),
        Q50("Reduced — 720p (recommended)", "Q50", 720, 2_500_000),
        Q25("Small — 480p", "Q25", 480, 1_200_000),
        Q10("Very small — 360p", "Q10", 360, 600_000),
        Q5("Preview — 180p", "Q5", 180, 200_000)
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

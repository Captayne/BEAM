package de.systragon.beam.desktop

import java.io.File

/**
 * Video-Komprimierung für die Desktop-Version — bewusst **nah an der Android-Lösung**
 * (`app/.../media/VideoCompressor.kt`): gleiche Qualitätsstufen (Auflösung + Bitrate, H.264).
 *
 * Da die JVM kein eingebautes Transcoding hat, rufen wir ein **mitgebündeltes `ffmpeg.exe`** auf
 * (liegt in den App-Ressourcen → seamless, der Nutzer muss nichts installieren). Fortschritt wird
 * aus ffmpegs `time=`/`Duration:`-Ausgaben geparst.
 */
object VideoCompressor {

    /** Identisch zu Android (Label/Tag/Höhe/Bitrate), damit beide Plattformen gleich aussehen. */
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

    private val VIDEO_EXT = setOf(
        "mp4", "mov", "mkv", "avi", "m4v", "webm", "3gp", "wmv", "flv",
        "mpg", "mpeg", "ts", "m2ts", "mts"
    )

    fun isVideo(f: File): Boolean = f.extension.lowercase() in VIDEO_EXT

    private val DURATION = Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""")
    private val TIME = Regex("""time=\s*(\d+):(\d+):(\d+(?:\.\d+)?)""")

    private fun hmsToSec(h: String, m: String, s: String): Double =
        h.toDouble() * 3600 + m.toDouble() * 60 + s.toDouble()

    /**
     * Findet das mitgebündelte ffmpeg.exe. Reihenfolge: App-Ressourcen (jpackage), dann die
     * Projekt-Ressourcen (für `./gradlew run`), zuletzt PATH ("ffmpeg").
     */
    fun ffmpegExe(): String {
        val candidates = buildList {
            // Gepackte App (jpackage): compose.application.resources.dir zeigt auf das resources-Verz.,
            // in das der Inhalt von desktop-resources/windows/ flach kopiert wird → ffmpeg/ffmpeg.exe.
            System.getProperty("compose.application.resources.dir")?.let { add(File(it, "ffmpeg/ffmpeg.exe")) }
            // Dev (./gradlew run vom Repo-Root bzw. aus beam-desktop):
            add(File("beam-desktop/desktop-resources/windows/ffmpeg/ffmpeg.exe"))
            add(File("desktop-resources/windows/ffmpeg/ffmpeg.exe"))
        }
        candidates.firstOrNull { it.exists() }?.let { return it.absolutePath }
        return "ffmpeg"
    }

    /** True, wenn ffmpeg aufrufbar ist (gebündelt oder im PATH). */
    fun isAvailable(): Boolean {
        val exe = ffmpegExe()
        if (exe != "ffmpeg" && File(exe).exists()) return true
        return runCatching {
            ProcessBuilder(exe, "-version").redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)
    }

    /**
     * Komprimiert [source] nach [outFile] (H.264, gewählte Stufe). [onProgress] 0..1.
     * Gibt true zurück bei Erfolg; bei Fehler false → Aufrufer sendet dann das Original.
     */
    fun compress(
        source: File,
        level: CompressionLevel,
        outFile: File,
        onProgress: (Float) -> Unit
    ): Boolean {
        if (level == CompressionLevel.ORIGINAL) return false
        val cmd = listOf(
            ffmpegExe(), "-y", "-i", source.absolutePath,
            "-vf", "scale=-2:${level.height}",
            "-c:v", "libx264", "-b:v", level.bitrate.toString(),
            "-preset", "veryfast", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart",
            outFile.absolutePath
        )
        return try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            var durationSec = 0.0
            proc.inputStream.bufferedReader().forEachLine { line ->
                if (durationSec <= 0.0) {
                    DURATION.find(line)?.let { m ->
                        durationSec = hmsToSec(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                    }
                }
                TIME.find(line)?.let { m ->
                    val t = hmsToSec(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                    if (durationSec > 0) onProgress((t / durationSec).toFloat().coerceIn(0f, 1f))
                }
            }
            val code = proc.waitFor()
            onProgress(1f)
            code == 0 && outFile.exists() && outFile.length() > 0
        } catch (e: Exception) {
            false
        }
    }
}

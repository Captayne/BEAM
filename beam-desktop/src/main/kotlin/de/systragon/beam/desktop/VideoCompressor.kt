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

    /**
     * Identisch zu Android (Label/Tag/Höhe/Bitrate), damit beide Plattformen gleich aussehen.
     * `height` ist eine **Obergrenze**: eine Stufe greift nur, wenn sie *kleiner* als die kürzere
     * Seite der Quelle ist (siehe [needsCompression]) — nie hochskaliert. FPS bleibt Original.
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

    /**
     * Kürzere Seite (die „p"-Zahl, orientierungsunabhängig) der Quelle via gebündeltem ffmpeg.
     * `ffmpeg -i` schreibt die Stream-Infos auf stderr (und endet mit Fehler „no output" — egal,
     * wir lesen nur die Auflösung). Null, wenn nicht lesbar.
     */
    fun sourceShortSide(f: File): Int? = try {
        val p = ProcessBuilder(ffmpegExe(), "-hide_banner", "-i", f.absolutePath)
            .redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        Regex("""Video:.*?[, ](\d{2,5})x(\d{2,5})""").find(text)?.let { m ->
            val w = m.groupValues[1].toIntOrNull()
            val h = m.groupValues[2].toIntOrNull()
            if (w != null && h != null) minOf(w, h) else null
        }
    } catch (_: Exception) { null }

    /** True nur, wenn eine echte Stufe gewählt ist UND sie kleiner als die Quelle ist (sonst Original). */
    fun needsCompression(f: File, level: CompressionLevel): Boolean {
        if (level == CompressionLevel.ORIGINAL) return false
        val short = sourceShortSide(f) ?: return true   // unlesbar → Wunsch respektieren
        return level.height < short
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

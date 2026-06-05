package de.systragon.beam.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Veröffentlicht eine fertig heruntergeladene Datei in die öffentlichen Ordner:
 *  - Bilder  → Galerie (Pictures/Beam)
 *  - Videos  → Galerie (Movies/Beam)
 *  - Rest    → Downloads/Beam
 *
 * Ab API 29 über MediaStore (keine Laufzeit-Berechtigung nötig), darunter über den öffentlichen
 * Dateipfad (benötigt WRITE_EXTERNAL_STORAGE) mit FileProvider-URI zum Öffnen.
 */
object MediaStoreSaver {

    private const val TAG = "MediaStoreSaver"
    const val SUBDIR = "Beam"

    /**
     * Räumt verwaiste „pending"-Einträge auf, die wir selbst angelegt haben (sichtbar als
     * `.pending-…`-Dateien in Download/Beam bzw. der Galerie). Sie entstehen, wenn ein
     * MediaStore-Schreibvorgang abbricht/abstürzt, bevor IS_PENDING wieder auf 0 gesetzt wurde.
     *
     * BEWUSST nur beim echten Prozessstart aufrufen ([BeamApp.onCreate]): dann läuft garantiert
     * kein eigener Schreibvorgang, jeder pending-Eintrag ist also verwaist. Gefiltert wird strikt
     * auf OWNER_PACKAGE_NAME == eigene App → fremde/legitime pending-Dateien bleiben unangetastet.
     * Gibt die Anzahl gelöschter Einträge zurück. Best-effort, schluckt Fehler.
     */
    fun cleanupOrphanPending(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val resolver = context.contentResolver
        val pkg = context.packageName
        val selection = "${MediaStore.MediaColumns.IS_PENDING}=1 AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?"
        val args = arrayOf(pkg)
        val collections = listOf(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
        var deleted = 0
        for (collection in collections) {
            try {
                val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val qArgs = Bundle().apply {
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                    }
                    resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), qArgs, null)
                } else {
                    @Suppress("DEPRECATION")
                    val pendingUri = MediaStore.setIncludePending(collection)
                    resolver.query(pendingUri, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
                }
                cursor?.use { c ->
                    val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    while (c.moveToNext()) {
                        val item = ContentUris.withAppendedId(collection, c.getLong(idIdx))
                        try { deleted += resolver.delete(item, null, null) } catch (_: Exception) { }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "cleanupOrphanPending($collection): ${e.message}")
            }
        }
        if (deleted > 0) Log.i(TAG, "Verwaiste pending-Einträge aufgeräumt: $deleted")
        return deleted
    }

    /** MIME-Typ aus der Dateiendung. Mit Fallback für gängige Medienformate, die `MimeTypeMap`
     *  auf manchen Geräten NICHT kennt (z. B. .heic vom iPhone, .mov) — sonst landen Fotos/Videos
     *  fälschlich in Download statt in der Galerie. */
    fun guessMime(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return when (ext) {
            "heic", "heif" -> "image/heif"
            "webp" -> "image/webp"
            "dng" -> "image/x-adobe-dng"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "m4v" -> "video/x-m4v"
            "3gp", "3gpp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            else -> "application/octet-stream"
        }
    }

    /**
     * Kopiert [source] in den passenden öffentlichen Ordner und gibt den content-URI zurück
     * (zum Öffnen via ACTION_VIEW) — oder null bei Fehler.
     */
    fun publish(context: Context, source: File, fileName: String): Uri? {
        val mime = guessMime(fileName)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishViaMediaStore(context, source, fileName, mime)
            } else {
                publishLegacy(context, source, fileName, mime)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Publish fehlgeschlagen: ${e.message}", e)
            null
        }
    }

    /**
     * Speichert eine Datei immer nach `Download/Beam` (erreichbarer Ordner, unabhängig vom Typ).
     * Genutzt, um komprimierte Videos beim Sender zu behalten.
     */
    fun publishToDownloads(context: Context, source: File, fileName: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, guessMime(fileName))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                } ?: run { resolver.delete(uri, null, null); return null }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, fileName)
                source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
            }
        } catch (e: Exception) {
            Log.e(TAG, "publishToDownloads fehlgeschlagen: ${e.message}", e)
            null
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun publishViaMediaStore(context: Context, source: File, fileName: String, mime: String): Uri? {
        val resolver = context.contentResolver
        // Bilder/Videos ohne Unterordner direkt in die Galerie (Pictures/Movies),
        // sonstige Dateien gebündelt in Download/Beam.
        val (collection, relativePath) = when {
            mime.startsWith("image/") ->
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") ->
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
            else ->
                MediaStore.Downloads.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values) ?: return null
        return try {
            (resolver.openOutputStream(uri) ?: throw java.io.IOException("kein OutputStream"))
                .use { out -> source.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "Gespeichert (MediaStore): $relativePath/$fileName")
            uri
        } catch (e: Exception) {
            // Bei JEDEM Fehler nach dem insert den pending-Eintrag (und die .pending-Datei) entfernen.
            resolver.delete(uri, null, null)
            Log.w(TAG, "publishViaMediaStore fehlgeschlagen, pending entfernt: ${e.message}")
            null
        }
    }

    /** API < 29: direkt in den öffentlichen Ordner schreiben, FileProvider-URI zurückgeben. */
    private fun publishLegacy(context: Context, source: File, fileName: String, mime: String): Uri? {
        val isMedia = mime.startsWith("image/") || mime.startsWith("video/")
        val dirType = when {
            mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val base = Environment.getExternalStoragePublicDirectory(dirType)
        // Bilder/Videos ohne Unterordner; sonstige Dateien in Download/Beam.
        val targetDir = if (isMedia) base else File(base, SUBDIR)
        if (!targetDir.exists()) targetDir.mkdirs()

        val target = File(targetDir, fileName)
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }

        Log.i(TAG, "Gespeichert (Legacy): ${target.absolutePath}")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    }
}

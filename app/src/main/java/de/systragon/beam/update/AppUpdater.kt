package de.systragon.beam.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import de.systragon.beam.core.RelayConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Veröffentlichte App-Version laut Stations-Manifest. */
data class AppVersion(val code: Int, val name: String)

/**
 * In-App-Update: fragt das token-gated Versions-Manifest der Beam-Station ab und kann — wenn eine
 * neuere APK vorliegt — sie laden und den System-Installer starten. Bewusst NICHT automatisch und
 * nicht blockierend: die Settings zeigen nur dann einen Button, wenn wirklich etwas Neueres da ist.
 */
object AppUpdater {

    private const val SUB_PATH = "updates/Beam-update.apk"

    /** Holt das Versions-Manifest der Station; null bei Netz-/Parse-Fehler (dann einfach kein Hinweis). */
    fun fetchAndroidVersion(): AppVersion? = runCatching {
        val conn = (URL(RelayConfig.versionUrl()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 5000
        }
        val txt = conn.inputStream.bufferedReader().use { it.readText() }
        val a = JSONObject(txt).getJSONObject("android")
        AppVersion(a.getInt("code"), a.optString("name"))
    }.getOrNull()

    /** Eigener versionCode (für den „neuer?"-Vergleich). */
    fun currentCode(context: Context): Int =
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toInt()
            else @Suppress("DEPRECATION") it.versionCode
        }

    /**
     * Lädt das aktuelle APK und startet den System-Installer. Kümmert sich beim ersten Mal um die
     * Freigabe „Apps aus dieser Quelle installieren" (schickt zur Einstellungsseite, dann erneut tippen).
     */
    fun installLatest(context: Context) {
        // Ab Android 8: Beam braucht die Erlaubnis, Pakete zu installieren (einmalige Nutzer-Freigabe).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            Toast.makeText(context, "Allow Beam to install apps, then tap “Install” again.", Toast.LENGTH_LONG).show()
            return
        }

        val appCtx = context.applicationContext
        val target = File(appCtx.getExternalFilesDir(null), SUB_PATH).apply { parentFile?.mkdirs(); if (exists()) delete() }
        val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(RelayConfig.apkUrl()))
            .setTitle("Beam update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(appCtx, null, SUB_PATH)
            .setMimeType("application/vnd.android.package-archive")
        val id = runCatching { dm.enqueue(req) }.getOrElse {
            Toast.makeText(context, "Update download failed.", Toast.LENGTH_LONG).show(); return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                runCatching { appCtx.unregisterReceiver(this) }
                if (!target.exists() || target.length() <= 0) {
                    Toast.makeText(appCtx, "Update download failed.", Toast.LENGTH_LONG).show(); return
                }
                val uri = FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", target)
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { appCtx.startActivity(install) }
                    .onFailure { Toast.makeText(appCtx, "Could not open installer.", Toast.LENGTH_LONG).show() }
            }
        }
        // DOWNLOAD_COMPLETE kommt vom System → Receiver muss „exported" registriert werden (API 33+).
        ContextCompat.registerReceiver(
            appCtx, receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        Toast.makeText(context, "Downloading update…", Toast.LENGTH_SHORT).show()
    }
}

package de.systragon.beam

import android.app.Application
import android.util.Log
import de.systragon.beam.core.BeamLog
import de.systragon.beam.media.MediaStoreSaver

/**
 * Setzt früh (vor allem anderen) den Logger-Sink, damit `beam-core` (plattformneutral, ohne
 * `android.util.Log`) seine Logs nach Logcat schreibt — unter denselben Tags wie bisher.
 */
class BeamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BeamLog.sink = { level, tag, msg ->
            when (level) {
                'E' -> Log.e(tag, msg)
                'W' -> Log.w(tag, msg)
                else -> Log.i(tag, msg)
            }
        }

        // Prozessstart = garantiert kein eigener MediaStore-Schreibvorgang aktiv → verwaiste
        // .pending-Reste abgebrochener Downloads wegräumen (nur eigene Einträge). Im Hintergrund,
        // damit der Start nicht blockiert.
        Thread {
            try { MediaStoreSaver.cleanupOrphanPending(this) } catch (_: Exception) { }
        }.start()
    }
}

package de.systragon.beam.core

/**
 * Plattformneutraler Logger für beam-core (das Modul darf nicht von `android.util.Log` abhängen).
 *
 * Die Plattform setzt beim Start einen Sink:
 *  - Android: `BeamLog.sink = { lvl, tag, msg -> ... android.util.Log ... }` (siehe BeamApp)
 *  - Desktop: println o. ä.
 * Ohne gesetzten Sink werden Logs einfach verworfen (kein Crash).
 */
object BeamLog {
    /** (level 'I'|'W'|'E', tag, message) */
    var sink: (Char, String, String) -> Unit = { _, _, _ -> }

    fun i(tag: String, msg: String) = sink('I', tag, msg)
    fun w(tag: String, msg: String) = sink('W', tag, msg)
    fun e(tag: String, msg: String) = sink('E', tag, msg)
}

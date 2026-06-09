package de.systragon.beam.core

/**
 * Adresse der **Beam-Relay-Station** — Betreiber-Einstellung (nicht im UI editierbar).
 *
 * Default ist einkompiliert (funktioniert out-of-box); überschreibbar per **Konfig-Datei** `relay.conf`
 * (eine Zeile `host` oder `host:port`, `#`-Kommentare erlaubt). So lässt sich die Adresse ändern
 * (z. B. später auf `systragon.de`), ohne neu zu bauen. Jede Plattform liest ihre eigene Datei und
 * gibt den Text an [parse] (beam-core ist plattformneutral, kennt keine Pfade):
 *  - Desktop: `~/Downloads/Beam/relay.conf`
 *  - Android: `<app files dir>/relay.conf` (per adb setzbar, nicht im UI)
 */
object RelayConfig {
    const val DEFAULT_HOST = "217.160.159.14"
    const val DEFAULT_PORT = 443
    const val TOKEN = "bs7Kf3R9xLmQ2v"          // Station-Token (HTTP :80, token-gated Routen)

    /** Token-gated Download-URL der aktuellen PC-MSI auf der Station (für „Share PC-Beam!"). */
    fun msiUrl(host: String = DEFAULT_HOST) = "http://$host/$TOKEN/Beam.msi"

    data class Endpoint(val host: String, val port: Int)

    val DEFAULT = Endpoint(DEFAULT_HOST, DEFAULT_PORT)

    /** Parst die erste nicht-leere, nicht-`#` Zeile als `host` oder `host:port`; sonst Default. */
    fun parse(text: String?): Endpoint {
        val line = text?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return DEFAULT
        val idx = line.lastIndexOf(':')
        return if (idx in 1 until line.length - 1) {
            val port = line.substring(idx + 1).toIntOrNull() ?: DEFAULT_PORT
            Endpoint(line.substring(0, idx), port)
        } else {
            Endpoint(line, DEFAULT_PORT)
        }
    }
}

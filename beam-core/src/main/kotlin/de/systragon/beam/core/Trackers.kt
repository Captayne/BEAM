package de.systragon.beam.core

/**
 * Plattformneutrale Tracker-Logik (geteilt von Android & Desktop).
 *
 * Mischung aus UDP, HTTP und HTTPS. WICHTIG: Mobilfunknetze blocken oft UDP — dann sind die
 * http(s)://-Tracker (TCP 80/443) der einzige Weg, sich überhaupt zu finden. Liste gepflegt nach
 * github.com/ngosang/trackerslist (best + http/https).
 */
object Trackers {

    /**
     * Version der Default-Trackerliste. Bei Erhöhung werden die neuen Default-Tracker einmalig in
     * eine bereits gespeicherte Nutzerliste **gemerged** (Custom-Einträge bleiben erhalten) — sonst
     * erreichen neue Tracker (z. B. http/https) Bestandsnutzer nie.
     */
    const val TRACKERS_VERSION = 2

    const val DEFAULT_TRACKERS = """udp://tracker.opentrackr.org:1337/announce
udp://open.stealth.si:80/announce
udp://open.demonii.com:1337/announce
udp://explodie.org:6969/announce
udp://tracker.openbittorrent.com:6969/announce
udp://tracker.torrent.eu.org:451/announce
http://tracker.opentrackr.org:1337/announce
http://tracker.renfei.net:8080/announce
http://tracker.dler.org:6969/announce
http://www.torrentsnipe.info:2701/announce
http://tracker.qu.ax:6969/announce
https://tracker.gcrenwp.top:443/announce
https://tracker.zhuqiy.com:443/announce
https://tr.zukizuki.org:443/announce
https://tracker.bt4g.com:443/announce"""

    /**
     * Liefert die gespeicherte Trackerliste und migriert sie bei Versionssprung (Merge mit den
     * aktuellen Defaults). Custom-Einträge bleiben erhalten.
     */
    fun loadAndMigrateTrackers(store: KeyValueStore): String {
        val stored = store.getString("trackers", null)
        if (stored.isNullOrBlank()) {
            store.putInt("trackers_version", TRACKERS_VERSION)
            return DEFAULT_TRACKERS
        }
        if (store.getInt("trackers_version", 0) >= TRACKERS_VERSION) return stored
        val merged = (parseTrackers(stored) + parseTrackers(DEFAULT_TRACKERS))
            .distinct()
            .joinToString("\n")
        store.putString("trackers", merged)
        store.putInt("trackers_version", TRACKERS_VERSION)
        return merged
    }

    /** Effektive Trackerliste = manuelle (migriert) ∪ automatisch geladene Top-Tracker, dedupliziert. */
    fun combinedTrackers(store: KeyValueStore): List<String> {
        val manual = parseTrackers(loadAndMigrateTrackers(store))
        val auto = parseTrackers(store.getString("auto_trackers", "") ?: "")
        return (manual + auto).distinct()
    }

    /** Zerlegt einen mehrzeiligen Tracker-Text in eine bereinigte URL-Liste (UDP/HTTP/HTTPS). */
    fun parseTrackers(raw: String): List<String> =
        raw.lines()
            .map { it.trim() }
            .filter {
                it.startsWith("udp://") || it.startsWith("http://") || it.startsWith("https://")
            }
}

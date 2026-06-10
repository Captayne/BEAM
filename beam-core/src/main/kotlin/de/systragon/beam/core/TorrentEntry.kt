package de.systragon.beam.core

import org.libtorrent4j.TorrentHandle
import java.io.File

/**
 * Repräsentiert einen einzelnen Torrent mit seinem Zustand.
 * Wird vom TorrentManager verwaltet — für beide Richtungen (Senden/Seeding und Empfangen/Download).
 */

enum class TorrentState {
    IDLE,               // Angelegt, noch nicht gestartet
    HASHING,            // Torrent-Metadaten werden erzeugt (Senden)
    FETCHING_METADATA,  // Empfang: Metadaten werden über das Netz geladen
    DOWNLOADING,        // Empfang: Daten werden geladen
    SEEDING,            // Aktiv am Seeden
    PAUSED,             // Vom User pausiert
    COMPLETED,          // Empfang fertig + Datei gespeichert
    STOPPED,            // Sauber gestoppt, Ressourcen freigegeben
    ERROR               // Fehler aufgetreten
}

data class TorrentEntry(
    val infoHash: String,
    var fileName: String,
    var fileSize: Long,
    var cachedFile: File,
    var torrentFile: File? = null,    // nur beim Senden vorhanden
    var state: TorrentState = TorrentState.IDLE,
    var handle: TorrentHandle? = null,
    var errorMessage: String? = null,
    var uploadedBytes: Long = 0L,
    var currentPeers: Int = 0,
    var uploadRate: Int = 0,
    // Relay-Empfehlung: Gegenüber bekannt (listPeers>0), aber keine Verbindung (numPeers==0) seit N s.
    var directBlocked: Boolean = false,
    var peerSeenAt: Long = 0L,        // wann das Gegenüber zuerst bekannt wurde (Grace-Timer-Anker)
    var relayEngaged: Boolean = false, // Relay-exklusiv schon umgeschaltet? (clear_peers nur EINMAL)
    var lastRelayDial: Long = 0L,      // Empfänger: wann zuletzt am Relay angemeldet (periodisches Re-Dial)
    var relayWaiting: Int = 0,         // Sender: wie viele Empfänger hängen gerade (Badge am Relay-Knopf)
    var lastWaitingPing: Long = 0L,    // Empfänger: letzter „ich-hänge"-Ping ans Relay
    var lastStatusPoll: Long = 0L,     // Sender: letzte Abfrage der Wartenden-Anzahl
    var relayIdleSince: Long = 0L,     // Sender: seit wann niemand wartet + kein Upload (Auto-Off-Karenz)
    // --- Empfang (Download) ---
    var isDownload: Boolean = false,
    var progress: Float = 0f,         // 0..1
    var downloadedBytes: Long = 0L,
    var downloadRate: Int = 0,
    var savedUri: String? = null,     // repräsentative URI (für „Öffnen"/Galerie)
    val savedUris: MutableList<String> = mutableListOf(),  // ALLE gespeicherten URIs (Bündel) → Mülltonne löscht alle
    var isEncrypted: Boolean = false, // E2E-verschlüsselte Übertragung (.beamenc)
    var streamReady: Boolean = false, // Video: erste + letzte Blöcke da → streambar
    var mediaOnly: Boolean = false,   // empfangene Datei(en) sind ausschließlich Bild/Video → Galerie öffnen
    var galleryMime: String? = null,  // MIME des repräsentativen Medien-Elements (für „in Galerie öffnen")
    var trackerWorking: Boolean = false, // mind. ein Tracker liefert gerade (für granulare Statuszeile)
    val createdAt: Long = System.currentTimeMillis() // Anlage-Zeitpunkt → neueste Karte zuoberst
)

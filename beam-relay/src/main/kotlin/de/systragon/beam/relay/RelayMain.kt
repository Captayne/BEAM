package de.systragon.beam.relay

/**
 * **Beam-Relay-Station** — zwei Dienste in EINEM Prozess, Beam-exklusiv, ohne libtorrent/Storage:
 *
 *  - **[BeamTracker]** (privater HTTP-Tracker, Port `BEAM_TRACKER_PORT`, Default 80): die Apps finden
 *    sich über uns (Token-Pfad). Er kennt jeden aktiven Infohash.
 *  - **[BeamPipe]** (stumpfer Byte-Pipe / Rendezvous, Port `BEAM_PIPE_PORT`, Default 443): der
 *    Fallback-Datenweg, wenn sich zwei Apps direkt nicht erreichen (beide CGNAT). Paart zwei
 *    Verbindungen per Infohash (aus dem BT-Handshake gepeekt), spleißt sie stumpf zusammen, zählt Bytes.
 *    Gate: nur Infohashes, die der Tracker kennt → keine Fremdnutzung.
 *
 * Das Relay versteht den Inhalt nie — es vermittelt nur. Verschlüsselt (Chiffretext), wenn die Apps
 * eine Passphrase nutzen.
 */
fun main(args: Array<String>) {
    // Isolierter Splice-Test: zwei libtorrent-Sessions (Seeder+Leecher) relay-exklusiv durch die Station.
    if (args.firstOrNull() == "--splice-test") {
        SpliceTest.run(args.getOrElse(1) { "217.160.159.14:443" })
        return
    }
    val trackerPort = (System.getenv("BEAM_TRACKER_PORT") ?: "80").toIntOrNull() ?: 80
    val trackerToken = System.getenv("BEAM_TRACKER_TOKEN") ?: "bs7Kf3R9xLmQ2v"
    val pipePort = (System.getenv("BEAM_PIPE_PORT") ?: "443").toIntOrNull() ?: 443

    // Debug: BEAM_FORCE_RELAY="ip:port" (Relay-Pipe-Endpunkt) → Tracker gibt NUR das Relay als Peer
    // zurück → jeder Transfer MUSS über die Station laufen (beweist den Datenweg, NAT-unabhängig).
    val forcePeer = System.getenv("BEAM_FORCE_RELAY")?.let { env ->
        runCatching {
            val (ip, p) = env.split(":")
            ip.split(".").map { it.toInt().toByte() }.toByteArray() +
                byteArrayOf(((p.toInt() ushr 8) and 0xFF).toByte(), (p.toInt() and 0xFF).toByte())
        }.getOrNull()
    }
    if (forcePeer != null) log("⚠ FORCE-RELAY aktiv (${System.getenv("BEAM_FORCE_RELAY")}) — alle Transfers über die Station")

    val nogate = System.getenv("BEAM_PIPE_NOGATE") != null
    if (nogate) log("⚠ PIPE-NOGATE aktiv — Pipe akzeptiert JEDEN Infohash (nur für Tests!)")

    log("Beam-Relay-Station startet — Tracker :$trackerPort, Byte-Pipe :$pipePort")
    val tracker = BeamTracker(trackerPort, trackerToken, forceRelayPeer = forcePeer)
    runCatching { tracker.start() }.onFailure { log("Tracker-Start FEHLER (:$trackerPort): ${it.message}") }
    runCatching { BeamPipe(pipePort) { nogate || tracker.knows(it) }.start() }
        .onFailure { log("Pipe-Start FEHLER (:$pipePort): ${it.message}") }

    // Prozess am Leben halten — die Server laufen auf eigenen Threads.
    while (true) { try { Thread.sleep(3_600_000) } catch (_: InterruptedException) {} }
}

internal fun log(msg: String) = println("${java.time.LocalTime.now().withNano(0)}  $msg")

internal fun humanBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = n.toDouble() / 1024.0
    var idx = 0
    while (value >= 1024.0 && idx < units.lastIndex) { value /= 1024.0; idx++ }
    return "%.1f %s".format(value, units[idx])
}

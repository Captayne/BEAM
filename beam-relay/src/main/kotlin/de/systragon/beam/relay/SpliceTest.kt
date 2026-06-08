package de.systragon.beam.relay

import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import java.io.File

/**
 * **Isolierter Splice-Test** — reproduziert das echte Szenario unter voller Kontrolle: ZWEI
 * libtorrent-Sessions (Seeder + Leecher) auf einer Maschine, beide **relay-exklusiv** (DHT/LSD/PEX aus,
 * keine Tracker, nur `connect_peer` aufs Relay) → die Station muss sie paaren und die Datei durchschieben.
 * Kein Geräte-Gewusel, kein Timing-Glück — schnelle Iteration zum Knacken des Splice-Verhaltens.
 *
 * Relay braucht dafür `BEAM_PIPE_NOGATE=1` (die Test-Sessions announcen ja nicht beim Tracker).
 */
object SpliceTest {
    fun run(relayEndpoint: String) {
        ensureNativeLib()
        val rHost = relayEndpoint.substringBeforeLast(':')
        val rPort = relayEndpoint.substringAfterLast(':').toInt()
        log("=== SPLICE-TEST gegen Relay $rHost:$rPort ===")

        val work = File("splice-test").apply { mkdirs() }
        val seedDir = File(work, "seed").apply { mkdirs() }
        val leechDir = File(work, "leech").apply { mkdirs() }
        File(leechDir, "testfile.bin").delete()   // frischer Lauf

        // 5-MB-Testdatei (deterministisch) → Torrent
        val testFile = File(seedDir, "testfile.bin")
        val data = ByteArray(5 * 1024 * 1024); java.util.Random(42).nextBytes(data); testFile.writeBytes(data)
        // V1_ONLY! Sonst baut libtorrent 2.x ein Hybrid-Torrent (v1+v2) → Seeder präsentiert den v2-
        // Infohash, der Magnet-Leecher den v1 → die Pipe paart sie nie. v1-only = ein einziger Infohash.
        val ti = TorrentInfo(TorrentBuilder().path(testFile).pieceSize(0)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode())
        val sha1 = ti.infoHashes().best
        log("Torrent: ${ti.infoHash()}  (${ti.totalSize()} B, ${ti.numPieces()} pieces)")

        // Hash EINMAL beim Gate-Tracker anmelden (reiner HTTP-Announce), damit die Byte-Pipe (Gate
        // `knows(hash)`) nicht abweist — OHNE libtorrent einen Tracker zu geben, also weiterhin KEIN
        // Direktpfad zwischen den beiden Test-Sessions. So bleibt's ein reiner Relay-Datenpfad-Test
        // gegen die Produktions-Konfig (Gate AN).
        registerWithGate(rHost, sha1.toString())

        // --- Seeder ---
        val seeder = SessionManager(); seeder.start(); seeder.applySettings(testSettings())
        seeder.download(ti, seedDir, null, null, null, TorrentFlags.SEED_MODE)
        val sh = awaitHandle(seeder, sha1, "seeder")
        relayExclusive(sh, rHost, rPort); log("Seeder: relay-exklusiv + connect_peer")

        Thread.sleep(1500)  // dem Seeder kurz Vorlauf am Relay geben (wartet als Erster)

        // --- Leecher ---
        val leecher = SessionManager(); leecher.start(); leecher.applySettings(testSettings())
        leecher.download("magnet:?xt=urn:btih:${ti.infoHash()}", leechDir, TorrentFlags.UPDATE_SUBSCRIBE)
        val lh = awaitHandle(leecher, sha1, "leecher")
        relayExclusive(lh, rHost, rPort); log("Leecher: relay-exklusiv + connect_peer")

        // --- Beobachten ---
        for (i in 1..90) {
            Thread.sleep(2000)
            val ls = lh.status(); val ss = sh.status()
            log("t=${i * 2}s  leech ${"%.1f".format(ls.progress() * 100)}%  peers=${ls.numPeers()}  ↓${ls.downloadRate()}/s  meta=${ls.hasMetadata()}  | seed peers=${ss.numPeers()} ↑${ss.uploadRate()}/s")
            if (ls.isFinished || ls.progress() >= 0.999f) {
                log("=== ✅ FERTIG — die Station hat die ganze Datei durchgeschoben! ===")
                seeder.stop(); leecher.stop(); return
            }
        }
        log("=== ❌ TIMEOUT — keine Datei durch ===")
        seeder.stop(); leecher.stop()
    }

    /** Reiner HTTP-Announce an den Beam-Gate-Tracker (:80), nur um `knows(hash)` zu erfüllen.
     *  Token fest (Test-Harness). info_hash = 20 Rohbytes, prozent-kodiert. */
    private fun registerWithGate(host: String, infoHashHex: String) {
        try {
            val enc = infoHashHex.chunked(2).joinToString("") { "%$it" }   // 40 Hex → %XX%XX… (20 Rohbytes)
            val url = java.net.URL("http://$host/bs7Kf3R9xLmQ2v/announce?info_hash=$enc&port=6881&uploaded=0&downloaded=0&left=0&event=started")
            (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 5000; readTimeout = 5000
                inputStream.use { it.readBytes() }
            }
            log("Gate-Announce gesendet → Pipe sollte jetzt durchlassen")
        } catch (e: Exception) { log("Gate-Announce fehlgeschlagen: ${e.message}") }
    }

    private fun relayExclusive(h: TorrentHandle, host: String, port: Int) {
        h.setFlags(TorrentFlags.DISABLE_DHT)
        h.setFlags(TorrentFlags.DISABLE_LSD)
        h.setFlags(TorrentFlags.DISABLE_PEX)
        h.replaceTrackers(emptyList())
        h.swig().clear_peers()
        h.swig().connect_peer(org.libtorrent4j.TcpEndpoint(host, port).swig())
    }

    private fun awaitHandle(s: SessionManager, hash: Sha1Hash, name: String): TorrentHandle {
        var h = s.find(hash); var w = 0
        while ((h == null || !h.isValid) && w < 5000) { Thread.sleep(100); w += 100; h = s.find(hash) }
        log("$name handle valid=${h?.isValid}")
        return h!!
    }

    private fun testSettings(): SettingsPack {
        val s = SettingsPack()
        s.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), false)
        s.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), false)
        s.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
        s.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
        s.setInteger(settings_pack.int_types.out_enc_policy.swigValue(), settings_pack.enc_policy.pe_disabled.swigValue())
        s.setInteger(settings_pack.int_types.in_enc_policy.swigValue(), settings_pack.enc_policy.pe_disabled.swigValue())
        s.setInteger(settings_pack.int_types.peer_connect_timeout.swigValue(), 120)
        s.setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:0")  // zufälliger Port (2 Sessions)
        return s
    }

    /** Native libtorrent-Lib aus dem Classpath laden (für die Test-Sessions auf dem PC). */
    private fun ensureNativeLib() {
        if (!System.getProperty("libtorrent4j.jni.path", "").isNullOrEmpty()) return
        val os = System.getProperty("os.name", "").lowercase()
        val ext = if (os.contains("win")) "dll" else if (os.contains("mac")) "dylib" else "so"
        val cl = Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
        val input = cl.getResourceAsStream("lib/x86_64/libtorrent4j.$ext") ?: run { log("Native-Lib fehlt!"); return }
        val tmp = File.createTempFile("libtorrent4j", ".$ext"); tmp.deleteOnExit()
        input.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
        System.setProperty("libtorrent4j.jni.path", tmp.absolutePath)
    }
}

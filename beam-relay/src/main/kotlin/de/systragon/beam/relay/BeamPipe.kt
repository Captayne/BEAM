package de.systragon.beam.relay

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * **Stumpfer Byte-Pipe / Rendezvous-Relay.** Lauscht auf einem Port; jede eingehende Verbindung schickt
 * (als BitTorrent-Peer) zuerst ihren 68-Byte-Handshake. Wir lesen daraus nur den **20-Byte-Infohash**
 * (Offset 28), **paaren** zwei Verbindungen mit gleichem Infohash und **spleißen** sie bidirektional
 * zusammen — Bytes zählend. Das Relay versteht den Inhalt NICHT; es schiebt nur Bytes (Chiffretext,
 * wenn die Apps mit Passphrase verschlüsseln). Die zwei libtorrents reden Ende-zu-Ende durch die Röhre.
 *
 * Missbrauchsschutz: nur Infohashes, die unser eigener Tracker kennt ([isAllowed]) → Beam-exklusiv.
 */
class BeamPipe(
    private val port: Int,
    private val pairTimeoutMs: Long = 5 * 60_000L,   // großzügig: fängt den Klick-Versatz zweier Nutzer ab
    private val isAllowed: (String) -> Boolean
) {
    private class Pending(val socket: Socket, val handshake: ByteArray, val since: Long)

    private val waiting = ConcurrentHashMap<String, Pending>()
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        val server = ServerSocket(port)
        log("Byte-Pipe lauscht auf :$port")
        pool.submit {
            while (true) {
                val s = try { server.accept() } catch (e: Exception) { log("pipe accept: ${e.message}"); continue }
                pool.submit { handle(s) }
            }
        }
        // Reaper: wartende Verbindungen ohne Partner nach Timeout schließen.
        Thread {
            while (true) {
                try { Thread.sleep(15_000) } catch (_: InterruptedException) {}
                val now = System.currentTimeMillis()
                waiting.entries.removeIf { (_, p) ->
                    if (now - p.since > pairTimeoutMs) { p.socket.closeQuietly(); true } else false
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 30_000                 // Handshake muss zügig kommen
        val hs = readHandshake(sock)
        if (hs == null) { sock.closeQuietly(); return }
        sock.soTimeout = 0
        val hashHex = toHex(hs, 28, 20)
        if (!isAllowed(hashHex)) {
            log("pipe: ABGEWIESEN ${hashHex.take(8)} (kein Beam-Infohash)")
            sock.closeQuietly(); return
        }

        // Atomar: Partner holen ODER selbst als wartend hinterlegen. Paaren NUR mit anderer peer_id
        // (Handshake-Bytes 48..67) → keine Selbst-Verbindung (wichtig: gleicher Carrier-CGNAT = gleiche IP,
        // aber verschiedene peer_id → soll gepaart werden; zweimal dasselbe Gerät = gleiche peer_id → nicht).
        val myPeerId = hs.copyOfRange(48, 68)
        var partner: Pending? = null
        synchronized(waiting) {
            val w = waiting[hashHex]
            when {
                w == null -> waiting[hashHex] = Pending(sock, hs, System.currentTimeMillis())
                w.handshake.copyOfRange(48, 68).contentEquals(myPeerId) -> {
                    w.socket.closeQuietly()   // gleiche peer_id → Selbst-Verbindung, alten ersetzen
                    waiting[hashHex] = Pending(sock, hs, System.currentTimeMillis())
                    log("pipe: gleiche peer_id für ${hashHex.take(8)} → keine Selbst-Paarung")
                }
                else -> { waiting.remove(hashHex); partner = w }
            }
        }
        if (partner == null) { log("pipe: warte auf Partner für ${hashHex.take(8)}"); return }

        // Gepaart! Handshakes kreuzweise zustellen, dann bidirektional spleißen.
        log("pipe: PAARE ${hashHex.take(8)} — splice startet")
        val a = partner.socket; val b = sock
        val bytes = AtomicLong(0)
        try {
            b.getOutputStream().apply { write(partner.handshake); flush() }
            a.getOutputStream().apply { write(hs); flush() }
            bytes.addAndGet((partner.handshake.size + hs.size).toLong())
        } catch (e: Exception) { closeBoth(a, b); return }

        val t1 = pool.submit { pump(a, b, bytes, "A→B") }
        val t2 = pool.submit { pump(b, a, bytes, "B→A") }
        pool.submit {
            runCatching { t1.get(); t2.get() }
            closeBoth(a, b)
            log("pipe: ${hashHex.take(8)} fertig — ${humanBytes(bytes.get())} relayed")
        }
    }

    /** Kopiert [from] → [to], bis EOF/Fehler; zählt die Bytes mit. Großer Puffer = wenige Syscalls
     *  bei hohem Durchsatz (Socket-/TCP-Fenster überlässt man dagegen Linux' Auto-Tuning).
     *  DIAGNOSE: loggt die ersten Bytes je Richtung (BT-Nachrichten-Typen) + welche Seite zuerst EOF schickt. */
    private fun pump(from: Socket, to: Socket, counter: AtomicLong, label: String) {
        val buf = ByteArray(1024 * 1024)   // 1 MB Kopierpuffer
        var sent = 0L
        var first = true
        try {
            val ins = from.getInputStream(); val outs = to.getOutputStream()
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                if (first) { log("pipe $label erste $n B: " + hexPreview(buf, n)); first = false }
                outs.write(buf, 0, n); outs.flush()
                counter.addAndGet(n.toLong()); sent += n
            }
        } catch (e: Exception) { log("pipe $label Fehler nach $sent B: ${e.message}") }
        log("pipe $label EOF/Ende nach $sent B")
    }

    private fun hexPreview(b: ByteArray, n: Int): String {
        val m = minOf(n, 48)
        val sb = StringBuilder()
        for (i in 0 until m) { sb.append("%02x".format(b[i].toInt() and 0xFF)); if (i % 4 == 3) sb.append(' ') }
        return sb.toString().trim()
    }

    /** Liest den BitTorrent-Handshake (1 + 19 + 8 + 20 + 20 = 68 Byte). pstrlen muss 19 sein. */
    private fun readHandshake(sock: Socket): ByteArray? {
        return try {
            val ins = sock.getInputStream()
            val full = ByteArray(68)
            var off = 0
            while (off < 68) {
                val n = ins.read(full, off, 68 - off)
                if (n < 0) return null
                off += n
            }
            if (full[0].toInt() != 19) null else full
        } catch (e: Exception) { null }
    }

    private fun closeBoth(a: Socket, b: Socket) { a.closeQuietly(); b.closeQuietly() }

    private fun Socket.closeQuietly() { runCatching { close() } }

    private fun toHex(b: ByteArray, offset: Int, len: Int): String {
        val sb = StringBuilder(len * 2)
        for (i in offset until offset + len) sb.append("%02x".format(b[i].toInt() and 0xFF))
        return sb.toString()
    }
}

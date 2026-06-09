package de.systragon.beam.relay

import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * **Rendezvous-Broker** (vormals dumme Byte-Pipe). Zwei CGNAT-Clients können nur RAUS zum Relay wählen,
 * verbinden sich und warten — die Station paart zwei Verbindungen mit gleichem Infohash und brückt sie.
 *
 * **Das Problem der dummen Pipe war das Timing:** wer allein ankam, dessen libtorrent gab auf (das Relay
 * blieb stumm, bis BEIDE da waren), beide wählten neu, Phasen-Versatz → Minuten Leerlauf.
 *
 * **Broker-Lösung:** Jeder ankommende Client bekommt SOFORT einen gültigen **synthetischen BT-Handshake**
 * → seine Verbindung gilt als „verbunden", er wartet geduldig (interested/choked). Wir halten ihn mit
 * **Keepalives** am Leben (beliebig lange) und **puffern** seine Anfangs-Nachrichten. Kommt der Partner,
 * **brücken** wir die beiden Nachrichtenströme (gepufferte Anfänge zuerst, dann bidirektional pumpen).
 * → Kein gemeinsames Zeitfenster nötig. Der Empfänger lauscht WIRKLICH dauerhaft und empfängt, sobald
 * der Sender auftaucht.
 *
 * Bleibt **content-blind:** BT-Rahmen sind Klartext (MSE aus), die Datei-Payload bleibt E2E-Chiffretext.
 * Kein Storage. Missbrauchsschutz: nur Infohashes, die unser Tracker kennt ([isAllowed]).
 */
class BeamPipe(
    private val port: Int,
    private val maxHoldMs: Long = 10 * 60_000L,      // wartende Verbindung max. so lange halten
    private val isAllowed: (String) -> Boolean
) {
    /** Eine angenommene Client-Verbindung im Broker. */
    private class Conn(val socket: Socket, val peerId: ByteArray) {
        val buffer = ByteArrayOutputStream()             // Anfangs-Nachrichten, gepuffert bis zur Paarung
        @Volatile var partner: Conn? = null              // gesetzt, sobald gepaart
        val holdDone = CountDownLatch(1)                 // signalisiert: holdLoop hat den Socket freigegeben
    }

    private val waiting = ConcurrentHashMap<String, Conn>()
    private val pool = Executors.newCachedThreadPool()

    private val RELAY_PEER_ID = "-BR0001-BeamRelayPipe".toByteArray(Charsets.US_ASCII).copyOf(20)
    private val KEEPALIVE = byteArrayOf(0, 0, 0, 0)      // BT keep-alive = 0-Längen-Nachricht

    fun start() {
        val server = ServerSocket(port)
        log("Byte-Pipe (Broker) lauscht auf :$port")
        pool.submit {
            while (true) {
                val s = try { server.accept() } catch (e: Exception) { log("pipe accept: ${e.message}"); continue }
                pool.submit { handle(s) }
            }
        }
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 30_000
        val hs = readHandshake(sock)
        if (hs == null) { sock.closeQuietly(); return }
        val hashHex = toHex(hs, 28, 20)
        if (!isAllowed(hashHex)) {
            log("pipe: ABGEWIESEN ${hashHex.take(8)} (kein Beam-Infohash)")
            sock.closeQuietly(); return
        }
        // Sofort synthetischen Handshake zurück → der Client gilt als verbunden und wartet geduldig.
        try { sock.getOutputStream().apply { write(syntheticHandshake(hs)); flush() } }
        catch (e: Exception) { sock.closeQuietly(); return }

        val me = Conn(sock, hs.copyOfRange(48, 68))
        var partner: Conn? = null
        synchronized(waiting) {
            val w = waiting[hashHex]
            when {
                w == null -> waiting[hashHex] = me
                w.peerId.contentEquals(me.peerId) -> {        // gleiche peer_id = Selbstverbindung → alten ersetzen
                    w.socket.closeQuietly(); waiting[hashHex] = me
                    log("pipe: gleiche peer_id für ${hashHex.take(8)} → keine Selbst-Paarung")
                }
                else -> { waiting.remove(hashHex); partner = w; w.partner = me; me.partner = w }
            }
        }
        if (partner == null) holdLoop(me, hashHex)            // puffern + keepalive bis Partner (oder Tod)
        else bridge(partner!!, me, hashHex)                  // wir treiben die Brücke
    }

    /** Hält eine allein wartende Verbindung am Leben: puffert ihre Bytes, schickt periodisch Keepalives,
     *  bis ein Partner gesetzt wird oder die Verbindung stirbt / das Limit erreicht ist. */
    private fun holdLoop(me: Conn, hashHex: String) {
        log("pipe: warte auf Partner für ${hashHex.take(8)} (Broker, keepalive)")
        val started = System.currentTimeMillis()
        var lastKa = started
        try {
            me.socket.soTimeout = 2_000                      // alle 2 s aufwachen → Partner prüfen / keepalive
            val ins = me.socket.getInputStream()
            val buf = ByteArray(64 * 1024)
            while (me.partner == null) {
                try {
                    val n = ins.read(buf)
                    if (n < 0) break                         // Client hat aufgelegt
                    if (n > 0) synchronized(me.buffer) { me.buffer.write(buf, 0, n) }
                } catch (e: SocketTimeoutException) { /* nur aufwachen */ }
                val now = System.currentTimeMillis()
                if (me.partner == null && now - lastKa > 30_000) {
                    try { me.socket.getOutputStream().apply { write(KEEPALIVE); flush() } }
                    catch (ex: Exception) { break }
                    lastKa = now
                }
                if (now - started > maxHoldMs) { log("pipe: ${hashHex.take(8)} Hold-Timeout"); break }
            }
        } catch (e: Exception) { /* fällt durch */ }
        if (me.partner == null) {                            // tot/abgelaufen ohne Partner → aufräumen
            synchronized(waiting) { if (waiting[hashHex] === me) waiting.remove(hashHex) }
            me.socket.closeQuietly()
        }
        me.holdDone.countDown()                              // Socket ist freigegeben (wird nicht mehr hier gelesen)
    }

    /** [a] war der Wartende (mit gepufferten Anfangs-Nachrichten), [b] ist gerade angekommen. */
    private fun bridge(a: Conn, b: Conn, hashHex: String) {
        try { a.holdDone.await(10, TimeUnit.SECONDS) } catch (_: Exception) {}   // a's holdLoop muss den Socket loslassen
        if (a.socket.isClosed) { b.socket.closeQuietly(); return }               // a starb während des Wartens
        log("pipe: PAARE ${hashHex.take(8)} — Broker bridged")
        val bytes = AtomicLong(0)
        try {
            a.socket.soTimeout = 0; b.socket.soTimeout = 0
            val aBuf = synchronized(a.buffer) { a.buffer.toByteArray() }         // a's gepufferte Anfänge an b nachliefern
            if (aBuf.isNotEmpty()) {
                b.socket.getOutputStream().apply { write(aBuf); flush() }
                bytes.addAndGet(aBuf.size.toLong())
                log("pipe ${hashHex.take(8)}: ${aBuf.size} B Puffer A→B nachgeliefert")
            }
        } catch (e: Exception) { closeBoth(a.socket, b.socket); return }
        // b's eigene Anfangs-Nachrichten stehen noch ungelesen im TCP-Puffer → der Pump b→a holt sie (Reihenfolge bleibt).
        val t1 = pool.submit { pump(a.socket, b.socket, bytes, "A→B") }
        val t2 = pool.submit { pump(b.socket, a.socket, bytes, "B→A") }
        pool.submit {
            runCatching { t1.get(); t2.get() }
            closeBoth(a.socket, b.socket)
            log("pipe: ${hashHex.take(8)} fertig — ${humanBytes(bytes.get())} relayed")
        }
    }

    /** Synthetischer 68-Byte-Handshake an den Client. Reserved-Bytes des Clients SPIEGELN → wir „können",
     *  was er kann (BEP10/ut_metadata etc.); Infohash spiegeln; eigene (irrelevante) Relay-Peer-ID. */
    private fun syntheticHandshake(clientHs: ByteArray): ByteArray {
        val out = ByteArray(68)
        out[0] = 19
        System.arraycopy("BitTorrent protocol".toByteArray(Charsets.US_ASCII), 0, out, 1, 19)
        System.arraycopy(clientHs, 20, out, 20, 8)     // reserved gespiegelt
        System.arraycopy(clientHs, 28, out, 28, 20)    // infohash gespiegelt
        System.arraycopy(RELAY_PEER_ID, 0, out, 48, 20)
        return out
    }

    /** Kopiert [from] → [to] bis EOF/Fehler; zählt Bytes. DIAGNOSE: erste Bytes je Richtung + EOF-Seite. */
    private fun pump(from: Socket, to: Socket, counter: AtomicLong, label: String) {
        val buf = ByteArray(1024 * 1024)
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

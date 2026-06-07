package de.systragon.beam.relay

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * **Minimaler privater BitTorrent-HTTP-Tracker** (BEP 3 + compact BEP 23), Beam-exklusiv.
 *
 * - Lauscht NUR auf `/<token>/announce` → nur Apps, die das Token kennen (fest in Beam einkompiliert),
 *   dürfen sich melden. Kein öffentlicher Eintrag, keine Fremdnutzung.
 * - Hält je Infohash eine Peer-Tabelle. Die Peer-IP nimmt der Tracker aus der **TCP-Verbindung**
 *   (= echte öffentliche Adresse, wichtig hinter NAT/CGNAT); der Port kommt aus dem `port=`-Parameter.
 * - Läuft im SELBEN Prozess wie das Relay → die Station kennt jeden aktiven Infohash in-process
 *   (das ist später der Registrierungskanal fürs Bridgen, ganz ohne Extra-Protokoll).
 *
 * Bewusst nur IPv4-compact (die CGNAT-Fälle, um die es geht, sind IPv4). IPv6/BEP7 später.
 */
class BeamTracker(
    private val port: Int,
    private val token: String,
    private val peerTtlMs: Long = 30 * 60 * 1000L,
    private val announceInterval: Int = 1800,
    private val forceRelayPeer: ByteArray? = null   // Debug: 6-Byte-Compact des Relay-Pipe-Endpunkts →
                                                    // als EINZIGER Peer zurückgeben → Daten MÜSSEN übers Relay
) {
    private data class PeerKey(val ip: ByteArray, val port: Int) {
        override fun equals(other: Any?) = other is PeerKey && port == other.port && ip.contentEquals(other.ip)
        override fun hashCode() = ip.contentHashCode() * 31 + port
    }

    // infohash(hex) -> (peer -> lastSeenMillis)
    private val swarms = ConcurrentHashMap<String, ConcurrentHashMap<PeerKey, Long>>()

    /** Kennt der Tracker diesen Infohash (mind. ein aktuell angemeldeter Peer)? → Gate für die Byte-Pipe. */
    fun knows(hashHex: String): Boolean = swarms[hashHex]?.isNotEmpty() ?: false

    fun start() {
        val server = HttpServer.create(InetSocketAddress(port), 0)
        server.createContext("/$token/announce") { ex -> safe(ex) { handleAnnounce(ex) } }
        server.createContext("/") { ex -> respond(ex, "Beam Station\n".toByteArray()) }  // harmlose Health-Antwort
        server.executor = Executors.newCachedThreadPool()
        server.start()
        log("Mini-Tracker läuft auf :$port/$token/announce")
        Thread {
            while (true) { try { Thread.sleep(60_000) } catch (_: InterruptedException) {}; cleanup() }
        }.apply { isDaemon = true }.start()
    }

    private fun handleAnnounce(ex: HttpExchange) {
        val params = parseRawQuery(ex.requestURI.rawQuery ?: "")
        val infoHash = params["info_hash"]
        val peerPort = params["port"]?.let { String(it, Charsets.US_ASCII).toIntOrNull() }
        if (infoHash == null || infoHash.size != 20 || peerPort == null) {
            respond(ex, bencodeFailure("missing or bad info_hash/port")); return
        }
        val event = params["event"]?.let { String(it, Charsets.US_ASCII) }
        val ip = ex.remoteAddress.address.address     // echte Verbindungs-IP (4 Bytes bei IPv4)
        val hashHex = toHex(infoHash)
        val swarm = swarms.getOrPut(hashHex) { ConcurrentHashMap() }
        val me = PeerKey(ip, peerPort)
        if (event == "stopped") swarm.remove(me) else swarm[me] = System.currentTimeMillis()

        // Kompakte Peer-Liste (6 Byte je Peer: 4 IP + 2 Port), den Anfragenden selbst auslassen.
        val now = System.currentTimeMillis()
        val peers = ByteArrayOutputStream()
        var count = 0
        for ((peer, seen) in swarm) {
            if (now - seen > peerTtlMs || peer.ip.size != 4 || peer == me) continue
            peers.write(peer.ip)
            peers.write((peer.port ushr 8) and 0xFF)
            peers.write(peer.port and 0xFF)
            count++
        }
        val out = ByteArrayOutputStream()
        out.write("d8:intervali".toByteArray(Charsets.US_ASCII))
        out.write(announceInterval.toString().toByteArray(Charsets.US_ASCII))
        out.write("e5:peers".toByteArray(Charsets.US_ASCII))
        val peerBytes = forceRelayPeer ?: peers.toByteArray()   // Force-Relay: nur das Relay als Peer
        out.write(peerBytes.size.toString().toByteArray(Charsets.US_ASCII))
        out.write(':'.code)
        out.write(peerBytes)
        out.write('e'.code)
        respond(ex, out.toByteArray())
        val shown = if (forceRelayPeer != null) "RELAY(force)" else "$count"
        log("announce ${hashHex.take(8)}  von ${ipStr(ip)}:$peerPort  event=${event ?: "-"}  → $shown peer(s) zurück")
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        for ((hash, swarm) in swarms) {
            swarm.entries.removeIf { now - it.value > peerTtlMs }
            if (swarm.isEmpty()) swarms.remove(hash)
        }
    }

    private inline fun safe(ex: HttpExchange, block: () -> Unit) {
        try { block() } catch (e: Exception) {
            runCatching { respond(ex, bencodeFailure("internal error")) }
            log("Tracker-Fehler: ${e.message}")
        }
    }

    private fun respond(ex: HttpExchange, body: ByteArray) {
        ex.responseHeaders.add("Content-Type", "text/plain")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun bencodeFailure(reason: String): ByteArray =
        "d14:failure reason${reason.length}:${reason}e".toByteArray(Charsets.ISO_8859_1)

    /** Roh-Query byteweise zerlegen (info_hash/peer_id sind 20 Roh-Bytes, NICHT UTF-8 dekodierbar). */
    private fun parseRawQuery(q: String): Map<String, ByteArray> {
        val map = HashMap<String, ByteArray>()
        for (pair in q.split("&")) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val k = if (idx >= 0) pair.substring(0, idx) else pair
            val v = if (idx >= 0) pair.substring(idx + 1) else ""
            map[k] = percentDecode(v)
        }
        return map
    }

    private fun percentDecode(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' && i + 2 < s.length -> {
                    val hi = Character.digit(s[i + 1], 16); val lo = Character.digit(s[i + 2], 16)
                    if (hi >= 0 && lo >= 0) { out.write((hi shl 4) or lo); i += 3 } else { out.write(c.code); i++ }
                }
                c == '+' -> { out.write(' '.code); i++ }
                else -> { out.write(c.code and 0xFF); i++ }
            }
        }
        return out.toByteArray()
    }

    private fun toHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append("%02x".format(x.toInt() and 0xFF))
        return sb.toString()
    }

    private fun ipStr(ip: ByteArray): String =
        if (ip.size == 4) ip.joinToString(".") { (it.toInt() and 0xFF).toString() } else "ipv6"
}

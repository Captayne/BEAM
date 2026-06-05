package de.systragon.beam.core

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Peer-Hinweis fürs lokale Direktverbinden.
 *
 * Die aktuelle WLAN-/Hotspot-IPv4 des Senders wird **kompakt im .beam-Dateinamen** kodiert
 * (nicht im portablen Magnetlink — der bleibt sauber und teilbar). Der Empfänger dekodiert sie
 * und verbindet direkt (`&x.pe=` im intern gebauten Magnet) — ohne Tracker/DHT/LSD-Wartezeit.
 *
 * Segment-Format: `pe` + 12 Hex-Zeichen (4 Byte IPv4 + 2 Byte Port). Keine Punkte → bricht die
 * punkt-getrennte `name.hash.beam`-Struktur nicht.
 */
object PeerHint {
    /** Lausch-Port der Session (siehe listen_interfaces in TorrentManager). */
    private const val PORT = 6881
    private val SEGMENT = Regex("^pe([0-9a-fA-F]{12})$")

    /** Kodiert die beste lokale IPv4 als Dateinamen-Segment, z. B. `pec0a8b2291ae1`. Null wenn keine. */
    fun localSegment(): String? {
        val ip = pickLocalIpv4() ?: return null
        val bytes = ip.address // 4 Byte
        val sb = StringBuilder("pe")
        for (b in bytes) sb.append("%02x".format(b.toInt() and 0xFF))
        sb.append("%04x".format(PORT))
        return sb.toString()
    }

    /** Erkennt ein Peer-Segment im Dateinamen. */
    fun isSegment(s: String): Boolean = SEGMENT.matches(s)

    /** Dekodiert ein Segment zu `ip:port`, oder null wenn es keins ist. */
    fun decode(segment: String): String? {
        val hex = SEGMENT.matchEntire(segment)?.groupValues?.get(1) ?: return null
        val o0 = hex.substring(0, 2).toInt(16)
        val o1 = hex.substring(2, 4).toInt(16)
        val o2 = hex.substring(4, 6).toInt(16)
        val o3 = hex.substring(6, 8).toInt(16)
        val port = hex.substring(8, 12).toInt(16)
        return "$o0.$o1.$o2.$o3:$port"
    }

    /**
     * Beste lokale IPv4: bevorzugt WLAN-/Hotspot-Interfaces (wlan/ap/softap) und `192.168.*`.
     * Mobilfunk-Interfaces (rmnet/ccmni/…) werden gemieden — deren CGNAT-IP wäre für die
     * Gegenseite unerreichbar.
     */
    private fun pickLocalIpv4(): Inet4Address? {
        val candidates = mutableListOf<Pair<Int, Inet4Address>>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") ||
                    name.startsWith("pdp") || name.startsWith("clat")) continue
                val wifiLike = name.startsWith("wlan") || name.startsWith("ap") ||
                    name.startsWith("swlan") || name.startsWith("softap")
                for (addr in nif.inetAddresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress || !addr.isSiteLocalAddress) continue
                    val host = addr.hostAddress ?: continue
                    val score = when {
                        host.startsWith("192.168.") && wifiLike -> 0
                        host.startsWith("192.168.") -> 1
                        wifiLike -> 2
                        host.startsWith("172.") -> 3
                        else -> 4 // 10.* zuletzt (kann Mobilfunk-CGNAT sein)
                    }
                    candidates.add(score to addr)
                }
            }
        } catch (_: Exception) {
            return null
        }
        return candidates.minByOrNull { it.first }?.second
    }
}

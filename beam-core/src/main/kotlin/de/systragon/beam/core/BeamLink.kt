package de.systragon.beam.core

import java.io.File

/**
 * Gemeinsame, plattformneutrale Helfer für das `.beam`-Format.
 *
 * Dateiname: `<Name>.<40hex-hash>[.pe<12hex>].beam`  (Hash + optionaler Peer-Hint stecken im Namen).
 * Dateiinhalt: Magnetlink (Fallback, falls ein Messenger den Namen verändert).
 */
data class BeamLink(
    val displayName: String,
    val infoHash: String,
    val peerHint: String? = null,
    val magnet: String? = null,
    /** „Backup"-Sammlung (z. B. Zeitraum-Backup) → Empfänger soll nach dem Speicherort fragen
     *  (etwa NAS-Bildersammlung), statt stumpf in den Standard-Ordner zu legen. Kodiert als
     *  Flag-Segment `.bk` im Dateinamen (vor dem Download lesbar). */
    val backup: Boolean = false
) {
    fun fileName(): String = buildString {
        append(sanitizeFileName(displayName.ifBlank { "beam-file" }))
        append('.')
        append(infoHash.lowercase())
        if (!peerHint.isNullOrBlank()) {
            append('.')
            append(peerHint)
        }
        if (backup) {
            append('.')
            append(BK_SEGMENT)
        }
        append(".beam")
    }

    fun writeTo(directory: File): File {
        directory.mkdirs()
        val out = File(directory, fileName())
        out.writeText(magnet ?: minimalMagnet(infoHash, displayName), Charsets.UTF_8)
        return out
    }

    companion object {
        private val HASH = Regex("^[0-9a-fA-F]{40}$")
        private const val BK_SEGMENT = "bk"   // Flag-Segment für „Backup"-Sammlungen

        fun minimalMagnet(infoHash: String, displayName: String? = null): String {
            val dn = displayName?.takeIf { it.isNotBlank() }?.let { "&dn=" + urlEncodeLite(it) } ?: ""
            return "magnet:?xt=urn:btih:${infoHash.lowercase()}$dn"
        }

        /** Parst einen `.beam`-Dateinamen. Findet das Hash-Segment, davor der Name, danach ggf. Peer-Hint. */
        fun parseName(name: String): BeamLink? {
            val core = name.removeSuffix(".beam")
            val parts = core.split('.')
            val hashIndex = parts.indexOfFirst { HASH.matches(it) }
            if (hashIndex < 0) return null
            val displayName = parts.take(hashIndex).joinToString(".")
            val infoHash = parts[hashIndex].lowercase()
            val after = parts.drop(hashIndex + 1)
            val peerHint = after.firstOrNull { PeerHint.isSegment(it) }
            val backup = after.any { it.equals(BK_SEGMENT, ignoreCase = true) }
            return BeamLink(displayName, infoHash, peerHint, backup = backup)
        }

        /** Liest eine `.beam`-Datei (Name primär, Inhalt als Magnet-Fallback). */
        fun parseFile(file: File): BeamLink? {
            val byName = parseName(file.name) ?: return null
            val magnet = runCatching {
                file.readText(Charsets.UTF_8).trim().takeIf { it.startsWith("magnet:") }
            }.getOrNull()
            return byName.copy(magnet = magnet)
        }

        /**
         * Baut den (intern genutzten) Magnetlink: btih + dn(Name) + optional `&x.pe=ip:port` aus
         * dem Peer-Hint. Der Peer-Hint kommt NUR aus dem Dateinamen, nie aus dem geteilten Magnet.
         */
        fun toMagnet(link: BeamLink): String {
            var m = minimalMagnet(link.infoHash, link.displayName)
            link.peerHint?.let { seg -> PeerHint.decode(seg)?.let { m += "&x.pe=$it" } }
            return m
        }

        private fun sanitizeFileName(s: String): String = s
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "beam-file" }

        private fun urlEncodeLite(s: String): String =
            java.net.URLEncoder.encode(s, Charsets.UTF_8.name()).replace("+", "%20")
    }
}

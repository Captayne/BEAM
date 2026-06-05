package de.systragon.beam.core

import org.libtorrent4j.Entry
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.create_file_entry
import org.libtorrent4j.swig.create_file_entry_vector
import org.libtorrent4j.swig.create_torrent
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.set_piece_hashes_listener
import java.io.File

/**
 * Plattformneutrale Torrent-Erzeugung (Senden). Baut aus einer Datei das `.torrent`, ermittelt den
 * InfoHash und einen Magnetlink. Genutzt vom Desktop-Senden; die Android-App hat (noch) ihre eigene
 * Variante in MainActivity.
 */
object TorrentFactory {

    data class Created(val torrentFile: File, val infoHash: String, val magnet: String)

    fun createTorrent(file: File, outDir: File): Created? {
        return try {
            val builder = TorrentBuilder()
            builder.path(file)
            builder.pieceSize(0) // 0 = libtorrent wählt sinnvolle Piece-Größe
            val bencode = builder.generate().entry().bencode()

            outDir.mkdirs()
            val torrentFile = File(outDir, "${file.nameWithoutExtension}.torrent")
            torrentFile.writeBytes(bencode)

            val infoHash = TorrentInfo(bencode).infoHash().toString()
            val magnet = BeamLink.minimalMagnet(infoHash, file.name)
            Created(torrentFile, infoHash, magnet)
        } catch (e: Exception) {
            BeamLog.e("TorrentFactory", "createTorrent fehlgeschlagen: ${e.message}")
            null
        }
    }

    /**
     * **No-Copy / In-place:** Baut einen Multi-File-Torrent aus einer KURIERTEN Datei-Liste, OHNE die
     * Dateien zu kopieren. Die Pfade im Torrent sind relativ zu [parentDir] (inkl. erstem Segment,
     * z. B. `0/DCIM/Camera/IMG.jpg`); gehasht wird direkt aus `<parentDir>/<rel>`. Zum **Seeden** den
     * `save_path = parentDir` setzen → libtorrent liest/serviert die ORIGINALE direkt von der Platte.
     * Alle [files] müssen unter [parentDir] liegen (sonst werden sie übersprungen).
     */
    fun createInPlace(parentDir: File, files: List<File>, displayName: String, outDir: File): Created? {
        return try {
            val parentPath = parentDir.absolutePath.trimEnd('/', '\\')
            val vec = create_file_entry_vector()
            var added = 0
            for (f in files) {
                if (!f.isFile) continue
                val abs = f.absolutePath
                if (!abs.startsWith(parentPath)) {
                    BeamLog.w("TorrentFactory", "in-place skip (außerhalb parent): $abs")
                    continue
                }
                val rel = abs.substring(parentPath.length).trimStart('/', '\\').replace('\\', '/')
                if (rel.isBlank()) continue
                vec.add(create_file_entry(rel, f.length()))
                added++
            }
            if (added == 0) { BeamLog.e("TorrentFactory", "createInPlace: keine gültigen Dateien"); return null }

            val ct = create_torrent(vec, 0)               // pieceSize 0 = libtorrent wählt
            val ec = error_code()
            // Hasht alle Pieces direkt aus <parentPath>/<rel> — KEINE Kopie.
            libtorrent.set_piece_hashes_ex(ct, parentPath, set_piece_hashes_listener(), ec)
            if (ec.value() != 0) {
                BeamLog.e("TorrentFactory", "set_piece_hashes_ex: ${ec.message()}")
                return null
            }
            val bencode = Entry(ct.generate()).bencode()

            outDir.mkdirs()
            val torrentFile = File(outDir, "${displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "beam" }}.torrent")
            torrentFile.writeBytes(bencode)

            val infoHash = TorrentInfo(bencode).infoHash().toString()
            val magnet = BeamLink.minimalMagnet(infoHash, displayName)
            BeamLog.i("TorrentFactory", "createInPlace: $added Dateien, hash=$infoHash")
            Created(torrentFile, infoHash, magnet)
        } catch (e: Exception) {
            BeamLog.e("TorrentFactory", "createInPlace fehlgeschlagen: ${e.message}")
            null
        }
    }
}

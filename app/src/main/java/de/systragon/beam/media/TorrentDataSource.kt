package de.systragon.beam.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.libtorrent4j.TorrentHandle
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * media3-Datenquelle, die ein noch laufendes Torrent-Download streamt: Bytes werden direkt aus der
 * (wachsenden) Datei gelesen; fehlt der benötigte Block noch, wird er priorisiert und kurz
 * gewartet, bis er da ist. Annahme: Single-File-Torrent (Beam versendet immer eine Datei).
 */
@UnstableApi
class TorrentDataSource(
    private val handle: TorrentHandle,
    private val file: File,
    private val pieceLength: Int,
    private val totalSize: Long,
    private val fileOffsetInTorrent: Long = 0L
) : BaseDataSource(true) {

    private var uri: Uri? = null
    private var position: Long = 0          // absolute Position im Torrent
    private var bytesRemaining: Long = 0
    private var raf: RandomAccessFile? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        position = fileOffsetInTorrent + dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            totalSize - dataSpec.position
        }
        if (bytesRemaining < 0) throw IOException("Ungültige Position")
        raf = RandomAccessFile(file, "r")
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val piece = (position / pieceLength).toInt()
        awaitPiece(piece)

        // Nur bis zur Blockgrenze lesen — der nächste Block ist evtl. noch nicht da.
        val pieceOffset = position % pieceLength
        val bytesLeftInPiece = pieceLength - pieceOffset
        val toRead = minOf(length.toLong(), bytesRemaining, bytesLeftInPiece).toInt()

        val f = raf ?: throw IOException("Datenquelle nicht offen")
        f.seek(position)
        val read = f.read(buffer, offset, toRead)
        if (read == -1) return C.RESULT_END_OF_INPUT

        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    /** Wartet (mit Read-Ahead + Timeout), bis der Block heruntergeladen ist. */
    private fun awaitPiece(piece: Int) {
        if (handle.havePiece(piece)) return
        try {
            handle.setPieceDeadline(piece, 0)
            handle.setPieceDeadline(piece + 1, 1000)
            handle.setPieceDeadline(piece + 2, 2000)
        } catch (_: Exception) { }

        val timeoutMs = 60_000L
        val start = System.currentTimeMillis()
        while (!handle.havePiece(piece)) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Abgebrochen")
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw IOException("Block $piece nicht rechtzeitig verfügbar")
            }
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Abgebrochen")
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        try { raf?.close() } catch (_: Exception) { }
        raf = null
    }

    @UnstableApi
    class Factory(
        private val handle: TorrentHandle,
        private val file: File,
        private val pieceLength: Int,
        private val totalSize: Long
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            TorrentDataSource(handle, file, pieceLength, totalSize)
    }
}

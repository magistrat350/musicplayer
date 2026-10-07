package com.magistrat.musicplayer.data

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset

/**
 * Liest Kapitelmarken aus MP4/M4A/M4B-Dateien (Hoerbuecher).
 * Unterstuetzt Nero-Kapitel ("chpl") und QuickTime-Kapitelspuren (Text-Track).
 */
object Mp4Chapters {
    data class Mark(val startMs: Long, val title: String)

    private class Box(val type: String, val start: Long, val dataStart: Long, val end: Long)

    fun read(context: Context, uri: Uri): List<Mark> = try {
        if (uri.scheme == "file") {
            RandomAccessFile(uri.path, "r").use { parse(it.channel) }
        } else {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { parse(it.channel) }
            } ?: emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun parse(ch: FileChannel): List<Mark> {
        val top = children(ch, 0, ch.size())
        if (top.none { it.type == "ftyp" }) return emptyList()
        val moov = top.firstOrNull { it.type == "moov" } ?: return emptyList()
        val moovKids = children(ch, moov.dataStart, moov.end)

        // 1) Nero-Kapitel: moov/udta/chpl
        moovKids.firstOrNull { it.type == "udta" }?.let { udta ->
            children(ch, udta.dataStart, udta.end).firstOrNull { it.type == "chpl" }?.let { chpl ->
                val marks = readChpl(ch, chpl)
                if (marks.size >= 2) return marks
            }
        }
        // 2) QuickTime-Kapitelspur (Text-Track)
        for (trak in moovKids.filter { it.type == "trak" }) {
            val marks = readTextTrack(ch, trak)
            if (marks.size >= 2) return marks
        }
        return emptyList()
    }

    private fun children(ch: FileChannel, from: Long, to: Long): List<Box> {
        val list = mutableListOf<Box>()
        var pos = from
        while (pos + 8 <= to) {
            val h = read(ch, pos, 16.coerceAtMost((to - pos).toInt()))
            var size = h.int.toLong() and 0xFFFFFFFFL
            val type = String(ByteArray(4).also { h.get(it) }, Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L && h.remaining() >= 8) {
                size = h.long
                header = 16
            } else if (size == 0L) {
                size = to - pos
            }
            if (size < header || pos + size > to) break
            list += Box(type, pos, pos + header, pos + size)
            pos += size
        }
        return list
    }

    private fun read(ch: FileChannel, pos: Long, len: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(len.coerceAtLeast(0))
        var p = pos
        while (buf.hasRemaining()) {
            val n = ch.read(buf, p)
            if (n <= 0) break
            p += n
        }
        buf.flip()
        return buf
    }

    private fun readChpl(ch: FileChannel, box: Box): List<Mark> {
        val b = read(ch, box.dataStart, (box.end - box.dataStart).coerceAtMost(1_000_000).toInt())
        val version = b.get().toInt()
        b.position(b.position() + 3) // flags
        if (version != 0) b.int // reserved
        val count = b.get().toInt() and 0xFF
        val marks = mutableListOf<Mark>()
        repeat(count) {
            if (b.remaining() < 9) return marks
            val start100ns = b.long
            val len = b.get().toInt() and 0xFF
            if (b.remaining() < len) return marks
            val title = String(ByteArray(len).also { b.get(it) }, Charsets.UTF_8)
            marks += Mark(start100ns / 10_000, title.trim())
        }
        return marks
    }

    private fun readTextTrack(ch: FileChannel, trak: Box): List<Mark> {
        val mdia = children(ch, trak.dataStart, trak.end).firstOrNull { it.type == "mdia" } ?: return emptyList()
        val mdiaKids = children(ch, mdia.dataStart, mdia.end)
        val hdlr = mdiaKids.firstOrNull { it.type == "hdlr" } ?: return emptyList()
        val handler = read(ch, hdlr.dataStart + 8, 4).let { String(ByteArray(4).also { a -> it.get(a) }, Charsets.ISO_8859_1) }
        if (handler != "text" && handler != "sbtl") return emptyList()

        val mdhd = mdiaKids.firstOrNull { it.type == "mdhd" } ?: return emptyList()
        val mh = read(ch, mdhd.dataStart, 32)
        val timescale = if (mh.get(0).toInt() == 1) mh.getInt(20) else mh.getInt(12)
        if (timescale <= 0) return emptyList()

        val minf = mdiaKids.firstOrNull { it.type == "minf" } ?: return emptyList()
        val stbl = children(ch, minf.dataStart, minf.end).firstOrNull { it.type == "stbl" } ?: return emptyList()
        val boxes = children(ch, stbl.dataStart, stbl.end).associateBy { it.type }

        // Startzeiten aus stts
        val stts = boxes["stts"] ?: return emptyList()
        val st = read(ch, stts.dataStart, (stts.end - stts.dataStart).coerceAtMost(4_000_000).toInt())
        st.int
        val starts = mutableListOf<Long>()
        var t = 0L
        repeat(st.int) {
            val cnt = st.int
            val delta = st.int.toLong() and 0xFFFFFFFFL
            repeat(cnt) {
                starts += t
                t += delta
            }
        }
        val sampleCount = starts.size
        if (sampleCount < 2 || sampleCount > 5000) return emptyList()

        // Groessen aus stsz
        val stsz = boxes["stsz"] ?: return emptyList()
        val sz = read(ch, stsz.dataStart, (stsz.end - stsz.dataStart).coerceAtMost(4_000_000).toInt())
        sz.int
        val uniform = sz.int
        val n = sz.int
        val sizes = IntArray(sampleCount) { i -> if (uniform != 0) uniform else if (i < n) sz.int else 0 }

        // Chunk-Offsets aus stco / co64
        val offsets = mutableListOf<Long>()
        boxes["stco"]?.let { b ->
            val bb = read(ch, b.dataStart, (b.end - b.dataStart).coerceAtMost(4_000_000).toInt())
            bb.int
            repeat(bb.int) { offsets += bb.int.toLong() and 0xFFFFFFFFL }
        } ?: boxes["co64"]?.let { b ->
            val bb = read(ch, b.dataStart, (b.end - b.dataStart).coerceAtMost(8_000_000).toInt())
            bb.int
            repeat(bb.int) { offsets += bb.long }
        }
        if (offsets.isEmpty()) return emptyList()

        // Samples pro Chunk aus stsc
        val stsc = boxes["stsc"] ?: return emptyList()
        val sc = read(ch, stsc.dataStart, (stsc.end - stsc.dataStart).coerceAtMost(4_000_000).toInt())
        sc.int
        val runs = List(sc.int) { Triple(sc.int, sc.int, sc.int) } // firstChunk (1-basiert), samplesPerChunk, descIndex

        val marks = mutableListOf<Mark>()
        var sample = 0
        for (chunk in offsets.indices) {
            val per = runs.lastOrNull { it.first <= chunk + 1 }?.second ?: 1
            var off = offsets[chunk]
            repeat(per) {
                if (sample >= sampleCount) return@repeat
                val size = sizes[sample]
                if (size >= 2) {
                    val data = read(ch, off, size.coerceAtMost(1024))
                    val len = (data.short.toInt() and 0xFFFF).coerceAtMost(data.remaining())
                    val bytes = ByteArray(len).also { data.get(it) }
                    val title = decodeText(bytes)
                    marks += Mark(starts[sample] * 1000 / timescale, title.trim())
                }
                off += size
                sample++
            }
        }
        return marks
    }

    private fun decodeText(b: ByteArray): String =
        if (b.size >= 2 && ((b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) || (b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()))) {
            String(b, Charset.forName("UTF-16"))
        } else {
            String(b, Charsets.UTF_8)
        }
}

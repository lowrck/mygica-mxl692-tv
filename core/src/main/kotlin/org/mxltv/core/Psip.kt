// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * MPEG PSI (PAT/PMT) and ATSC A/65 PSIP (MGT, TVCT/CVCT, EIT, ETT, STT) parsing.
 */
package org.mxltv.core

import java.util.concurrent.ConcurrentHashMap

data class EsInfo(val streamType: Int, val pid: Int)

data class PmtInfo(val programNumber: Int, val pcrPid: Int, val streams: List<EsInfo>) {
    val hasVideo get() = streams.any { it.streamType in VIDEO_TYPES }
    val hasAudio get() = streams.any { it.streamType in AUDIO_TYPES }

    companion object {
        val VIDEO_TYPES = setOf(0x01, 0x02, 0x1b, 0x24, 0x80)
        val AUDIO_TYPES = setOf(0x03, 0x04, 0x0f, 0x11, 0x81, 0x87)
    }
}

data class VctChannel(
    val shortName: String,
    val major: Int,
    val minor: Int,
    val tsid: Int,
    val programNumber: Int,
    val hidden: Boolean,
    val hideGuide: Boolean,
    val serviceType: Int,
    val sourceId: Int,
    val longName: String?,
)

data class EpgEvent(
    val sourceId: Int,
    val eventId: Int,
    val startUtcMs: Long,
    val durationSec: Int,
    val title: String,
    var description: String? = null,
) {
    val endUtcMs get() = startUtcMs + durationSec * 1000L
}

private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xff
private fun u16(b: ByteArray, o: Int) = (u8(b, o) shl 8) or u8(b, o + 1)
private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()

const val GPS_EPOCH_UNIX_S = 315964800L

/**
 * ATSC multiple_string_structure. Only uncompressed segments are decoded;
 * Huffman-compressed (A/65 Annex C) segments are skipped.
 */
object Mss {
    fun decode(b: ByteArray, off: Int, len: Int, preferLang: String = "eng"): String {
        if (len < 1) return ""
        val n = u8(b, off)
        var p = off + 1
        val end = off + len
        val strings = ArrayList<Pair<String, String>>()
        repeat(n) {
            if (p + 4 > end) return@repeat
            val lang = String(b, p, 3, Charsets.ISO_8859_1)
            val segs = u8(b, p + 3)
            p += 4
            val sb = StringBuilder()
            repeat(segs) {
                if (p + 3 > end) return@repeat
                val comp = u8(b, p)
                val mode = u8(b, p + 1)
                val nb = u8(b, p + 2)
                p += 3
                if (p + nb > end) return@repeat
                if (comp == 0) {
                    when {
                        mode == 0x3f -> sb.append(String(b, p, nb and 1.inv(), Charsets.UTF_16BE))
                        mode <= 0x33 -> for (i in 0 until nb) sb.append(((mode shl 8) or u8(b, p + i)).toChar())
                        else -> {}
                    }
                }
                p += nb
            }
            strings += lang to sb.toString().trim()
        }
        val text = (strings.firstOrNull { it.first == preferLang } ?: strings.firstOrNull())?.second ?: ""
        return unescapeEntities(text)
    }

    private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|quot|amp|apos|lt|gt|nbsp);")

    /** Some stations send HTML entities in guide text. */
    fun unescapeEntities(s: String): String {
        if ('&' !in s) return s
        return entity.replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                e == "quot" -> "\""
                e == "amp" -> "&"
                e == "apos" -> "'"
                e == "lt" -> "<"
                e == "gt" -> ">"
                else -> " "
            }
        }
    }
}

/**
 * Collects PSI/PSIP from one transport stream (one RF channel).
 * Thread-safe for one writer (the stream thread) and many readers.
 */
class PsipCollector {
    @Volatile var tsid = -1; private set
    @Volatile var pat: Map<Int, Int> = emptyMap(); private set // program -> PMT PID
    val pmts = ConcurrentHashMap<Int, PmtInfo>()
    val vct = ConcurrentHashMap<Int, VctChannel>() // key: major*1000+minor
    @Volatile var vctComplete = false; private set
    @Volatile var gpsUtcOffset = 18; private set
    @Volatile var stTimeUtcMs = 0L; private set
    val events = ConcurrentHashMap<Long, EpgEvent>() // (sourceId<<16)|eventId
    private val ettText = ConcurrentHashMap<Long, String>() // ETM id -> text

    private val eitPids = ConcurrentHashMap<Int, Int>() // pid -> EIT index
    private val ettPids = ConcurrentHashMap<Int, Int>()
    private val assemblers = HashMap<Int, SectionAssembler>()
    private val seen = HashSet<Long>()
    private val vctSections = HashMap<Int, MutableSet<Int>>()
    private val vctLast = HashMap<Int, Int>()

    @Volatile var eitSectionsSeen = 0; private set
    @Volatile var lastEpgChangeMs = 0L; private set

    /** PIDs seen with transport_scrambling_control set (encrypted, e.g. most cable channels). */
    val scrambledPids: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    fun onPacket(p: ByteArray, o: Int) {
        if (p[o + 1].toInt() and 0x80 != 0) return // transport error
        val pid = tsPid(p, o)
        if (p[o + 3].toInt() and 0xc0 != 0) scrambledPids.add(pid)
        val interesting = pid == 0 || pid == 0x1ffb || pid in pat.values || eitPids.containsKey(pid) ||
            ettPids.containsKey(pid)
        if (!interesting) return
        assemblers.getOrPut(pid) { SectionAssembler { onSection(pid, it) } }.feed(p, o)
    }

    private fun onSection(pid: Int, s: ByteArray) {
        if (s.size < 8) return
        val crc = if (s.size >= 4) u32(s, s.size - 4) else 0
        val key = (pid.toLong() shl 32) or crc
        val tid = u8(s, 0)
        if (tid != 0xcd) { // STT changes every second; always parse it
            synchronized(seen) {
                if (!seen.add(key)) return
            }
        }
        try {
            when (tid) {
                0x00 -> if (pid == 0) parsePat(s)
                0x02 -> parsePmt(s)
                0xc7 -> if (pid == 0x1ffb) parseMgt(s)
                0xc8, 0xc9 -> if (pid == 0x1ffb) parseVct(s)
                0xcb -> parseEit(s)
                0xcc -> parseEtt(s)
                0xcd -> if (pid == 0x1ffb) parseStt(s)
            }
        } catch (_: IndexOutOfBoundsException) {
            // malformed section; ignore
        }
    }

    private fun parsePat(s: ByteArray) {
        tsid = u16(s, 3)
        val m = HashMap(pat)
        var p = 8
        val end = s.size - 4
        while (p + 4 <= end) {
            val prog = u16(s, p)
            val pid = u16(s, p + 2) and 0x1fff
            if (prog != 0) m[prog] = pid
            p += 4
        }
        pat = m
    }

    private fun parsePmt(s: ByteArray) {
        val prog = u16(s, 3)
        val pcr = u16(s, 8) and 0x1fff
        val piLen = u16(s, 10) and 0xfff
        var p = 12 + piLen
        val end = s.size - 4
        val streams = ArrayList<EsInfo>()
        while (p + 5 <= end) {
            val type = u8(s, p)
            val pid = u16(s, p + 1) and 0x1fff
            val esLen = u16(s, p + 3) and 0xfff
            streams += EsInfo(type, pid)
            p += 5 + esLen
        }
        pmts[prog] = PmtInfo(prog, pcr, streams)
    }

    private fun parseMgt(s: ByteArray) {
        val n = u16(s, 9)
        var p = 11
        repeat(n) {
            val type = u16(s, p)
            val pid = u16(s, p + 2) and 0x1fff
            val dLen = u16(s, p + 9) and 0xfff
            when (type) {
                in 0x0100..0x017f -> eitPids[pid] = type - 0x0100
                in 0x0200..0x027f -> ettPids[pid] = type - 0x0200
            }
            p += 11 + dLen
        }
    }

    private fun parseVct(s: ByteArray) {
        val secNo = u8(s, 6)
        val last = u8(s, 7)
        val n = u8(s, 9)
        var p = 10
        repeat(n) {
            val name = String(s, p, 14, Charsets.UTF_16BE).trimEnd('\u0000', ' ')
            val b0 = u8(s, p + 14); val b1 = u8(s, p + 15); val b2 = u8(s, p + 16)
            val major = ((b0 and 0x0f) shl 6) or (b1 ushr 2)
            val minor = ((b1 and 0x03) shl 8) or b2
            val chTsid = u16(s, p + 22)
            val prog = u16(s, p + 24)
            val flags = u8(s, p + 26)
            val svc = u8(s, p + 27) and 0x3f
            val src = u16(s, p + 28)
            val dLen = u16(s, p + 30) and 0x3ff
            var longName: String? = null
            var d = p + 32
            while (d + 2 <= p + 32 + dLen) {
                val tag = u8(s, d); val l = u8(s, d + 1)
                if (tag == 0xa0) longName = Mss.decode(s, d + 2, l).ifBlank { null }
                d += 2 + l
            }
            vct[major * 1000 + minor] = VctChannel(
                shortName = name, major = major, minor = minor, tsid = chTsid, programNumber = prog,
                hidden = flags and 0x10 != 0, hideGuide = flags and 0x02 != 0, serviceType = svc,
                sourceId = src, longName = longName,
            )
            p += 32 + dLen
        }
        synchronized(vctSections) {
            val ext = u16(s, 3)
            vctLast[ext] = last
            vctSections.getOrPut(ext) { HashSet() }.add(secNo)
            vctComplete = vctSections.all { (e, got) -> got.size >= (vctLast[e] ?: 0) + 1 }
        }
    }

    private fun gpsToUtcMs(gps: Long) = (gps + GPS_EPOCH_UNIX_S - gpsUtcOffset) * 1000L

    private fun parseEit(s: ByteArray) {
        val src = u16(s, 3)
        val n = u8(s, 9)
        var p = 10
        repeat(n) {
            val eventId = u16(s, p) and 0x3fff
            val start = u32(s, p + 2)
            val dur = ((u8(s, p + 6) and 0x0f) shl 16) or u16(s, p + 7)
            val tLen = u8(s, p + 9)
            val title = Mss.decode(s, p + 10, tLen)
            p += 10 + tLen
            val dLen = u16(s, p) and 0xfff
            p += 2 + dLen
            val key = (src.toLong() shl 16) or eventId.toLong()
            val etmId = (src.toLong() shl 16) or (eventId.toLong() shl 2) or 2L
            val ev = EpgEvent(src, eventId, gpsToUtcMs(start), dur, title, ettText[etmId])
            val old = events.put(key, ev)
            if (old == null || old.title != ev.title || old.startUtcMs != ev.startUtcMs) {
                lastEpgChangeMs = System.currentTimeMillis()
            }
        }
        eitSectionsSeen++
    }

    private fun parseEtt(s: ByteArray) {
        val etmId = u32(s, 9)
        val text = Mss.decode(s, 13, s.size - 4 - 13)
        if (etmId and 3L != 2L) return // only event ETMs
        ettText[etmId] = text
        val src = (etmId ushr 16) and 0xffff
        val ev = (etmId ushr 2) and 0x3fff
        events[(src shl 16) or ev]?.description = text
    }

    private fun parseStt(s: ByteArray) {
        val t = u32(s, 9)
        gpsUtcOffset = u8(s, 13)
        stTimeUtcMs = gpsToUtcMs(t)
    }

    /** Number of distinct EIT tables we know about (from the MGT). */
    val eitTableCount get() = eitPids.size
}

// SPDX-License-Identifier: GPL-2.0-or-later
package org.mxltv.core

const val TS_SIZE = 188
const val TS_SYNC: Byte = 0x47

/** Re-aligns an arbitrary byte stream into 188-byte TS packets. */
class TsSync(private val onPacket: (ByteArray, Int) -> Unit) {
    private val carry = ByteArray(TS_SIZE * 2)
    private var carryLen = 0

    fun feed(data: ByteArray, off: Int, len: Int) {
        var p = off
        val end = off + len
        // Finish a partial packet from the previous call.
        if (carryLen > 0) {
            val need = TS_SIZE - carryLen
            if (len >= need) {
                System.arraycopy(data, p, carry, carryLen, need)
                if (carry[0] == TS_SYNC && (p + need >= end || data[p + need] == TS_SYNC)) {
                    onPacket(carry, 0)
                    p += need
                }
                carryLen = 0
            } else {
                System.arraycopy(data, p, carry, carryLen, len)
                carryLen += len
                return
            }
        }
        while (p < end) {
            if (data[p] != TS_SYNC || (p + TS_SIZE < end && data[p + TS_SIZE] != TS_SYNC)) {
                p++
                continue
            }
            if (p + TS_SIZE <= end) {
                onPacket(data, p)
                p += TS_SIZE
            } else {
                carryLen = end - p
                System.arraycopy(data, p, carry, 0, carryLen)
                return
            }
        }
    }
}

fun tsPid(b: ByteArray, o: Int) = ((b[o + 1].toInt() and 0x1f) shl 8) or (b[o + 2].toInt() and 0xff)

object Crc32Mpeg {
    private val table = IntArray(256) { i ->
        var c = i shl 24
        repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
        c
    }

    fun compute(b: ByteArray, off: Int, len: Int): Int {
        var crc = -1
        for (i in off until off + len) crc = (crc shl 8) xor table[((crc ushr 24) xor b[i].toInt()) and 0xff]
        return crc
    }
}

/** Reassembles PSI/PSIP sections for one PID. Calls [onSection] with CRC-checked sections. */
class SectionAssembler(private val onSection: (ByteArray) -> Unit) {
    private var buf = ByteArray(4096 + 188)
    private var len = 0
    private var active = false
    private var lastCc = -1

    fun feed(p: ByteArray, o: Int) {
        val pusi = p[o + 1].toInt() and 0x40 != 0
        val afc = (p[o + 3].toInt() ushr 4) and 3
        val cc = p[o + 3].toInt() and 0x0f
        if (afc == 0 || afc == 2) return
        if (lastCc >= 0 && cc != ((lastCc + 1) and 0x0f) && cc != lastCc) active = false
        if (cc == lastCc && lastCc >= 0) return // duplicate
        lastCc = cc
        var start = o + 4
        if (afc == 3) start += 1 + (p[o + 4].toInt() and 0xff)
        val end = o + TS_SIZE
        if (start >= end) return
        if (pusi) {
            val pointer = p[start].toInt() and 0xff
            start++
            if (active && pointer > 0) append(p, start, minOf(pointer, end - start))
            active = true
            len = 0
            start += pointer
            if (start >= end) { active = false; return }
            append(p, start, end - start)
        } else if (active) {
            append(p, start, end - start)
        }
    }

    private fun append(p: ByteArray, off: Int, n: Int) {
        if (n <= 0) return
        if (len + n > buf.size) { active = false; len = 0; return }
        System.arraycopy(p, off, buf, len, n)
        len += n
        drain()
    }

    private fun drain() {
        var pos = 0
        while (true) {
            if (len - pos < 3) break
            if (buf[pos] == 0xff.toByte()) { pos = len; break } // stuffing
            val secLen = 3 + (((buf[pos + 1].toInt() and 0x0f) shl 8) or (buf[pos + 2].toInt() and 0xff))
            if (len - pos < secLen) break
            val syntax = buf[pos + 1].toInt() and 0x80 != 0
            if (!syntax || Crc32Mpeg.compute(buf, pos, secLen) == 0) {
                onSection(buf.copyOfRange(pos, pos + secLen))
            }
            pos += secLen
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos)
            len -= pos
        }
    }
}

/** Counts packets, transport-error-flagged packets and continuity-counter gaps across a stream. */
class TsStats {
    @Volatile var packets = 0L; private set
    @Volatile var transportErrors = 0L; private set
    @Volatile var ccErrors = 0L; private set
    private val lastCc = IntArray(8192) { -1 }

    fun onPacket(p: ByteArray, o: Int) {
        packets++
        if (p[o + 1].toInt() and 0x80 != 0) { transportErrors++; return }
        val pid = tsPid(p, o)
        if (pid == 0x1fff) return
        val afc = (p[o + 3].toInt() ushr 4) and 3
        val cc = p[o + 3].toInt() and 0x0f
        if (afc and 1 == 0) return // no payload: CC does not advance
        val last = lastCc[pid]
        if (last >= 0 && cc != ((last + 1) and 0x0f) && cc != last) ccErrors++
        lastCc[pid] = cc
    }

    override fun toString() = "packets=$packets tei=$transportErrors ccErr=$ccErrors"
}

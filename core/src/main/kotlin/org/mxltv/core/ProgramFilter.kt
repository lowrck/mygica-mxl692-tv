// SPDX-License-Identifier: GPL-2.0-or-later
package org.mxltv.core

/**
 * Cuts a single program out of a full ATSC multiplex: passes its PMT, PCR and
 * elementary stream packets, and replaces the PAT with one listing only that
 * program. Output is written in 188-byte packets to [out].
 */
class ProgramFilter(
    private val programNumber: Int,
    private val psip: PsipCollector,
    private val out: (ByteArray, Int, Int) -> Unit,
) {
    private var patCc = 0
    private var pids: Set<Int> = emptySet()
    private var pidsFromPmt: PmtInfo? = null

    private fun refreshPids(): Int? {
        val pmtPid = psip.pat[programNumber] ?: return null
        val pmt = psip.pmts[programNumber]
        if (pmt !== pidsFromPmt || pids.isEmpty()) {
            pidsFromPmt = pmt
            val s = HashSet<Int>()
            s += pmtPid
            if (pmt != null) {
                s += pmt.pcrPid
                pmt.streams.forEach { s += it.pid }
            }
            pids = s
        }
        return pmtPid
    }

    fun onPacket(p: ByteArray, o: Int) {
        val pid = tsPid(p, o)
        if (pid == 0) {
            val pmtPid = refreshPids() ?: return
            out(makePat(pmtPid), 0, TS_SIZE)
            return
        }
        if (pid in pids) out(p, o, TS_SIZE)
    }

    private fun makePat(pmtPid: Int): ByteArray {
        val pkt = ByteArray(TS_SIZE) { 0xff.toByte() }
        pkt[0] = TS_SYNC
        pkt[1] = 0x40 // PUSI, PID 0
        pkt[2] = 0
        pkt[3] = (0x10 or (patCc and 0x0f)).toByte()
        patCc++
        pkt[4] = 0 // pointer field
        val tsid = psip.tsid.coerceAtLeast(0)
        val sec = byteArrayOf(
            0x00, 0xb0.toByte(), 13, // table id, syntax, length = 5 + 4 + 4
            (tsid shr 8).toByte(), tsid.toByte(),
            0xc1.toByte(), 0, 0, // version 0, current, section 0/0
            (programNumber shr 8).toByte(), programNumber.toByte(),
            (0xe0 or (pmtPid shr 8)).toByte(), pmtPid.toByte(),
        )
        sec.copyInto(pkt, 5)
        val crc = Crc32Mpeg.compute(sec, 0, sec.size)
        pkt[5 + sec.size] = (crc ushr 24).toByte()
        pkt[6 + sec.size] = (crc ushr 16).toByte()
        pkt[7 + sec.size] = (crc ushr 8).toByte()
        pkt[8 + sec.size] = crc.toByte()
        return pkt
    }
}

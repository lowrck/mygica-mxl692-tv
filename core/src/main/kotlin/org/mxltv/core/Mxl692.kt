// SPDX-License-Identifier: GPL-2.0
/*
 * Userspace port of the MaxLinear MxL692 tuner/demodulator driver,
 * configured as in "[RFC PATCH 2/2] media: dvb-usb-v2: Add support for
 * Geniatech/MyGica A681B and PT682C" (Forest Crossman).
 *
 * Based on drivers/media/dvb-frontends/mxl692.c from Linux
 * Copyright (C) 2020 Brad Love <brad@nextdimension.cc>
 * based on code Copyright (c) 2016 MaxLinear, Inc., released under GPL V2
 *
 * Wire format notes (host is little-endian, as in the kernel driver):
 *  - "mem" accesses (0xFC write / 0xFB read) carry the address and value
 *    little-endian, unswapped.
 *  - "op" messages are an 8-byte header + payload in which multi-byte
 *    fields are big-endian; the whole message is then byte-reversed in
 *    4-byte groups on the wire (convert_endian()).
 */
package org.mxltv.core

import java.io.IOException

open class Mxl692Exception(msg: String) : IOException(msg)

/** The chip/firmware refused the demodulator type (e.g. QAM on an ATSC-only MxL691). */
class UnsupportedModulationException(val modulation: Modulation, msg: String) : Mxl692Exception(msg)

data class AtscStatus(
    val snrDbTenths: Int,
    val timingOffset: Int,
    val carrierOffsetHz: Int,
    val frameLock: Boolean,
    val atscLock: Boolean,
    val fecLock: Boolean,
)

data class AtscErrors(val errorPackets: Long, val totalPackets: Long, val errorBytes: Long)

/** Over-the-air 8VSB or US cable (ITU-T J.83 Annex B) QAM. */
enum class Modulation { ATSC, QAM }

data class QamStatus(
    val qamType: Int, // 0=16, 1=64, 2=256, 3=1024, 4=32, 5=128, 6=QPSK
    val qamLocked: Boolean,
    val fecLocked: Boolean,
    val mpegLocked: Boolean,
    val snrDbTenths: Int,
    val carrierOffsetHz: Int,
)

data class QamErrors(val corrected: Long, val uncorrected: Long, val totalCodewords: Long)

data class AgcStatus(val locked: Boolean, val rawAgcGain: Int, val rxPowerDbHundredths: Int)

data class Mxl692Version(val chipId: String, val firmware: String) {
    /** QAM (cable) needs an MxL692 or MxL248; the MxL691 is ATSC-only. */
    val supportsQam get() = chipId == "692" || chipId == "248"
}

class Mxl692(private val i2c: I2cBus, private val addr: Int = 0x60) {
    private object Op {
        const val DEMOD_TYPE_SET = 0
        const val MPEG_OUT_PARAMS_SET = 1
        const val POWERMODE_SET = 2
        const val VERSION_GET = 7
        const val STATUS_GET = 8
        const val TUNER_CHANNEL_TUNE_SET = 10
        const val TUNER_AGC_STATUS_GET = 12
        const val ATSC_INIT_SET = 13
        const val ATSC_STATUS_GET = 15
        const val ATSC_ERROR_COUNTERS_GET = 16
        const val QAM_PARAMS_SET = 19
        const val QAM_RESTART_SET = 20
        const val QAM_STATUS_GET = 21
        const val QAM_ERROR_COUNTERS_GET = 22
    }

    companion object {
        const val FIRMWARE_NAME = "dvb-demod-mxl692.fw"
        private const val HDR = 8
        private const val MAX_PACKET = 58
        private const val MHEADER = 6
        private const val PHEADER = 2
        private const val DEMOD_QAM = 0
        private const val DEMOD_ATSC = 2
        private const val POWER_ACTIVE = 1
        private const val POWER_SLEEP = 0
        private val FW_MAGIC = byteArrayOf(0x4D, 0x31, 0x10, 0x02, 0x40, 0x00, 0x00, 0x80.toByte())

        /** mxl692_validate_fw_header(); returns null if OK, else a reason. */
        fun validateFirmware(fw: ByteArray): String? {
            if (fw.size < 16 || fw.size > 76 * 1000) return "wrong size (${fw.size} bytes)"
            for (i in 0 until 8) if (fw[i] != FW_MAGIC[i]) return "bad header magic"
            val declared = be32(fw, 8) ushr 8
            if (fw.size - 16L != declared) return "length mismatch"
            var sum = 0
            for (i in 16 until fw.size) sum += fw[i].toInt() and 0xff
            if ((sum and 0xff) != (fw[11].toInt() and 0xff)) return "checksum mismatch"
            return null
        }

        internal fun be32(b: ByteArray, off: Int): Long =
            ((b[off].toLong() and 0xff) shl 24) or ((b[off + 1].toLong() and 0xff) shl 16) or
                ((b[off + 2].toLong() and 0xff) shl 8) or (b[off + 3].toLong() and 0xff)

        internal fun le32(v: Long) = byteArrayOf(
            v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()
        )

        /** convert_endian(): reverse each 4-byte group; trailing 2/3 bytes reversed too. */
        internal fun swapWords(d: ByteArray, off: Int = 0, size: Int = d.size - off) {
            var i = 0
            while (i + 4 <= size) {
                val a = d[off + i]; d[off + i] = d[off + i + 3]; d[off + i + 3] = a
                val b = d[off + i + 1]; d[off + i + 1] = d[off + i + 2]; d[off + i + 2] = b
                i += 4
            }
            when (size and 3) {
                2 -> { val a = d[off + i]; d[off + i] = d[off + i + 1]; d[off + i + 1] = a }
                3 -> { val a = d[off + i]; d[off + i] = d[off + i + 2]; d[off + i + 2] = a }
            }
        }

        /** mxl692_checksum(): sum of big-endian words (zero padded) XOR 0xDEADBEEF. */
        internal fun checksum(buf: ByteArray, size: Int): Long {
            var sum = 0L
            val words = (size + 3) / 4
            for (w in 0 until words) {
                var v = 0L
                for (k in 0 until 4) {
                    val idx = w * 4 + k
                    v = (v shl 8) or (if (idx < buf.size) buf[idx].toLong() and 0xff else 0L)
                }
                sum = (sum + v) and 0xffffffffL
            }
            return sum xor 0xDEADBEEFL
        }
    }

    private var seq = 1
    private val lock = Any()

    var version: Mxl692Version? = null
        private set

    // ---- low level -------------------------------------------------------

    private fun memWrite(address: Long, value: Long) {
        val b = ByteArray(MHEADER + 4)
        b[0] = 0xFC.toByte()
        b[1] = (4 + 4).toByte()
        le32(address).copyInto(b, 2)
        le32(value).copyInto(b, 6)
        i2c.write(addr, b)
    }

    private fun memRead(address: Long): Long {
        val b = ByteArray(MHEADER)
        b[0] = 0xFB.toByte()
        b[1] = 4
        le32(address).copyInto(b, 2)
        synchronized(lock) {
            i2c.write(addr, b)
            val r = i2c.read(addr, 4)
            return (r[0].toLong() and 0xff) or ((r[1].toLong() and 0xff) shl 8) or
                ((r[2].toLong() and 0xff) shl 16) or ((r[3].toLong() and 0xff) shl 24)
        }
    }

    private fun opWrite(msg: ByteArray, size: Int) {
        val total = (size + 3) and 3.inv()
        val b = ByteArray(PHEADER + total)
        b[0] = 0xFE.toByte()
        b[1] = total.toByte()
        msg.copyInto(b, PHEADER, 0, minOf(total, msg.size))
        swapWords(b, PHEADER, total)
        i2c.write(addr, b)
    }

    private fun opRead(size: Int): ByteArray {
        i2c.write(addr, byteArrayOf(0xFD.toByte(), 0))
        val total = (size + 3) and 3.inv()
        val out = ByteArray(total)
        var ix = 0
        while (ix < total) {
            i2c.read(addr, 4).copyInto(out, ix)
            ix += 4
        }
        swapWords(out)
        return out
    }

    /**
     * mxl692_i2c_writeread(). [tx] holds the payload with multi-byte fields
     * already big-endian; the returned payload is big-endian too.
     */
    private fun writeRead(opcode: Int, tx: ByteArray = ByteArray(0), rxExpected: Int = 0): ByteArray =
        synchronized(lock) {
            require(tx.size + HDR <= MAX_PACKET - PHEADER)
            val msg = ByteArray((HDR + tx.size + 3) and 3.inv())
            val mySeq = seq
            seq = if (seq >= 255) 1 else seq + 1
            msg[0] = opcode.toByte()
            msg[1] = mySeq.toByte()
            msg[2] = tx.size.toByte()
            tx.copyInto(msg, HDR)
            val ck = checksum(msg, HDR + tx.size)
            msg[4] = (ck ushr 24).toByte(); msg[5] = (ck ushr 16).toByte()
            msg[6] = (ck ushr 8).toByte(); msg[7] = ck.toByte()
            opWrite(msg, HDR + tx.size)

            var rx: ByteArray
            var tries = 40
            var empty: Boolean
            do {
                rx = opRead(rxExpected + HDR)
                Thread.sleep(1, 500_000)
                tries--
                empty = rx[1].toInt() == 0 && rx[4].toInt() == 0 && rx[5].toInt() == 0 &&
                    rx[6].toInt() == 0 && rx[7].toInt() == 0
            } while (empty && tries > 0)
            if (empty) throw Mxl692Exception("timeout waiting for opcode $opcode")
            if (rx[3].toInt() != 0) throw Mxl692Exception("opcode $opcode status ${rx[3]}")
            val rxSize = rx[2].toInt() and 0xff
            if (rx[1].toInt() and 0xff != mySeq || rx[0].toInt() and 0xff != opcode || rxSize != rxExpected) {
                throw Mxl692Exception(
                    "bad response to opcode $opcode: op=${rx[0]} seq=${rx[1]}/$mySeq size=$rxSize/$rxExpected"
                )
            }
            val got = be32(rx, 4)
            rx[4] = 0; rx[5] = 0; rx[6] = 0; rx[7] = 0
            if (checksum(rx, HDR + rxSize) != got) throw Mxl692Exception("rx checksum failure, opcode $opcode")
            rx.copyOfRange(HDR, HDR + rxSize)
        }

    // ---- init ------------------------------------------------------------

    private fun writeFwBlock(fw: ByteArray, start: Int): Int {
        var ix = start
        if (fw[ix].toInt() != 0x53) throw Mxl692Exception("bad firmware segment at $ix")
        var totalLen = ((fw[ix + 1].toInt() and 0xff) shl 16) or ((fw[ix + 2].toInt() and 0xff) shl 8) or
            (fw[ix + 3].toInt() and 0xff)
        totalLen = (totalLen + 3) and 3.inv()
        val address = be32(fw, ix + 4)
        ix += 8
        val payloadMax = MAX_PACKET - MHEADER
        var prev = 0L
        while (totalLen > 0) {
            val chunk = minOf(totalLen, payloadMax)
            val b = ByteArray(MHEADER + chunk)
            b[0] = 0xFC.toByte()
            b[1] = (chunk + 4).toByte()
            le32(address + prev).copyInto(b, 2)
            for (k in 0 until chunk) b[MHEADER + k] = if (ix + k < fw.size) fw[ix + k] else 0
            swapWords(b, MHEADER, chunk)
            i2c.write(addr, b)
            prev += chunk
            totalLen -= chunk
            ix += chunk
        }
        return ix
    }

    private fun fwDownload(fw: ByteArray) {
        validateFirmware(fw)?.let { throw Mxl692Exception("invalid firmware: $it") }
        synchronized(lock) {
            var ix = writeFwBlock(fw, 16) // DRAM
            ix = writeFwBlock(fw, ix) // IRAM
            memWrite(0x70000018, 1) // release CPU from reset
        }
        Thread.sleep(50)
        writeRead(Op.STATUS_GET, rxExpected = 4)
    }

    private fun readVersion(): Mxl692Version {
        val v = writeRead(Op.VERSION_GET, rxExpected = 11)
        val ids = arrayOf("N/A", "691", "248", "692")
        val chip = ids.getOrElse(v[0].toInt() and 0xff) { "?" }
        fun u(i: Int) = v[i].toInt() and 0xff
        val fwv = "${u(1)}.${u(2)}.${u(3)}.${u(4)}_RC${u(5)}"
        return Mxl692Version(chip, fwv)
    }

    private fun configRegulatorsDual() {
        var r = memRead(0x90000000)
        r = (r and 0x00FFFFFF) or 0x10000000
        memWrite(0x90000000, r)
        r = memRead(0x90000018) or 0x800
        memWrite(0x90000018, r)
    }

    /** mxl692_config_xtal() with the dvbsky settings: cap 26, calibration on, sharing off. */
    private fun configXtal(calibration: Boolean = true, sharing: Boolean = false) {
        var r = memRead(0x90000000)
        r = (r and 0xFFFFFFE0) or 26L
        r = r and 0xFFFFFEFF // clk out off
        memWrite(0x90000000, r)
        r = r and 0xFFFFFDFF // clk out divider off
        memWrite(0x90000000, r)
        r = if (sharing) r or 0x010400 else r and 0xFFFEFBFF
        memWrite(0x90000000, r)

        var r1 = memRead(0x90000030)
        if (calibration) {
            r1 = (r1 and 0xFFFFFFFD) or 0x30
            memWrite(0x90000030, r1)
        } else {
            r1 = r1 or 0x2
            memWrite(0x90000030, r1)
            r = memRead(0x9000002c)
            r = (r and 0xC0FFFFFF) or 0xA000000
            memWrite(0x9000002c, r)
        }

        r = memRead(0x70000010) or 0x8
        memWrite(0x70000010, r)
        r = memRead(0x70000018) or 0x10
        memWrite(0x70000018, r)
        r = memRead(0x9001014c) and 0xFFFFEFFF
        memWrite(0x9001014c, r)
        r = r or 0x1000
        memWrite(0x9001014c, r)
        Thread.sleep(50)
    }

    private fun powerMode(active: Boolean) {
        writeRead(Op.POWERMODE_SET, byteArrayOf(if (active) POWER_ACTIVE.toByte() else POWER_SLEEP.toByte()))
    }

    /** Cold init (mxl692_init): reset, regulators, xtal, firmware, power up. */
    fun init(firmware: ByteArray): Mxl692Version {
        seq = 1
        memWrite(0x80000100, 2) // legacy i2c override
        memRead(0x70000188) // sku, informational only (as in the kernel)
        Thread.sleep(50)
        configRegulatorsDual()
        configXtal()
        fwDownload(firmware)
        val v = readVersion()
        version = v
        powerMode(true)
        Thread.sleep(50)
        currentFreq = -1
        currentMod = null
        return v
    }

    fun sleep() = runCatching { powerMode(false) }

    // ---- tuning & status -------------------------------------------------

    private var currentFreq = -1L

    private var currentMod: Modulation? = null

    /**
     * mxl692_set_frontend() with the dvbsky MPEG settings, for 8VSB or Annex B QAM
     * (64/256 auto-detected by the firmware).
     */
    fun tune(freqHz: Long, mod: Modulation) {
        if (currentFreq == freqHz && currentMod == mod) return
        currentFreq = -1
        currentMod = null
        try {
            writeRead(Op.DEMOD_TYPE_SET, byteArrayOf((if (mod == Modulation.ATSC) DEMOD_ATSC else DEMOD_QAM).toByte()))
        } catch (e: Mxl692Exception) {
            if (e.message?.contains("status") == true) {
                throw UnsupportedModulationException(mod, "this tuner (MxL${version?.chipId}) does not support $mod")
            }
            throw e
        }
        Thread.sleep(20)
        val mpeg = byteArrayOf(
            1, // mpeg_parallel
            1, // msb_first (MSB_1ST; Annex B keeps MSB first)
            1, // sync pulse width: BYTE
            0, // valid polarity positive
            0, // sync polarity positive
            1, // clock polarity negative
            1, // 3-wire mode
            3, // 13.5 MHz
            3, 3, 3, 3, // pad drive 4X
        )
        writeRead(Op.MPEG_OUT_PARAMS_SET, mpeg)
        if (mod == Modulation.QAM) {
            // MXL_EAGLE_QAM_DEMOD_PARAMS_T (packed); the two u32 rates go big-endian.
            val qam = ByteArray(13)
            qam[0] = 0 // Annex B (J.83B)
            qam[1] = 7 // QAM type: auto
            qam[2] = 2 // IQ flip: auto
            qam[3] = 0 // search range index
            qam[4] = 0 // spur canceller off
            putBe32(qam, 5, 5_057_000) // symbol rate (64-QAM)
            putBe32(qam, 9, 5_361_000) // symbol rate for 256-QAM
            writeRead(Op.QAM_PARAMS_SET, qam)
        }
        Thread.sleep(20)
        val tune = ByteArray(6)
        putBe32(tune, 0, freqHz)
        tune[4] = 0 // tune mode VIEW
        tune[5] = 0 // 6 MHz
        writeRead(Op.TUNER_CHANNEL_TUNE_SET, tune)
        Thread.sleep(20)
        writeRead(if (mod == Modulation.ATSC) Op.ATSC_INIT_SET else Op.QAM_RESTART_SET)
        currentFreq = freqHz
        currentMod = mod
    }

    fun tuneAtsc(freqHz: Long) = tune(freqHz, Modulation.ATSC)

    private fun putBe32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }

    fun qamStatus(): QamStatus {
        val b = writeRead(Op.QAM_STATUS_GET, rxExpected = 16)
        return QamStatus(
            qamType = b[1].toInt() and 0xff,
            qamLocked = b[5].toInt() != 0,
            fecLocked = b[6].toInt() != 0,
            mpegLocked = b[7].toInt() != 0,
            snrDbTenths = ((b[8].toInt() and 0xff) shl 8) or (b[9].toInt() and 0xff),
            carrierOffsetHz = be32(b, 12).toInt(),
        )
    }

    fun qamErrors(): QamErrors {
        val b = writeRead(Op.QAM_ERROR_COUNTERS_GET, rxExpected = 28)
        return QamErrors(be32(b, 0), be32(b, 4), be32(b, 8))
    }

    fun atscStatus(): AtscStatus {
        val b = writeRead(Op.ATSC_STATUS_GET, rxExpected = 11)
        fun s16(o: Int) = ((b[o].toInt() shl 8) or (b[o + 1].toInt() and 0xff)).toShort().toInt()
        return AtscStatus(
            snrDbTenths = s16(0),
            timingOffset = s16(2),
            carrierOffsetHz = be32(b, 4).toInt(),
            frameLock = b[8].toInt() != 0,
            atscLock = b[9].toInt() != 0,
            fecLock = b[10].toInt() != 0,
        )
    }

    fun atscErrors(): AtscErrors {
        val b = writeRead(Op.ATSC_ERROR_COUNTERS_GET, rxExpected = 12)
        return AtscErrors(be32(b, 0), be32(b, 4), be32(b, 8))
    }

    fun agcStatus(): AgcStatus {
        val b = writeRead(Op.TUNER_AGC_STATUS_GET, rxExpected = 5)
        return AgcStatus(
            locked = b[0].toInt() != 0,
            rawAgcGain = ((b[1].toInt() and 0xff) shl 8) or (b[2].toInt() and 0xff),
            rxPowerDbHundredths = ((b[3].toInt() shl 8) or (b[4].toInt() and 0xff)).toShort().toInt(),
        )
    }
}

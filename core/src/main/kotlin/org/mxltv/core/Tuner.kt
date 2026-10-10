// SPDX-License-Identifier: GPL-2.0-or-later
package org.mxltv.core

import java.util.concurrent.CopyOnWriteArrayList

/** Bulk stream reader. The default uses blocking bulkIn; Android overrides with queued UsbRequests. */
interface StreamReader {
    /** Blocks, delivering stream data to [onData] until [running] returns false. */
    fun run(onData: (ByteArray, Int, Int) -> Unit, running: () -> Boolean)
}

class BlockingStreamReader(private val usb: UsbTransport, private val bufSize: Int = 65536) : StreamReader {
    override fun run(onData: (ByteArray, Int, Int) -> Unit, running: () -> Boolean) {
        val buf = ByteArray(bufSize)
        while (running()) {
            val n = try {
                usb.bulkIn(Dvbsky.EP_STREAM, buf, 500)
            } catch (e: java.io.IOException) {
                if (!running()) return
                0
            }
            if (n > 0) onData(buf, 0, n)
        }
    }
}

data class SignalInfo(
    val locked: Boolean,
    val snrDb: Double,
    val rxPowerDbm: Double?,
    val errorPackets: Long,
    val totalPackets: Long,
    val modulation: Modulation = Modulation.ATSC,
    val qamOrder: Int = 0,
) {
    /** Rough 0..100 quality from SNR (8VSB needs ~15 dB, 256-QAM ~28 dB, 64-QAM ~22 dB). */
    val quality: Int get() {
        if (!locked) return 0
        val (lo, hi) = when {
            modulation == Modulation.ATSC -> 14.0 to 30.0
            qamOrder == 64 -> 21.0 to 34.0
            else -> 27.0 to 40.0
        }
        return (((snrDb - lo) / (hi - lo)) * 100).toInt().coerceIn(0, 100)
    }
}

/**
 * One opened stick: USB bridge + MxL692. Owns the streaming thread and fans
 * TS packets out to listeners.
 */
class Tuner(
    private val usb: UsbTransport,
    private val reader: StreamReader = BlockingStreamReader(usb),
) {
    val bridge = Dvbsky(usb)
    val demod = Mxl692(bridge)

    private val packetListeners = CopyOnWriteArrayList<(ByteArray, Int) -> Unit>()
    @Volatile private var streaming = false
    private var streamThread: Thread? = null
    @Volatile var currentFreqHz = 0L; private set
    @Volatile var bytesReceived = 0L; private set

    fun init(firmware: ByteArray): Mxl692Version {
        bridge.resetMxl692()
        return demod.init(firmware)
    }

    @Volatile var currentModulation = Modulation.ATSC; private set

    private fun locked(mod: Modulation) = when (mod) {
        Modulation.ATSC -> demod.atscStatus().atscLock
        Modulation.QAM -> demod.qamStatus().let { it.qamLocked && it.fecLocked }
    }

    /** Tunes and waits up to [timeoutMs] for lock. */
    fun tune(freqHz: Long, mod: Modulation = Modulation.ATSC, timeoutMs: Long = 2500): Boolean {
        demod.tune(freqHz, mod)
        currentFreqHz = freqHz
        currentModulation = mod
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            if (runCatching { locked(mod) }.getOrDefault(false)) return true
        }
        return false
    }

    fun signal(): SignalInfo {
        val agc = runCatching { demod.agcStatus() }.getOrNull()
        return when (currentModulation) {
            Modulation.ATSC -> {
                val st = demod.atscStatus()
                val err = if (st.atscLock) runCatching { demod.atscErrors() }.getOrNull() else null
                SignalInfo(
                    locked = st.atscLock,
                    snrDb = st.snrDbTenths / 10.0,
                    rxPowerDbm = agc?.rxPowerDbHundredths?.div(100.0),
                    errorPackets = err?.errorPackets ?: 0,
                    totalPackets = err?.totalPackets ?: 0,
                    modulation = Modulation.ATSC,
                )
            }
            Modulation.QAM -> {
                val st = demod.qamStatus()
                val lock = st.qamLocked && st.fecLocked
                val err = if (lock) runCatching { demod.qamErrors() }.getOrNull() else null
                SignalInfo(
                    locked = lock,
                    snrDb = st.snrDbTenths / 10.0,
                    rxPowerDbm = agc?.rxPowerDbHundredths?.div(100.0),
                    errorPackets = err?.uncorrected ?: 0,
                    totalPackets = err?.totalCodewords ?: 0,
                    modulation = Modulation.QAM,
                    qamOrder = when (st.qamType) { 1 -> 64; 2 -> 256; 5 -> 128; else -> 0 },
                )
            }
        }
    }

    fun addPacketListener(l: (ByteArray, Int) -> Unit) = packetListeners.add(l)
    fun removePacketListener(l: (ByteArray, Int) -> Unit) = packetListeners.remove(l)

    @Synchronized
    fun startStreaming() {
        if (streaming) return
        streaming = true
        bridge.streamCtrl(true)
        val sync = TsSync { p, o -> for (l in packetListeners) l(p, o) }
        streamThread = Thread({
            reader.run({ b, o, n ->
                bytesReceived += n
                sync.feed(b, o, n)
            }, { streaming })
        }, "mxl692-stream").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @Synchronized
    fun stopStreaming() {
        if (!streaming) return
        streaming = false
        streamThread?.join(2000)
        streamThread = null
        runCatching { bridge.streamCtrl(false) }
    }

    fun close() {
        stopStreaming()
        demod.sleep()
        usb.close()
    }
}

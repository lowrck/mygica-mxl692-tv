// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * Desktop harness: runs the same driver/PSIP code as the Android app
 * against a stick attached to a Linux PC (via libusb), for testing.
 */
package org.mxltv.desktop

import org.mxltv.core.*
import org.usb4java.*
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.text.SimpleDateFormat
import java.util.Date

class LibUsbTransport : UsbTransport {
    private val ctx = Context()
    private val handle: DeviceHandle

    init {
        check(LibUsb.init(ctx) == LibUsb.SUCCESS) { "libusb init failed" }
        val list = DeviceList()
        LibUsb.getDeviceList(ctx, list)
        var found: Device? = null
        for (d in list) {
            val desc = DeviceDescriptor()
            LibUsb.getDeviceDescriptor(d, desc)
            if (UsbIds.isSupported(desc.idVendor().toInt() and 0xffff, desc.idProduct().toInt() and 0xffff)) {
                found = d; break
            }
        }
        val dev = found ?: throw IOException("no supported stick found")
        handle = DeviceHandle()
        val r = LibUsb.open(dev, handle)
        LibUsb.freeDeviceList(list, true)
        if (r != LibUsb.SUCCESS) throw IOException("open failed: ${LibUsb.strError(r)} (need rw access to the USB node)")
        LibUsb.setAutoDetachKernelDriver(handle, true)
        val c = LibUsb.claimInterface(handle, 0)
        if (c != LibUsb.SUCCESS) throw IOException("claim failed: ${LibUsb.strError(c)}")
    }

    override fun bulkOut(endpoint: Int, data: ByteArray, timeoutMs: Int) {
        val bb = ByteBuffer.allocateDirect(data.size).put(data)
        val n = IntBuffer.allocate(1)
        val r = LibUsb.bulkTransfer(handle, endpoint.toByte(), bb, n, timeoutMs.toLong())
        if (r != LibUsb.SUCCESS || n.get(0) != data.size) throw IOException("bulkOut: ${LibUsb.strError(r)}")
    }

    override fun bulkIn(endpoint: Int, buf: ByteArray, timeoutMs: Int): Int {
        val bb = ByteBuffer.allocateDirect(buf.size)
        val n = IntBuffer.allocate(1)
        val r = LibUsb.bulkTransfer(handle, endpoint.toByte(), bb, n, timeoutMs.toLong())
        if (r != LibUsb.SUCCESS && r != LibUsb.ERROR_TIMEOUT) throw IOException("bulkIn: ${LibUsb.strError(r)}")
        val got = n.get(0)
        bb.get(buf, 0, got)
        return got
    }

    override fun close() {
        LibUsb.releaseInterface(handle, 0)
        LibUsb.attachKernelDriver(handle, 0)
        LibUsb.close(handle)
        LibUsb.exit(ctx)
    }
}

private val fmt = SimpleDateFormat("EEE HH:mm")

fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println(
            """usage: desktop <firmware.fw> scan|scan-cable [ch,ch,...]
                |       desktop <firmware.fw> record <rf> <program> <out.ts> <seconds>""".trimMargin()
        )
        return
    }
    val fw = File(args[0]).readBytes()
    Mxl692.validateFirmware(fw)?.let { error("firmware: $it") }
    val usb = LibUsbTransport()
    val tuner = Tuner(usb)
    try {
        val t0 = System.currentTimeMillis()
        val v = tuner.init(fw)
        println("MxL${v.chipId} firmware ${v.firmware} (init ${System.currentTimeMillis() - t0} ms)")
        when (args[1]) {
            "scan", "scan-cable" -> {
                val plan: ChannelPlan = if (args[1] == "scan-cable") UsCable else UsAtsc
                val rfs = args.getOrNull(2)?.split(",")?.map { it.toInt() } ?: plan.channels
                val epg = EpgStore()
                val chans = Scanner(tuner, epg).scan(rfs, plan = plan, listener = object : ScanListener {
                    override fun onScrambled(rf: Int, count: Int) = println("        ($count encrypted)")
                    override fun onLock(rf: Int, locked: Boolean, signal: SignalInfo?) {
                        println("ch %3d: %s".format(rf, if (locked) "LOCK snr=%.1f dB power=%s qam=%d".format(
                            signal?.snrDb ?: 0.0, signal?.rxPowerDbm?.let { "%.1f dBm".format(it) },
                            signal?.qamOrder ?: 0) else "-"))
                    }
                    override fun onChannels(rf: Int, found: List<Channel>) {
                        found.forEach { println("        ${it.number} ${it.name} (prog ${it.programNumber}, src ${it.sourceId})") }
                    }
                })
                println("\n${chans.size} channels. Guide:")
                for (c in chans) {
                    val ev = epg.eventsFor(c.epgKey)
                    println("${c.number} ${c.name}: ${ev.size} events")
                    ev.take(3).forEach {
                        println("    ${fmt.format(Date(it.startUtcMs))} ${it.durationSec / 60}m ${it.title}" +
                            (it.description?.let { d -> " — ${d.take(70)}" } ?: ""))
                    }
                }
            }
            "record" -> {
                val rf = args[2].toInt(); val prog = args[3].toInt()
                val out = File(args[4]).outputStream().buffered()
                val secs = args[5].toLong()
                check(tuner.tune(UsAtsc.freqHz(rf))) { "no lock on RF $rf" }
                println("locked: ${tuner.signal()}")
                val psip = PsipCollector()
                var written = 0L
                val filter = ProgramFilter(prog, psip) { b, o, n -> out.write(b, o, n); written += n }
                tuner.addPacketListener { p, o -> psip.onPacket(p, o); filter.onPacket(p, o) }
                tuner.startStreaming()
                repeat(secs.toInt()) {
                    Thread.sleep(1000)
                    println("  ${tuner.bytesReceived * 8 / 1000 / (it + 1)} kbit/s in, $written bytes out, ${tuner.signal()}")
                }
                tuner.stopStreaming()
                out.close()
                println("PMT: ${psip.pmts[prog]}")
            }
        }
    } finally {
        tuner.close()
    }
}

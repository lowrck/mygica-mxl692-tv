// SPDX-License-Identifier: GPL-2.0-or-later
package org.mxltv.app

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import org.mxltv.core.Dvbsky
import org.mxltv.core.StreamReader
import org.mxltv.core.UsbTransport
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

class AndroidUsbTransport(manager: UsbManager, device: UsbDevice) : UsbTransport {
    val connection: UsbDeviceConnection = manager.openDevice(device) ?: throw IOException("openDevice failed")
    private val iface: UsbInterface = device.getInterface(0)
    private val endpoints = HashMap<Int, UsbEndpoint>()

    init {
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            throw IOException("claimInterface failed")
        }
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            endpoints[ep.address] = ep
        }
    }

    fun endpoint(address: Int) = endpoints[address] ?: throw IOException("endpoint 0x%02x missing".format(address))

    override fun bulkOut(endpoint: Int, data: ByteArray, timeoutMs: Int) {
        val n = connection.bulkTransfer(endpoint(endpoint), data, data.size, timeoutMs)
        if (n != data.size) throw IOException("bulk out 0x%02x failed (%d)".format(endpoint, n))
    }

    override fun bulkIn(endpoint: Int, buf: ByteArray, timeoutMs: Int): Int {
        val n = connection.bulkTransfer(endpoint(endpoint), buf, buf.size, timeoutMs)
        if (n < 0) throw IOException("bulk in 0x%02x failed".format(endpoint))
        return n
    }

    override fun close() {
        runCatching { connection.releaseInterface(iface) }
        connection.close()
    }
}

/** Keeps several bulk IN requests in flight so the stick's FIFO never overflows. */
class QueuedStreamReader(
    private val usb: AndroidUsbTransport,
    private val count: Int = 16,
    private val size: Int = 16384,
) : StreamReader {
    override fun run(onData: (ByteArray, Int, Int) -> Unit, running: () -> Boolean) {
        val conn = usb.connection
        val ep = usb.endpoint(Dvbsky.EP_STREAM)
        require(ep.direction == UsbConstants.USB_DIR_IN)
        val requests = List(count) {
            UsbRequest().apply {
                initialize(conn, ep)
                clientData = ByteBuffer.allocateDirect(size)
            }
        }
        val scratch = ByteArray(size)
        try {
            for (r in requests) r.queue(r.clientData as ByteBuffer)
            while (running()) {
                val r = try {
                    conn.requestWait(500)
                } catch (_: TimeoutException) {
                    continue
                } ?: break
                val buf = r.clientData as ByteBuffer
                val n = buf.position()
                if (n > 0) {
                    buf.flip()
                    buf.get(scratch, 0, n)
                    onData(scratch, 0, n)
                }
                buf.clear()
                if (running() && !r.queue(buf)) break
            }
        } finally {
            for (r in requests) runCatching { r.cancel() }
            // Reap cancelled requests before closing them.
            repeat(count) { runCatching { conn.requestWait(50) } }
            for (r in requests) runCatching { r.close() }
        }
    }
}

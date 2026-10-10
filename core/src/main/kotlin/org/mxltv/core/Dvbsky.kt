// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * Userspace port of the DVBSky USB bridge protocol used by the
 * MyGica A681B / PT682C / 1f4d:692a ATSC sticks.
 *
 * Based on drivers/media/usb/dvb-usb-v2/dvbsky.c from Linux
 * Copyright (C) 2013 Max nibble <nibble.max@gmail.com>
 */
package org.mxltv.core

import java.io.IOException

/** Raw bulk access to the stick; implemented by Android USB host and by libusb for desktop tests. */
interface UsbTransport {
    /** Bulk OUT to [endpoint]; must write all bytes or throw. */
    fun bulkOut(endpoint: Int, data: ByteArray, timeoutMs: Int)

    /** Bulk IN from [endpoint] into [buf]; returns bytes read or throws. */
    fun bulkIn(endpoint: Int, buf: ByteArray, timeoutMs: Int): Int

    fun close()
}

object UsbIds {
    const val VID_GTEK = 0x1f4d
    const val PID_A681B = 0x692f
    const val PID_PT682C = 0xe691
    const val PID_692A = 0x692a
    val SUPPORTED = setOf(PID_A681B, PID_PT682C, PID_692A)

    fun isSupported(vid: Int, pid: Int) = vid == VID_GTEK && pid in SUPPORTED
}

/** I2C bus behind the USB bridge. Addresses are 7-bit. */
interface I2cBus {
    fun write(addr: Int, data: ByteArray)
    fun read(addr: Int, len: Int): ByteArray
}

class Dvbsky(private val usb: UsbTransport) : I2cBus {
    companion object {
        const val EP_CTRL_OUT = 0x01
        const val EP_CTRL_IN = 0x81
        const val EP_STREAM = 0x82
        private const val MAX_I2C = 60
    }

    private val lock = Any()

    private fun rw(wbuf: ByteArray, rlen: Int): ByteArray = synchronized(lock) {
        usb.bulkOut(EP_CTRL_OUT, wbuf, 2000)
        if (rlen == 0) return ByteArray(0)
        val buf = ByteArray(64)
        val n = usb.bulkIn(EP_CTRL_IN, buf, 2000)
        if (n < rlen) throw IOException("short control read: $n < $rlen")
        buf.copyOf(rlen)
    }

    fun gpio(port: Int, value: Int) {
        rw(byteArrayOf(0x0e, port.toByte(), value.toByte()), 1)
    }

    /** Same reset the kernel does in dvbsky_identify_state() for MxL692 sticks. */
    fun resetMxl692() {
        gpio(0x80, 0)
        Thread.sleep(100)
        gpio(0x80, 1)
        Thread.sleep(100)
    }

    override fun write(addr: Int, data: ByteArray) {
        require(data.size <= MAX_I2C) { "i2c write too long: ${data.size}" }
        val obuf = ByteArray(3 + data.size)
        obuf[0] = 0x08
        obuf[1] = addr.toByte()
        obuf[2] = data.size.toByte()
        data.copyInto(obuf, 3)
        rw(obuf, 1)
    }

    override fun read(addr: Int, len: Int): ByteArray {
        require(len <= MAX_I2C) { "i2c read too long: $len" }
        val ibuf = rw(byteArrayOf(0x09, 0, len.toByte(), addr.toByte()), len + 1)
        return ibuf.copyOfRange(1, len + 1)
    }

    fun streamCtrl(on: Boolean) = synchronized(lock) {
        usb.bulkOut(EP_CTRL_OUT, byteArrayOf(0x37, 0, 0), 2000)
        if (on) {
            Thread.sleep(20)
            usb.bulkOut(EP_CTRL_OUT, byteArrayOf(0x36, 3, 0), 2000)
        }
    }
}

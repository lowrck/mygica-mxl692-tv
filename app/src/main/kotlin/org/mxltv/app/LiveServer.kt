// SPDX-License-Identifier: GPL-2.0-or-later
package org.mxltv.app

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Loopback HTTP server handing the filtered transport stream to libVLC.
 * Each playback session gets its own URL; starting a new session drops the old client.
 */
class LiveServer {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port get() = server.localPort

    private val queue = ArrayBlockingQueue<ByteArray>(512)
    @Volatile private var session = 0
    @Volatile private var client: Socket? = null
    @Volatile var dropped = 0L; private set
    @Volatile var sent = 0L; private set

    private val pending = ByteArray(16 * 188 * 7)
    private var pendingLen = 0
    private var lastFlush = 0L

    init {
        Thread({ acceptLoop() }, "live-server").apply { isDaemon = true; start() }
    }

    /** Starts a new session and returns its URL. */
    @Synchronized
    fun newSession(): String {
        session++
        synchronized(pending) { pendingLen = 0 }
        queue.clear()
        runCatching { client?.close() }
        return "http://127.0.0.1:$port/live/$session.ts"
    }

    @Synchronized
    fun endSession() {
        session++
        queue.clear()
        runCatching { client?.close() }
    }

    /** Called from the stream thread with whole TS packets. */
    fun write(b: ByteArray, off: Int, len: Int) {
        synchronized(pending) {
            if (pendingLen + len > pending.size) flushLocked()
            System.arraycopy(b, off, pending, pendingLen, len)
            pendingLen += len
            val now = System.nanoTime()
            if (pendingLen > pending.size - 188 || now - lastFlush > 40_000_000) flushLocked()
        }
    }

    private fun flushLocked() {
        if (pendingLen == 0) return
        val chunk = pending.copyOf(pendingLen)
        pendingLen = 0
        lastFlush = System.nanoTime()
        if (!queue.offer(chunk)) {
            queue.poll() // player is behind; drop the oldest data
            dropped++
            queue.offer(chunk)
        }
    }

    private fun acceptLoop() {
        while (true) {
            val s = runCatching { server.accept() }.getOrNull() ?: return
            Thread({ serve(s) }, "live-client").apply { isDaemon = true; start() }
        }
    }

    private fun serve(s: Socket) {
        val mySession: Int
        synchronized(this) {
            runCatching { client?.close() }
            client = s
            mySession = session
        }
        try {
            s.tcpNoDelay = true
            val input = s.getInputStream()
            // Read the request headers; we serve the same stream for any path.
            var state = 0
            while (state < 4) {
                val c = input.read()
                if (c < 0) return
                state = when {
                    c == '\r'.code && (state == 0 || state == 2) -> state + 1
                    c == '\n'.code && (state == 1 || state == 3) -> state + 1
                    else -> 0
                }
            }
            val out: OutputStream = s.getOutputStream()
            out.write(
                ("HTTP/1.0 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
            )
            while (session == mySession && !s.isClosed) {
                val chunk = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                out.write(chunk)
                sent += chunk.size
            }
        } catch (_: Exception) {
        } finally {
            runCatching { s.close() }
        }
    }
}

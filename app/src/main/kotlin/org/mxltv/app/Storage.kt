// SPDX-License-Identifier: GPL-2.0-or-later
package org.mxltv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.mxltv.core.Channel
import org.mxltv.core.EpgEvent
import org.mxltv.core.Modulation
import org.mxltv.core.Mxl692
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

object Firmware {
    private val MAGIC = byteArrayOf(0x4D, 0x31, 0x10, 0x02, 0x40, 0x00, 0x00, 0x80.toByte())

    fun file(ctx: Context) = File(ctx.filesDir, Mxl692.FIRMWARE_NAME)

    fun load(ctx: Context): ByteArray? = file(ctx).takeIf { it.isFile }?.readBytes()
        ?.takeIf { Mxl692.validateFirmware(it) == null }

    /**
     * Accepts dvb-demod-mxl692.fw, the Windows driver's cyDtv.Sys, or the
     * driver .zip. Driver files are searched for an image whose header,
     * length and checksum validate.
     */
    fun extract(data: ByteArray): ByteArray? {
        if (Mxl692.validateFirmware(data) == null) return data
        if (data.size > 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()) {
            ZipInputStream(ByteArrayInputStream(data)).use { zip ->
                val candidates = ArrayList<Pair<String, ByteArray>>()
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name.lowercase()
                    if (!e.isDirectory && (name.endsWith(".sys") || name.endsWith(".fw"))) {
                        candidates += name to zip.readBytes()
                    }
                }
                candidates.sortByDescending { (n, _) -> n.contains("x64") && n.endsWith("cydtv.sys") }
                for ((_, bytes) in candidates) search(bytes)?.let { return it }
            }
            return null
        }
        return search(data)
    }

    private fun search(b: ByteArray): ByteArray? {
        var i = 0
        while (i + 16 <= b.size) {
            if (b[i] == MAGIC[0] && (0 until 8).all { b[i + it] == MAGIC[it] }) {
                val len = (((b[i + 8].toLong() and 0xff) shl 24) or ((b[i + 9].toLong() and 0xff) shl 16) or
                    ((b[i + 10].toLong() and 0xff) shl 8) or (b[i + 11].toLong() and 0xff)) ushr 8
                val end = i + 16 + len
                if (len in 1..76_000 && end <= b.size) {
                    val img = b.copyOfRange(i, end.toInt())
                    if (Mxl692.validateFirmware(img) == null) return img
                }
            }
            i++
        }
        return null
    }

    fun save(ctx: Context, fw: ByteArray) {
        val tmp = File(ctx.filesDir, "fw.tmp")
        tmp.writeBytes(fw)
        tmp.renameTo(file(ctx))
    }
}

object Store {
    private fun channelsFile(ctx: Context) = File(ctx.filesDir, "channels.json")
    private fun epgFile(ctx: Context) = File(ctx.filesDir, "epg.json")

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }

    fun saveChannels(ctx: Context, list: List<Channel>) {
        val a = JSONArray()
        for (c in list) a.put(JSONObject().apply {
            put("freq", c.freqHz); put("rf", c.rf); put("major", c.major); put("minor", c.minor)
            put("name", c.name); put("prog", c.programNumber); put("src", c.sourceId); put("tsid", c.tsid)
            put("audio", c.audioOnly); put("mod", c.modulation.name)
        })
        writeAtomic(channelsFile(ctx), a.toString())
    }

    fun loadChannels(ctx: Context): List<Channel> = runCatching {
        val a = JSONArray(channelsFile(ctx).readText())
        List(a.length()) {
            val o = a.getJSONObject(it)
            Channel(
                o.getLong("freq"), o.getInt("rf"), o.getInt("major"), o.getInt("minor"), o.getString("name"),
                o.getInt("prog"), o.getInt("src"), o.getInt("tsid"), o.optBoolean("audio"),
                runCatching { Modulation.valueOf(o.optString("mod", "ATSC")) }.getOrDefault(Modulation.ATSC),
            )
        }
    }.getOrDefault(emptyList())

    fun saveEpg(ctx: Context, data: Map<String, List<EpgEvent>>) {
        val root = JSONObject()
        for ((k, list) in data) {
            val a = JSONArray()
            for (e in list) a.put(JSONObject().apply {
                put("s", e.sourceId); put("e", e.eventId); put("t", e.startUtcMs); put("d", e.durationSec)
                put("n", e.title); e.description?.let { put("x", it) }
            })
            root.put(k, a)
        }
        writeAtomic(epgFile(ctx), root.toString())
    }

    fun loadEpg(ctx: Context): Map<String, List<EpgEvent>> = runCatching {
        val root = JSONObject(epgFile(ctx).readText())
        root.keys().asSequence().associateWith { k ->
            val a = root.getJSONArray(k)
            List(a.length()) {
                val o = a.getJSONObject(it)
                EpgEvent(o.getInt("s"), o.getInt("e"), o.getLong("t"), o.getInt("d"), o.getString("n"),
                    if (o.has("x")) o.getString("x") else null)
            }
        }
    }.getOrDefault(emptyMap())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("mxltv", Context.MODE_PRIVATE)
    fun lastChannel(ctx: Context): String? = prefs(ctx).getString("last", null)
    fun setLastChannel(ctx: Context, key: String) = prefs(ctx).edit().putString("last", key).apply()
}

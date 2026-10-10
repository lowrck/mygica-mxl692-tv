// SPDX-License-Identifier: GPL-2.0-or-later
package org.mxltv.core

data class Channel(
    val freqHz: Long,
    val rf: Int,
    val major: Int,
    val minor: Int,
    val name: String,
    val programNumber: Int,
    val sourceId: Int,
    val tsid: Int,
    val audioOnly: Boolean = false,
    val modulation: Modulation = Modulation.ATSC,
) {
    val key get() = "$freqHz:$programNumber"
    val number get() = when {
        major > 0 -> "$major.$minor"
        modulation == Modulation.QAM -> "$rf-$programNumber"
        else -> "RF$rf-$programNumber"
    }
    val isCable get() = modulation == Modulation.QAM
    /** EPG events are keyed by RF frequency + source_id. */
    val epgKey get() = "$freqHz:$sourceId"
}

/** A list of physical channels to scan, with how to tune them. */
interface ChannelPlan {
    val name: String
    val modulation: Modulation
    val channels: List<Int>
    fun freqHz(ch: Int): Long
    fun channelForFreq(freqHz: Long) = channels.firstOrNull { freqHz(it) == freqHz } ?: 0
}

object UsAtsc : ChannelPlan {
    override val name = "Antenna (ATSC)"
    override val modulation = Modulation.ATSC
    override val channels get() = RF_CHANNELS

    /** Post-repack US broadcast channels 2-36, center frequencies. */
    val RF_CHANNELS = (2..36).toList()

    override fun freqHz(ch: Int): Long = 1_000_000L * when (ch) {
        in 2..4 -> 57 + (ch - 2) * 6
        in 5..6 -> 79 + (ch - 5) * 6
        in 7..13 -> 177 + (ch - 7) * 6
        in 14..69 -> 473 + (ch - 14) * 6
        else -> throw IllegalArgumentException("RF $ch")
    }

    fun rfForFreq(freqHz: Long) = channelForFreq(freqHz)
}

/**
 * US cable, EIA/CEA-542 "STD" plan (center frequencies), clear QAM (Annex B).
 * Channels above 134 (861+ MHz) are beyond the MxL692's 858 MHz limit.
 */
object UsCable : ChannelPlan {
    override val name = "Cable (QAM)"
    override val modulation = Modulation.QAM
    override val channels: List<Int> = (2..134).sortedBy { freqHz(it) }

    override fun freqHz(ch: Int): Long = 1_000_000L * when (ch) {
        in 2..4 -> 57 + (ch - 2) * 6
        in 5..6 -> 79 + (ch - 5) * 6
        in 7..13 -> 177 + (ch - 7) * 6
        in 14..22 -> 123 + (ch - 14) * 6
        in 23..94 -> 219 + (ch - 23) * 6
        in 95..99 -> 93 + (ch - 95) * 6
        in 100..158 -> 651 + (ch - 100) * 6
        else -> throw IllegalArgumentException("cable ch $ch")
    }
}

/** Program guide for all channels, keyed by [Channel.epgKey]. */
class EpgStore {
    private val map = HashMap<String, MutableMap<Int, EpgEvent>>()

    @Synchronized
    fun merge(freqHz: Long, events: Collection<EpgEvent>) {
        for (e in events) map.getOrPut("$freqHz:${e.sourceId}") { HashMap() }[e.eventId] = e.copy()
    }

    @Synchronized
    fun eventsFor(epgKey: String, nowMs: Long = System.currentTimeMillis()): List<EpgEvent> =
        map[epgKey]?.values?.filter { it.endUtcMs > nowMs }?.sortedBy { it.startUtcMs } ?: emptyList()

    fun nowNext(epgKey: String, nowMs: Long = System.currentTimeMillis()): Pair<EpgEvent?, EpgEvent?> {
        val ev = eventsFor(epgKey, nowMs)
        val now = ev.firstOrNull { it.startUtcMs <= nowMs }
        val next = ev.firstOrNull { it.startUtcMs > nowMs }
        return now to next
    }

    @Synchronized
    fun prune(nowMs: Long = System.currentTimeMillis()) {
        for (m in map.values) m.values.removeAll { it.endUtcMs < nowMs - 3600_000 }
        map.values.removeAll { it.isEmpty() }
    }

    @Synchronized
    fun all(): Map<String, List<EpgEvent>> = map.mapValues { it.value.values.toList() }

    @Synchronized
    fun replaceAll(data: Map<String, List<EpgEvent>>) {
        map.clear()
        for ((k, v) in data) map[k] = v.associateBy { it.eventId }.toMutableMap()
    }
}

interface ScanListener {
    fun onProgress(index: Int, total: Int, rf: Int, freqHz: Long) {}
    fun onLock(rf: Int, locked: Boolean, signal: SignalInfo?) {}
    fun onChannels(rf: Int, found: List<Channel>) {}
    fun onScrambled(rf: Int, count: Int) {}
    fun onError(rf: Int, e: Exception) {}
}

class Scanner(private val tuner: Tuner, private val epg: EpgStore) {
    /**
     * Streams the current multiplex into a fresh [PsipCollector] until the channel
     * tables are complete (bounded by [tablesMs]); then keeps collecting guide data
     * for up to [epgMs] or until the guide stops changing.
     */
    fun collect(tablesMs: Long, epgMs: Long, cancelled: () -> Boolean = { false }): PsipCollector {
        val psip = PsipCollector()
        val l: (ByteArray, Int) -> Unit = psip::onPacket
        tuner.addPacketListener(l)
        tuner.startStreaming()
        try {
            val t0 = System.currentTimeMillis()
            while (!cancelled() && System.currentTimeMillis() - t0 < tablesMs) {
                Thread.sleep(100)
                val pat = psip.pat
                if (pat.isNotEmpty() && psip.vctComplete && pat.keys.all { psip.pmts.containsKey(it) }) break
            }
            val t1 = System.currentTimeMillis()
            while (!cancelled() && System.currentTimeMillis() - t1 < epgMs) {
                Thread.sleep(200)
                val quiet = System.currentTimeMillis() - maxOf(psip.lastEpgChangeMs, t1) > 4000
                if (psip.eitSectionsSeen > 0 && quiet) break
            }
        } finally {
            tuner.stopStreaming()
            tuner.removePacketListener(l)
        }
        epg.merge(tuner.currentFreqHz, psip.events.values)
        return psip
    }

    /** True if any of the program's audio/video PIDs carried scrambled packets. */
    fun isScrambled(psip: PsipCollector, program: Int): Boolean {
        val pmt = psip.pmts[program] ?: return false
        return pmt.streams.any { it.pid in psip.scrambledPids }
    }

    fun channelsFrom(psip: PsipCollector, freqHz: Long, plan: ChannelPlan = UsAtsc): List<Channel> {
        val rf = plan.channelForFreq(freqHz)
        val pat = psip.pat
        val mod = plan.modulation
        val fromVct = psip.vct.values
            .filter { !it.hidden && it.serviceType in 2..3 && it.programNumber in pat }
            .filter { !isScrambled(psip, it.programNumber) }
            .sortedWith(compareBy({ it.major }, { it.minor }))
            .map {
                Channel(
                    freqHz, rf, it.major, it.minor, it.shortName.ifBlank { it.longName ?: "" },
                    it.programNumber, it.sourceId, psip.tsid, audioOnly = it.serviceType == 3, modulation = mod,
                )
            }
        if (fromVct.isNotEmpty()) return fromVct
        // No virtual channel table (common on cable): list the programs that carry audio/video.
        return pat.keys.sorted()
            .filter { prog -> psip.pmts[prog]?.let { it.hasVideo || it.hasAudio } ?: false }
            .filter { !isScrambled(psip, it) }
            .map { prog ->
                val audioOnly = psip.pmts[prog]?.hasVideo == false
                Channel(freqHz, rf, 0, 0, if (audioOnly) "Audio $prog" else "Program $prog", prog, -1,
                    psip.tsid, audioOnly = audioOnly, modulation = mod)
            }
    }

    fun scan(
        rfChannels: List<Int>,
        listener: ScanListener,
        epgMs: Long = 6000,
        cancelled: () -> Boolean = { false },
        plan: ChannelPlan = UsAtsc,
    ): List<Channel> {
        val result = ArrayList<Channel>()
        val lockMs = if (plan.modulation == Modulation.QAM) 3000L else 2500L
        for ((i, rf) in rfChannels.withIndex()) {
            if (cancelled()) break
            val f = plan.freqHz(rf)
            listener.onProgress(i, rfChannels.size, rf, f)
            val locked = try {
                tuner.tune(f, plan.modulation, lockMs)
            } catch (e: UnsupportedModulationException) {
                listener.onError(rf, e)
                break
            } catch (e: Exception) {
                listener.onError(rf, e)
                false
            }
            listener.onLock(rf, locked, if (locked) runCatching { tuner.signal() }.getOrNull() else null)
            if (!locked) continue
            val psip = collect(tablesMs = 5000, epgMs = epgMs, cancelled = cancelled)
            val found = channelsFrom(psip, f, plan)
            val scrambled = psip.pat.keys.count { isScrambled(psip, it) }
            if (scrambled > 0) listener.onScrambled(rf, scrambled)
            listener.onChannels(rf, found)
            result += found
        }
        listener.onProgress(rfChannels.size, rfChannels.size, 0, 0)
        return result
    }

    /** Refreshes the guide by visiting each multiplex that has channels. */
    fun refreshGuide(
        channels: List<Channel>,
        dwellMs: Long = 20000,
        onMux: (Int, Int, Long) -> Unit = { _, _, _ -> },
        cancelled: () -> Boolean = { false },
    ) {
        val muxes = channels.map { it.freqHz to it.modulation }.distinct()
        for ((i, m) in muxes.withIndex()) {
            val (f, mod) = m
            if (cancelled()) break
            onMux(i, muxes.size, f)
            if (runCatching { tuner.tune(f, mod) }.getOrDefault(false)) {
                collect(tablesMs = 3000, epgMs = dwellMs, cancelled = cancelled)
            }
        }
    }
}

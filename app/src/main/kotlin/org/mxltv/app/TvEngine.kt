// SPDX-License-Identifier: GPL-2.0-or-later
package org.mxltv.app

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mxltv.core.Channel
import org.mxltv.core.EpgStore
import org.mxltv.core.Mxl692Version
import org.mxltv.core.ProgramFilter
import org.mxltv.core.PsipCollector
import org.mxltv.core.ScanListener
import org.mxltv.core.Scanner
import org.mxltv.core.SignalInfo
import org.mxltv.core.Tuner
import org.mxltv.core.UsbIds
import java.util.concurrent.Executors

sealed class EngineState {
    data object NoDevice : EngineState()
    data object NeedPermission : EngineState()
    data object NoFirmware : EngineState()
    data object Initializing : EngineState()
    data class Ready(val version: Mxl692Version) : EngineState()
    data class Error(val message: String) : EngineState()
}

sealed class PlayState {
    data object Idle : PlayState()
    data class Tuning(val channel: Channel) : PlayState()
    data class Playing(val channel: Channel, val url: String) : PlayState()
    data class NoSignal(val channel: Channel) : PlayState()
    data class Failed(val channel: Channel, val message: String) : PlayState()
}

@SuppressLint("StaticFieldLeak")
object TvEngine {
    private const val TAG = "MxlTv"
    private const val ACTION_PERMISSION = "org.mxltv.app.USB_PERMISSION"

    private lateinit var app: Application
    private lateinit var usbManager: UsbManager

    /** All tuner I/O happens on this single thread. */
    private val tunerDispatcher = Executors.newSingleThreadExecutor { Thread(it, "tuner") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + tunerDispatcher)

    private val _state = MutableStateFlow<EngineState>(EngineState.NoDevice)
    val state: StateFlow<EngineState> = _state

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels

    val epg = EpgStore()
    private val _epgVersion = MutableStateFlow(0)
    val epgVersion: StateFlow<Int> = _epgVersion

    private val _play = MutableStateFlow<PlayState>(PlayState.Idle)
    val play: StateFlow<PlayState> = _play

    private val _signal = MutableStateFlow<SignalInfo?>(null)
    val signal: StateFlow<SignalInfo?> = _signal

    private var tuner: Tuner? = null
    private var device: UsbDevice? = null
    private val server by lazy { LiveServer() }

    private var livePsip: PsipCollector? = null
    private var livePsipListener: ((ByteArray, Int) -> Unit)? = null
    private var filterListener: ((ByteArray, Int) -> Unit)? = null
    private var monitorJob: Job? = null
    private var stats: org.mxltv.core.TsStats? = null
    private var statsListener: ((ByteArray, Int) -> Unit)? = null
    private var busyJob: Job? = null

    fun init(application: Application) {
        app = application
        usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
        _channels.value = Store.loadChannels(app)
        epg.replaceAll(Store.loadEpg(app))
        epg.prune()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val d = usbDevice(i)
                        if (d != null && d.deviceName == device?.deviceName) onDetached()
                    }
                    else -> refresh()
                }
            }
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    @Suppress("DEPRECATION")
    private fun usbDevice(i: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun findDevice(): UsbDevice? = usbManager.deviceList.values.firstOrNull {
        UsbIds.isSupported(it.vendorId, it.productId)
    }

    /** Re-evaluates device / permission / firmware and opens the tuner if possible. */
    fun refresh() {
        if (tuner != null && _state.value is EngineState.Ready) return
        if (_state.value is EngineState.Initializing) return
        val d = findDevice()
        if (d == null) {
            _state.value = EngineState.NoDevice
            return
        }
        if (!usbManager.hasPermission(d)) {
            _state.value = EngineState.NeedPermission
            val pi = PendingIntent.getBroadcast(
                app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(d, pi)
            return
        }
        val fw = Firmware.load(app)
        if (fw == null) {
            _state.value = EngineState.NoFirmware
            return
        }
        _state.value = EngineState.Initializing
        scope.launch {
            try {
                val usb = AndroidUsbTransport(usbManager, d)
                val t = Tuner(usb, QueuedStreamReader(usb))
                val v = try {
                    t.init(fw)
                } catch (e: Exception) {
                    runCatching { usb.close() }
                    throw e
                }
                tuner = t
                device = d
                Log.i(TAG, "MxL${v.chipId} firmware ${v.firmware}")
                _state.value = EngineState.Ready(v)
            } catch (e: Exception) {
                Log.e(TAG, "init failed", e)
                _state.value = EngineState.Error(e.message ?: e.toString())
            }
        }
    }

    private fun onDetached() {
        scope.launch {
            stopLiveLocked()
            runCatching { tuner?.close() }
            tuner = null
            device = null
            _play.value = PlayState.Idle
            _state.value = EngineState.NoDevice
        }
    }

    fun importFirmware(bytes: ByteArray): Boolean {
        val fw = Firmware.extract(bytes) ?: return false
        Firmware.save(app, fw)
        if (_state.value is EngineState.NoFirmware || _state.value is EngineState.Error) {
            _state.value = EngineState.NoDevice
            refresh()
        }
        return true
    }

    fun retry() {
        if (_state.value is EngineState.Error) _state.value = EngineState.NoDevice
        refresh()
    }

    // ---- scanning / guide refresh ---------------------------------------

    fun startScan(
        plan: org.mxltv.core.ChannelPlan,
        collectGuide: Boolean,
        listener: ScanListener,
        onDone: (List<Channel>) -> Unit,
    ) {
        val t = tuner ?: return
        busyJob?.cancel()
        busyJob = scope.launch {
            stopLiveLocked()
            _play.value = PlayState.Idle
            val job = coroutineContext[Job]!!
            val found = Scanner(t, epg).scan(
                plan.channels, listener,
                epgMs = if (collectGuide) 8000 else 0,
                cancelled = { !job.isActive },
                plan = plan,
            )
            if (found.isNotEmpty()) {
                // Keep channels from the other source (antenna vs cable).
                val kept = _channels.value.filter { it.modulation != plan.modulation }
                val all = (kept + found).sortedWith(
                    compareBy<Channel>({ it.isCable }, { if (it.major > 0) it.major else 10_000 + it.rf },
                        { if (it.major > 0) it.minor else it.programNumber })
                )
                _channels.value = all
                Store.saveChannels(app, all)
            }
            saveEpg()
            withContext(kotlinx.coroutines.Dispatchers.Main) { onDone(found) }
        }
    }

    fun refreshGuide(onMux: (Int, Int) -> Unit, onDone: () -> Unit) {
        val t = tuner ?: return
        busyJob?.cancel()
        busyJob = scope.launch {
            stopLiveLocked()
            _play.value = PlayState.Idle
            val job = coroutineContext[Job]!!
            Scanner(t, epg).refreshGuide(_channels.value, onMux = { i, n, _ ->
                onMux(i, n)
                _epgVersion.value++
            }, cancelled = { !job.isActive })
            saveEpg()
            withContext(kotlinx.coroutines.Dispatchers.Main) { onDone() }
        }
    }

    fun cancelBusy() {
        busyJob?.cancel()
    }

    private fun saveEpg() {
        epg.prune()
        Store.saveEpg(app, epg.all())
        _epgVersion.value++
    }

    // ---- live playback ----------------------------------------------------

    fun play(channel: Channel) {
        val t = tuner ?: return
        Store.setLastChannel(app, channel.key)
        _play.value = PlayState.Tuning(channel)
        busyJob?.cancel()
        busyJob = scope.launch {
            try {
                val sameMux = livePsip != null && t.currentFreqHz == channel.freqHz &&
                    t.currentModulation == channel.modulation
                if (!sameMux) {
                    stopLiveLocked()
                    if (!t.tune(channel.freqHz, channel.modulation, if (channel.isCable) 3000 else 2500)) {
                        _play.value = PlayState.NoSignal(channel)
                        startMonitor()
                        return@launch
                    }
                }
                val psip = livePsip ?: PsipCollector().also { p ->
                    livePsip = p
                    val l: (ByteArray, Int) -> Unit = p::onPacket
                    livePsipListener = l
                    t.addPacketListener(l)
                }
                if (statsListener == null) {
                    val st = org.mxltv.core.TsStats()
                    stats = st
                    val sl: (ByteArray, Int) -> Unit = st::onPacket
                    statsListener = sl
                    t.addPacketListener(sl)
                }
                filterListener?.let { t.removePacketListener(it) }
                val url = server.newSession()
                val filter = ProgramFilter(channel.programNumber, psip, server::write)
                val fl: (ByteArray, Int) -> Unit = filter::onPacket
                filterListener = fl
                t.addPacketListener(fl)
                t.startStreaming()
                _play.value = PlayState.Playing(channel, url)
                startMonitor()
            } catch (e: Exception) {
                Log.e(TAG, "play failed", e)
                _play.value = PlayState.Failed(channel, e.message ?: e.toString())
            }
        }
    }

    /** Polls signal; retries tuning while there is no signal; merges live guide data. */
    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var tick = 0
            while (isActive) {
                val t = tuner ?: break
                _signal.value = runCatching { t.signal() }.getOrNull()
                if (tick % 30 == 29) Log.i(TAG, "diag: ${t.bytesReceived * 8 / 1000 / (tick + 1)} kbit/s avg, " +
                    "mux ${stats}, server sent=${server.sent} dropped=${server.dropped}, signal=${_signal.value}")
                val ps = _play.value
                if (ps is PlayState.NoSignal && tick % 3 == 2) {
                    play(ps.channel)
                    break
                }
                if (tick % 15 == 14) livePsip?.let { p ->
                    epg.merge(t.currentFreqHz, p.events.values)
                    _epgVersion.value++
                }
                if (tick % 120 == 119) saveEpg()
                tick++
                delay(1000)
            }
        }
    }

    fun stop() {
        scope.launch {
            stopLiveLocked()
            _play.value = PlayState.Idle
        }
    }

    private fun stopLiveLocked() {
        monitorJob?.cancel()
        monitorJob = null
        val t = tuner ?: return
        t.stopStreaming()
        filterListener?.let { t.removePacketListener(it) }
        livePsipListener?.let { t.removePacketListener(it) }
        statsListener?.let { t.removePacketListener(it) }
        statsListener = null
        stats = null
        livePsip?.let { epg.merge(t.currentFreqHz, it.events.values) }
        filterListener = null
        livePsipListener = null
        livePsip = null
        server.endSession()
        _signal.value = null
    }

    fun neighbour(current: Channel, delta: Int): Channel? {
        val list = _channels.value
        if (list.isEmpty()) return null
        val i = list.indexOfFirst { it.key == current.key }
        return list[((if (i < 0) 0 else i) + delta).mod(list.size)]
    }

    fun channelByKey(key: String?) = _channels.value.firstOrNull { it.key == key }
}

// SPDX-License-Identifier: GPL-2.0-or-later
package org.mxltv.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mxltv.core.Channel
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_KEY = "channel"
        private const val OVERLAY_MS = 7000L

        /** Reuses an existing player (e.g. when picking a channel from the guide). */
        fun intent(ctx: android.content.Context, c: Channel) = Intent(ctx, PlayerActivity::class.java)
            .putExtra(EXTRA_KEY, c.key)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val c = TvEngine.channelByKey(intent.getStringExtra(EXTRA_KEY)) ?: return
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) tuneTo(c) else channel = c
    }

    private lateinit var libVlc: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var overlay: View
    private lateinit var center: TextView
    private lateinit var numberEntry: TextView
    private lateinit var chNumber: TextView
    private lateinit var chName: TextView
    private lateinit var signalText: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowProgress: ProgressBar
    private lateinit var nowDesc: TextView
    private lateinit var nextText: TextView
    private lateinit var btnUp: Button

    private var channel: Channel? = null
    private var currentUrl: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private var digits = ""
    private val commitDigits = Runnable { commitNumber() }
    private val restartPlayback = Runnable { currentUrl?.let { startMedia(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        overlay = findViewById(R.id.overlay)
        center = findViewById(R.id.center)
        numberEntry = findViewById(R.id.numberEntry)
        chNumber = findViewById(R.id.chNumber)
        chName = findViewById(R.id.chName)
        signalText = findViewById(R.id.signal)
        nowTitle = findViewById(R.id.nowTitle)
        nowProgress = findViewById(R.id.nowProgress)
        nowDesc = findViewById(R.id.nowDesc)
        nextText = findViewById(R.id.next)
        btnUp = findViewById(R.id.btnUp)

        libVlc = LibVLC(this, arrayListOf("--deinterlace=-1", "--deinterlace-mode=yadif", "--no-stats"))
        player = MediaPlayer(libVlc)
        player.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Playing -> center.visibility = View.GONE
                MediaPlayer.Event.Buffering -> if (ev.buffering < 100f && TvEngine.play.value is PlayState.Playing) {
                    showCenter("Buffering…")
                } else if (ev.buffering >= 100f) center.visibility = View.GONE
                MediaPlayer.Event.EncounteredError, MediaPlayer.Event.EndReached -> {
                    // The live stream never ends on its own; reconnect.
                    if (TvEngine.play.value is PlayState.Playing) {
                        handler.removeCallbacks(restartPlayback)
                        handler.postDelayed(restartPlayback, 1000)
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnUp).setOnClickListener { zap(+1) }
        findViewById<Button>(R.id.btnDown).setOnClickListener { zap(-1) }
        findViewById<Button>(R.id.btnAudio).setOnClickListener { cycleAudio() }
        findViewById<Button>(R.id.btnCc).setOnClickListener { cycleCc() }
        findViewById<Button>(R.id.btnGuide).setOnClickListener { openGuide() }
        setupTouch()

        channel = TvEngine.channelByKey(intent.getStringExtra(EXTRA_KEY))
            ?: TvEngine.channelByKey(Store.lastChannel(this))
            ?: TvEngine.channels.value.firstOrNull()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TvEngine.play.collect { onPlayState(it) } }
                launch { TvEngine.signal.collect { renderSignal() } }
                launch { TvEngine.epgVersion.collect { renderInfo() } }
                launch { while (true) { renderInfo(); delay(20_000) } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The video surface is destroyed while the activity is stopped, so libVLC must be
        // re-attached every time it starts (as in libVLC's own samples).
        player.attachViews(findViewById<VLCVideoLayout>(R.id.video), null, true, false)
        val c = channel
        if (c == null) {
            Toast.makeText(this, getString(R.string.no_channels), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        tuneTo(c)
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        player.stop()
        player.detachViews()
        currentUrl = null
        TvEngine.stop()
        super.onStop()
    }

    override fun onDestroy() {
        player.release()
        libVlc.release()
        super.onDestroy()
    }

    // ---- playback ---------------------------------------------------------

    private fun tuneTo(c: Channel) {
        channel = c
        renderInfo()
        showOverlay(focus = false)
        TvEngine.play(c)
    }

    private fun onPlayState(s: PlayState) {
        when (s) {
            is PlayState.Tuning -> {
                player.stop()
                currentUrl = null
                showCenter("Tuning ${s.channel.number}…")
            }
            is PlayState.Playing -> if (s.url != currentUrl) {
                currentUrl = s.url
                startMedia(s.url)
            }
            is PlayState.NoSignal -> {
                player.stop()
                currentUrl = null
                showCenter("No signal on ${s.channel.number} (${if (s.channel.isCable) "cable ch" else "RF"} ${s.channel.rf}). Retrying…")
            }
            is PlayState.Failed -> showCenter("Tuner error: ${s.message}")
            PlayState.Idle -> {}
        }
    }

    private fun startMedia(url: String) {
        val m = Media(libVlc, Uri.parse(url))
        m.setHWDecoderEnabled(true, false)
        m.addOption(":network-caching=1200")
        m.addOption(":demux=ts")
        player.media = m
        m.release()
        player.play()
        showCenter("Starting…")
    }

    private fun zap(delta: Int) {
        val c = channel ?: return
        TvEngine.neighbour(c, delta)?.let { tuneTo(it) }
    }

    private fun cycleAudio() {
        val tracks = player.audioTracks?.filter { it.id >= 0 } ?: emptyList()
        if (tracks.isEmpty()) return toast("No audio tracks")
        val i = tracks.indexOfFirst { it.id == player.audioTrack }
        val next = tracks[(i + 1).mod(tracks.size)]
        player.setAudioTrack(next.id)
        toast("Audio: ${next.name}")
    }

    private fun cycleCc() {
        val tracks = player.spuTracks?.toList() ?: emptyList() // includes "Disable" (id -1)
        if (tracks.size <= 1) return toast("No captions on this channel")
        val i = tracks.indexOfFirst { it.id == player.spuTrack }
        val next = tracks[(i + 1).mod(tracks.size)]
        player.setSpuTrack(next.id)
        toast("Captions: ${next.name}")
    }

    private fun openGuide() = startActivity(Intent(this, GuideActivity::class.java))

    // ---- UI ---------------------------------------------------------------

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun showCenter(s: String) {
        center.text = s
        center.visibility = View.VISIBLE
    }

    private fun showOverlay(focus: Boolean) {
        overlay.visibility = View.VISIBLE
        if (focus && !overlayHasFocus()) btnUp.requestFocus()
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, OVERLAY_MS)
    }

    private fun overlayHasFocus(): Boolean {
        var v: View? = currentFocus
        while (v != null) {
            if (v === overlay) return true
            v = v.parent as? View
        }
        return false
    }

    private fun renderInfo() {
        val c = channel ?: return
        chNumber.text = c.number
        chName.text = c.name
        val (now, next) = TvEngine.epg.nowNext(c.epgKey)
        if (now != null) {
            nowTitle.text = "${now.title}  ·  ${Fmt.range(this, now)}"
            nowProgress.visibility = View.VISIBLE
            nowProgress.progress = Fmt.progress(now)
            nowDesc.text = now.description ?: ""
            nowDesc.visibility = if (now.description.isNullOrBlank()) View.GONE else View.VISIBLE
        } else {
            nowTitle.text = "No guide information"
            nowProgress.visibility = View.GONE
            nowDesc.visibility = View.GONE
        }
        nextText.text = next?.let { "Next: ${Fmt.time(this, it.startUtcMs)}  ${it.title}" } ?: ""
        nextText.visibility = if (next == null) View.GONE else View.VISIBLE
    }

    private fun renderSignal() {
        val s = TvEngine.signal.value
        signalText.text = when {
            s == null -> ""
            !s.locked -> "No lock"
            else -> {
                val bars = (s.quality + 19) / 20
                val qam = if (s.qamOrder > 0) "  ${s.qamOrder}-QAM" else ""
                android.text.SpannableStringBuilder("▮▮▮▮▮  SNR %.1f dB$qam".format(s.snrDb)).apply {
                    setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.text_dim)), bars, 5, 0)
                }
            }
        }
        signalText.setTextColor(getColor(
            when {
                s == null || !s.locked -> R.color.bad
                s.quality < 35 -> R.color.warn
                else -> R.color.good
            }
        ))
    }

    // ---- input ------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && overlay.visibility == View.VISIBLE) {
            handler.removeCallbacks(hideOverlay)
            handler.postDelayed(hideOverlay, OVERLAY_MS)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val overlayShown = overlay.visibility == View.VISIBLE
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { zap(+1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { zap(-1); return true }
            KeyEvent.KEYCODE_GUIDE -> { openGuide(); return true }
            KeyEvent.KEYCODE_CAPTIONS -> { cycleCc(); return true }
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { cycleAudio(); return true }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> { showOverlay(focus = true); return true }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { addDigit('0' + (keyCode - KeyEvent.KEYCODE_0)); return true }
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                addDigit('0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)); return true
            }
            KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_NUMPAD_DOT, KeyEvent.KEYCODE_MINUS -> {
                if (digits.isNotEmpty()) addDigit('.'); return true
            }
        }
        if (!overlayShown) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { zap(+1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (digits.isNotEmpty()) commitNumber() else showOverlay(focus = true)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlay.visibility == View.VISIBLE && overlayHasFocus()) {
            overlay.visibility = View.GONE
            overlay.findFocus()?.clearFocus()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun addDigit(ch: Char) {
        if (digits.length >= 6) return
        digits += ch
        numberEntry.text = digits
        numberEntry.visibility = View.VISIBLE
        handler.removeCallbacks(commitDigits)
        handler.postDelayed(commitDigits, 2000)
    }

    private fun commitNumber() {
        handler.removeCallbacks(commitDigits)
        val d = digits.trimEnd('.')
        digits = ""
        numberEntry.visibility = View.GONE
        if (d.isEmpty()) return
        val list = TvEngine.channels.value
        val match = list.firstOrNull { it.number == d }
            ?: if ('.' !in d) list.firstOrNull { it.major.toString() == d } else null
        if (match != null) tuneTo(match) else toast("No channel $d")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (overlay.visibility == View.VISIBLE) overlay.visibility = View.GONE else showOverlay(focus = false)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vy) > abs(vx) && abs(vy) > 800) {
                    zap(if (vy < 0) +1 else -1)
                    return true
                }
                return false
            }
        })
        findViewById<View>(R.id.root).setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
    }
}

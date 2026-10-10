// SPDX-License-Identifier: GPL-2.0-or-later
package org.mxltv.app

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.mxltv.core.Channel
import org.mxltv.core.ChannelPlan
import org.mxltv.core.Modulation
import org.mxltv.core.UsAtsc
import org.mxltv.core.UsCable
import org.mxltv.core.UnsupportedModulationException
import org.mxltv.core.ScanListener
import org.mxltv.core.SignalInfo

class ScanActivity : AppCompatActivity() {
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var start: Button
    private lateinit var cancel: Button
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        fitSystemBars()
        log = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.progressText)
        start = findViewById(R.id.start)
        cancel = findViewById(R.id.cancel)
        val collect = findViewById<CheckBox>(R.id.collectGuide)
        val cable = findViewById<android.widget.RadioButton>(R.id.srcCable)
        val hint = findViewById<TextView>(R.id.sourceHint)
        findViewById<android.widget.RadioGroup>(R.id.source).setOnCheckedChangeListener { _, id ->
            hint.setText(if (id == R.id.srcCable) R.string.hint_cable else R.string.hint_antenna)
        }
        start.setOnClickListener { startScan(if (cable.isChecked) UsCable else UsAtsc, collect.isChecked) }
        val st = TvEngine.state.value
        if (st is EngineState.Ready && !st.version.supportsQam) {
            cable.isEnabled = false
            cable.text = getString(R.string.src_cable_unsupported, st.version.chipId)
        }
        cancel.setOnClickListener { TvEngine.cancelBusy() }
        start.requestFocus()
    }

    private fun append(line: String) {
        log.append(line + "\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun startScan(plan: ChannelPlan, collectGuide: Boolean) {
        if (TvEngine.state.value !is EngineState.Ready) {
            append("Tuner not ready.")
            return
        }
        running = true
        start.isEnabled = false
        cancel.isEnabled = true
        cancel.requestFocus()
        log.text = ""
        val label = if (plan.modulation == Modulation.QAM) "Cable" else "RF"
        append("Scanning ${plan.name}…")
        TvEngine.startScan(plan, collectGuide, object : ScanListener {
            override fun onProgress(index: Int, total: Int, rf: Int, freqHz: Long) = runOnUiThread {
                progress.max = total
                progress.progress = index
                progressText.text = if (index < total) "$label $rf  (%.0f MHz)  ·  ${index + 1} of $total".format(freqHz / 1e6)
                else "Finishing…"
            }

            override fun onLock(rf: Int, locked: Boolean, signal: SignalInfo?) = runOnUiThread {
                if (!locked) return@runOnUiThread
                val q = if ((signal?.qamOrder ?: 0) > 0) "  ${signal!!.qamOrder}-QAM" else ""
                append("$label %3d  locked  SNR %.1f dB$q".format(rf, signal?.snrDb ?: 0.0))
            }

            override fun onError(rf: Int, e: Exception) = runOnUiThread {
                if (e is UnsupportedModulationException) append("Stopped: ${e.message}.")
                else append("$label %3d  error: ${e.message}".format(rf))
            }

            override fun onScrambled(rf: Int, count: Int) = runOnUiThread {
                append("        ($count encrypted, skipped)")
            }

            override fun onChannels(rf: Int, found: List<Channel>) = runOnUiThread {
                for (c in found) append("        ${c.number}  ${c.name}")
                if (found.isEmpty()) append("        (no channels)")
            }
        }) { found ->
            running = false
            start.isEnabled = true
            cancel.isEnabled = false
            progress.progress = progress.max
            val muxes = found.map { it.freqHz }.distinct().size
            progressText.text = "Found ${found.size} channels on $muxes frequencies."
            append("\nDone: ${found.size} channels.")
            start.requestFocus()
        }
    }

    override fun onDestroy() {
        if (running) TvEngine.cancelBusy()
        super.onDestroy()
    }
}

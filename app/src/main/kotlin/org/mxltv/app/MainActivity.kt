// SPDX-License-Identifier: GPL-2.0-or-later
package org.mxltv.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mxltv.core.Channel

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var messageBox: View
    private lateinit var message: TextView
    private lateinit var messageAction: Button
    private lateinit var btnScan: Button
    private lateinit var btnGuide: Button
    private val adapter = ChannelAdapter { play(it) }

    private val pickFirmware = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { TvEngine.importFirmware(it) }
                }.getOrNull() == true
            }
            Toast.makeText(
                this@MainActivity,
                if (ok) "Firmware imported" else "No valid MxL692 firmware found in that file",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        fitSystemBars()
        status = findViewById(R.id.status)
        messageBox = findViewById(R.id.messageBox)
        message = findViewById(R.id.message)
        messageAction = findViewById(R.id.messageAction)
        btnScan = findViewById(R.id.btnScan)
        btnGuide = findViewById(R.id.btnGuide)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        btnScan.setOnClickListener { startActivity(Intent(this, ScanActivity::class.java)) }
        btnGuide.setOnClickListener { startActivity(Intent(this, GuideActivity::class.java)) }
        findViewById<Button>(R.id.btnFirmware).setOnClickListener { pickFirmware.launch(arrayOf("*/*")) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TvEngine.state.collect { render() } }
                launch { TvEngine.channels.collect { adapter.submit(it); render() } }
                launch { TvEngine.epgVersion.collect { adapter.notifyDataSetChanged() } }
                launch {
                    while (true) { delay(30_000); adapter.notifyDataSetChanged() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        TvEngine.refresh()
    }

    override fun onResume() {
        super.onResume()
        TvEngine.refresh()
        TvEngine.stop()
    }

    private fun render() {
        val st = TvEngine.state.value
        val ready = st is EngineState.Ready
        status.text = when (st) {
            is EngineState.Ready -> "Tuner ready · MxL${st.version.chipId} fw ${st.version.firmware}"
            EngineState.Initializing -> getString(R.string.state_initializing)
            EngineState.NoDevice -> "No tuner connected"
            EngineState.NeedPermission -> getString(R.string.state_permission)
            EngineState.NoFirmware -> "Firmware missing"
            is EngineState.Error -> "Tuner error"
        }
        btnScan.isEnabled = ready
        val (msg, action) = when {
            st is EngineState.NoDevice -> getString(R.string.state_no_device) to null
            st is EngineState.NeedPermission -> getString(R.string.state_permission) to
                ("Ask again" to { TvEngine.refresh() })
            st is EngineState.NoFirmware -> getString(R.string.state_no_firmware) to
                ("Import firmware" to { pickFirmware.launch(arrayOf("*/*")) })
            st is EngineState.Error -> "Tuner error: ${st.message}\nReplug the stick and retry." to
                (getString(R.string.retry) to { TvEngine.retry() })
            ready && TvEngine.channels.value.isEmpty() -> getString(R.string.no_channels) to
                (getString(R.string.start_scan) to { btnScan.performClick(); Unit })
            else -> null to null
        }
        messageBox.visibility = if (msg != null) View.VISIBLE else View.GONE
        message.text = msg
        messageAction.visibility = if (action != null) View.VISIBLE else View.GONE
        action?.let { (label, fn) ->
            messageAction.text = label
            messageAction.setOnClickListener { fn() }
            if (!messageAction.hasFocus() && adapter.itemCount == 0) messageAction.requestFocus()
        }
    }

    private fun play(c: Channel) {
        if (TvEngine.state.value !is EngineState.Ready) {
            Toast.makeText(this, status.text, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(PlayerActivity.intent(this, c))
    }
}

class ChannelAdapter(private val onClick: (Channel) -> Unit) : RecyclerView.Adapter<ChannelAdapter.VH>() {
    private var items: List<Channel> = emptyList()

    fun submit(list: List<Channel>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.number)
        val name: TextView = v.findViewById(R.id.name)
        val now: TextView = v.findViewById(R.id.now)
        val progress: ProgressBar = v.findViewById(R.id.progress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        val ctx = h.itemView.context
        h.number.text = c.number
        h.name.text = c.name
        val (now, next) = TvEngine.epg.nowNext(c.epgKey)
        if (now != null) {
            h.now.text = "${now.title} · ${Fmt.minutesLeft(now)} min left" +
                (next?.let { " · Next ${Fmt.time(ctx, it.startUtcMs)} ${it.title}" } ?: "")
            h.progress.visibility = View.VISIBLE
            h.progress.progress = Fmt.progress(now)
        } else {
            h.now.text = next?.let { "Next ${Fmt.time(ctx, it.startUtcMs)} ${it.title}" } ?: (if (c.isCable) "Cable ch ${c.rf}" else "RF ${c.rf}")
            h.progress.visibility = View.INVISIBLE
        }
        h.itemView.setOnClickListener { onClick(c) }
    }
}

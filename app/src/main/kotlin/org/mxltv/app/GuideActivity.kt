// SPDX-License-Identifier: GPL-2.0-or-later
package org.mxltv.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.mxltv.core.Channel
import org.mxltv.core.EpgEvent

/** Two-pane guide: channels on the left, that channel's schedule on the right, details below. */
class GuideActivity : AppCompatActivity() {
    private lateinit var detail: TextView
    private lateinit var status: TextView
    private lateinit var refresh: Button
    private var selected: Channel? = null
    private var refreshing = false

    private val channelAdapter = TextAdapter<Channel>(
        bind = { c -> c.number + "  " + c.name to (TvEngine.epg.nowNext(c.epgKey).first?.title ?: "") },
        onFocus = { select(it) },
        onClick = { c ->
            // Touch: first tap selects, second tap plays. Remote: focus selects, OK plays.
            if (selected?.key != c.key) select(c)
            else startActivity(PlayerActivity.intent(this, c))
        },
    )

    private val eventAdapter = TextAdapter<EpgEvent>(
        bind = { e -> e.title.ifBlank { "(untitled)" } to "${Fmt.day(e.startUtcMs)} ${Fmt.range(this, e)}" },
        onFocus = { showDetail(it) },
        onClick = { e ->
            showDetail(e)
            if (e.startUtcMs <= System.currentTimeMillis()) selected?.let { c ->
                startActivity(PlayerActivity.intent(this, c))
            }
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        fitSystemBars()
        detail = findViewById(R.id.detail)
        status = findViewById(R.id.status)
        refresh = findViewById(R.id.refresh)
        findViewById<RecyclerView>(R.id.channels).apply {
            layoutManager = LinearLayoutManager(this@GuideActivity)
            adapter = channelAdapter
        }
        findViewById<RecyclerView>(R.id.events).apply {
            layoutManager = LinearLayoutManager(this@GuideActivity)
            adapter = eventAdapter
        }
        refresh.setOnClickListener { toggleRefresh() }
        status.text = "Select a channel to see its schedule. Guide data covers about the next 12 hours."

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    TvEngine.channels.collect {
                        channelAdapter.submit(it)
                        if (selected == null) it.firstOrNull()?.let { c -> select(c) }
                    }
                }
                launch {
                    TvEngine.epgVersion.collect {
                        channelAdapter.notifyDataSetChanged()
                        selected?.let { c -> eventAdapter.submit(TvEngine.epg.eventsFor(c.epgKey)) }
                    }
                }
            }
        }
    }

    private fun select(c: Channel) {
        if (selected?.key == c.key) return
        selected = c
        channelAdapter.setActivated(c)
        val ev = TvEngine.epg.eventsFor(c.epgKey)
        eventAdapter.submit(ev)
        if (ev.isEmpty()) detail.text = "No guide data for ${c.number} ${c.name} yet. Use Refresh guide, " +
            "or watch the channel for a minute."
        else showDetail(ev.first())
    }

    private fun showDetail(e: EpgEvent) {
        val c = selected
        detail.text = buildString {
            append(e.title.ifBlank { "(untitled)" })
            append("\n")
            append("${Fmt.day(e.startUtcMs)} ${Fmt.range(this@GuideActivity, e)} · ${e.durationSec / 60} min")
            if (c != null) append(" · ${c.number} ${c.name}")
            e.description?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
        }
    }

    private fun toggleRefresh() {
        if (refreshing) {
            TvEngine.cancelBusy()
            return
        }
        if (TvEngine.state.value !is EngineState.Ready || TvEngine.channels.value.isEmpty()) {
            status.text = "Tuner not ready or no channels."
            return
        }
        refreshing = true
        refresh.text = getString(R.string.cancel)
        status.text = "Refreshing guide…"
        TvEngine.refreshGuide(
            onMux = { i, n -> runOnUiThread { status.text = "Refreshing guide: frequency ${i + 1} of $n…" } },
            onDone = {
                refreshing = false
                refresh.text = getString(R.string.refresh_guide)
                status.text = "Guide updated."
            },
        )
    }

    override fun onDestroy() {
        if (refreshing) TvEngine.cancelBusy()
        super.onDestroy()
    }
}

class TextAdapter<T>(
    private val bind: (T) -> Pair<String, String>,
    private val onFocus: (T) -> Unit,
    private val onClick: (T) -> Unit,
) : RecyclerView.Adapter<TextAdapter.VH>() {
    private var items: List<T> = emptyList()
    private var activated: T? = null

    fun submit(list: List<T>) {
        items = list
        notifyDataSetChanged()
    }

    fun setActivated(item: T) {
        activated = item
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.sub)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_text, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        val (t, s) = bind(item)
        h.title.text = t
        h.sub.text = s
        h.sub.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
        h.itemView.isActivated = item == activated
        h.itemView.setOnFocusChangeListener { _, has -> if (has) onFocus(item) }
        h.itemView.setOnClickListener { onClick(item) }
    }
}

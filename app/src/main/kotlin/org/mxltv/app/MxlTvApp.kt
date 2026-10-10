// SPDX-License-Identifier: GPL-2.0-or-later
package org.mxltv.app

import android.app.Application
import android.app.Activity
import android.text.format.DateFormat
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.content.Context
import org.mxltv.core.EpgEvent
import java.util.Date

class MxlTvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TvEngine.init(this)
    }
}

object Fmt {
    fun time(ctx: Context, ms: Long): String = DateFormat.getTimeFormat(ctx).format(Date(ms))

    fun day(ms: Long): String = android.text.format.DateFormat.format("EEE", Date(ms)).toString()

    fun range(ctx: Context, e: EpgEvent) = "${time(ctx, e.startUtcMs)} – ${time(ctx, e.endUtcMs)}"

    fun progress(e: EpgEvent, now: Long = System.currentTimeMillis()): Int =
        (((now - e.startUtcMs).toDouble() / (e.durationSec * 1000.0)) * 1000).toInt().coerceIn(0, 1000)

    fun minutesLeft(e: EpgEvent, now: Long = System.currentTimeMillis()) =
        ((e.endUtcMs - now) / 60000).coerceAtLeast(0)
}

/** Pads an activity's root view so content stays clear of the status/navigation bars (edge-to-edge). */
fun Activity.fitSystemBars() {
    val root = (findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
    val l = root.paddingLeft; val t = root.paddingTop; val r = root.paddingRight; val b = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(l + i.left, t + i.top, r + i.right, b + i.bottom)
        insets
    }
}

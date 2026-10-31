package com.aivicventures.subtitletranslator.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.aivicventures.subtitletranslator.R
import com.aivicventures.subtitletranslator.app
import com.aivicventures.subtitletranslator.models.ModelSpec
import com.aivicventures.subtitletranslator.models.ModelState
import kotlinx.coroutines.launch

/** Model Manager: download (pinned URL + SHA-256), cancel, delete. */
class ModelsActivity : ComponentActivity() {
    private val rows = mutableMapOf<String, Pair<TextView, Button>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val m = app.models
        val col = column()
        col.addFull(body(getString(R.string.models_intro, m.deviceMemoryMb())))
        for (spec in m.catalog) {
            col.addFull(heading(spec.name))
            col.addFull(body("${spec.description}\n${formatSize(spec.size)} · ${spec.license}"))
            val state = body()
            val action = Button(this).apply { isAllCaps = false }
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            line.addView(action)
            line.addView(state, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
            col.addFull(line)
            rows[spec.id] = state to action
        }
        val scroll = ScrollView(this).apply { addView(col) }
        scroll.padForSystemBars()
        setContentView(scroll)

        lifecycleScope.launch {
            m.states.collect { states -> for (spec in m.catalog) render(spec, states[spec.id] ?: ModelState.NotInstalled) }
        }
    }

    private fun render(spec: ModelSpec, s: ModelState) {
        val (state, action) = rows[spec.id] ?: return
        val m = app.models
        when (s) {
            ModelState.NotInstalled -> {
                state.text = getString(R.string.state_not_installed)
                action.text = getString(R.string.download)
                action.setOnClickListener { m.download(spec) }
            }
            is ModelState.Downloading -> {
                state.text = getString(R.string.state_downloading, 100.0 * s.bytes / s.total.coerceAtLeast(1), formatSize(s.bytes), formatSize(s.total))
                action.text = getString(R.string.cancel)
                action.setOnClickListener { m.cancel(spec) }
            }
            ModelState.Verifying -> {
                state.text = getString(R.string.state_verifying)
                action.text = getString(R.string.cancel)
                action.setOnClickListener { m.cancel(spec) }
            }
            ModelState.Installed -> {
                state.text = getString(R.string.state_installed)
                action.text = getString(R.string.delete)
                action.setOnClickListener { m.delete(spec) }
            }
            is ModelState.Failed -> {
                state.text = getString(R.string.state_failed, s.message)
                action.text = getString(R.string.retry)
                action.setOnClickListener { m.download(spec) }
            }
        }
    }
}

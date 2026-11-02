package com.aivicventures.subtitletranslator.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.aivicventures.subtitletranslator.R
import com.aivicventures.subtitletranslator.app
import com.aivicventures.subtitletranslator.translate.TranslatorNative
import com.google.android.material.R as M
import com.google.android.material.chip.Chip

/** Built-in models: every model ships in the APK, so this screen only explains them. */
class ModelsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = scaffold(getString(R.string.models_title)).content
        val m = app.models
        val auto = m.chooseAsr("")
        col.addFull(card(16, M.attr.colorSecondaryContainer) {
            addView(row(iconBadge(R.drawable.ic_check, M.attr.colorSecondary, M.attr.colorOnSecondary), stack(
                heading(getString(R.string.models_included)).apply { setTextColor(themeColor(M.attr.colorOnSecondaryContainer)) },
                body(getString(R.string.models_intro, "%.1f".format(m.deviceMemoryMb() / 1024.0), auto?.name ?: "-"))
                    .apply { setTextColor(themeColor(M.attr.colorOnSecondaryContainer)) },
            )))
        }, 8)
        for ((kind, title) in listOf("asr" to R.string.models_speech, "vad" to R.string.models_vad, "mt" to R.string.models_translation)) {
            col.addFull(sectionTitle(getString(title)))
            for (spec in m.catalog.filter { it.kind == kind }) {
                val status = when {
                    !m.isInstalled(spec) -> getString(R.string.model_missing)
                    kind == "mt" && !TranslatorNative.available -> getString(R.string.model_needs_64bit)
                    spec.id == auto?.id -> getString(R.string.model_auto)
                    else -> getString(R.string.model_included)
                }
                col.addFull(card {
                    val icon = when (kind) { "asr" -> R.drawable.ic_audio; "mt" -> R.drawable.ic_translate; else -> R.drawable.ic_memory }
                    addView(row(iconBadge(icon), stack(heading(spec.name), caption("${formatSize(spec.size)} · ${spec.license}"))))
                    addFull(body(spec.description), 10)
                    addView(Chip(context).apply {
                        text = status
                        isClickable = false
                        setChipIconResource(R.drawable.ic_check)
                        isChipIconVisible = m.isInstalled(spec)
                    }, android.widget.LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
                }, 8)
            }
        }
    }
}

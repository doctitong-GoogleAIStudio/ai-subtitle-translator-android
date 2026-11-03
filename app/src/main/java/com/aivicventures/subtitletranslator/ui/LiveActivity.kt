package com.aivicventures.subtitletranslator.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aivicventures.subtitletranslator.R
import com.aivicventures.subtitletranslator.app
import com.aivicventures.subtitletranslator.live.AudioMode
import com.aivicventures.subtitletranslator.live.LiveSession
import com.aivicventures.subtitletranslator.live.LiveSubtitleService
import com.aivicventures.subtitletranslator.live.Quality
import com.aivicventures.subtitletranslator.settings.Languages
import com.google.android.material.R as M
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File

/**
 * Live Floating Subtitles: choose the source, start and stop, and watch the session's state.
 * Every permission is asked for when Start is tapped, each with an explanation first, and
 * every refusal ends in a message saying what still works.
 */
class LiveActivity : ComponentActivity() {
    private val live by lazy { app.liveSettings }
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var bigButton: MaterialButton
    private lateinit var modeHelp: TextView
    private lateinit var errorCard: MaterialCardView
    private lateinit var errorText: TextView
    private lateinit var errorActions: LinearLayout
    private lateinit var usageText: TextView
    private val status = HashMap<Int, TextView>()
    private var pendingMode: AudioMode = AudioMode.MICROPHONE
    private var askedNotifications = false
    /** The session ended or failed while this screen showed its own error: keep that one. */
    private var localError = false

    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) checkNotifications() else showError(getString(R.string.live_error_overlay_denied), overlayActions())
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { checkMicrophone() }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchSession() else micDenied()
    }
    private val projectionConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            LiveSubtitleService.start(this, AudioMode.INTERNAL, r.resultCode, data)
        } else {
            showError(getString(R.string.live_error_projection_declined), listOf(getString(R.string.live_action_use_mic) to ::switchToMicrophoneAndStart))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = scaffold(getString(R.string.live_title))
        val col = s.content

        col.addFull(card(20, M.attr.colorPrimaryContainer) {
            addView(iconBadge(R.drawable.ic_closed_caption, androidx.appcompat.R.attr.colorPrimary, M.attr.colorOnPrimary, 52))
            addFull(headline(getString(R.string.live_title)).apply { setTextColor(themeColor(M.attr.colorOnPrimaryContainer)) }, 16)
            addFull(body(getString(R.string.live_description)).apply { setTextColor(themeColor(M.attr.colorOnPrimaryContainer)) }, 6)
        }, 8)

        col.addFull(sectionTitle(getString(R.string.live_session)))
        col.addFull(card(16) {
            val langs = Languages.choices
            addFull(choice(getString(R.string.live_source_language), langs.map { it.second },
                langs.indexOfFirst { it.first == live.sourceLanguage }.coerceAtLeast(0), null) { live.sourceLanguage = langs[it].first })
            addFull(choiceRow(getString(R.string.live_target_language), getString(R.string.live_english)) {}.apply { isClickable = false; background = null })
            val modes = AudioMode.entries
            addFull(choice(getString(R.string.live_audio_source), modes.map { modeName(it) }, modes.indexOf(live.audioMode), null) {
                live.audioMode = modes[it]
                modeHelp.text = modeHelpText(modes[it])
            })
            modeHelp = caption(modeHelpText(live.audioMode))
            addFull(modeHelp)
            val qualities = Quality.entries
            addFull(choice(getString(R.string.live_quality), qualities.map { qualityName(it) }, qualities.indexOf(live.quality),
                getString(R.string.live_quality_help)) { live.quality = qualities[it] }, 8)
        })

        startButton = button(getString(R.string.live_start), R.drawable.ic_play) { startClicked() }.apply { minHeight = dp(56); textSize = 16f }
        col.addFull(startButton, 16)
        col.addFull(tonalButton(getString(R.string.live_settings_title), R.drawable.ic_settings) {
            startActivity(Intent(this, LiveSettingsActivity::class.java))
        }, 8)
        bigButton = tonalButton(getString(R.string.live_show_big), R.drawable.ic_tv) {
            startActivity(Intent(this, SecondDeviceActivity::class.java))
        }
        col.addFull(bigButton, 8)
        stopButton = button(getString(R.string.live_stop_upper), R.drawable.ic_stop, R.attr.appOutlinedButtonStyle) {
            LiveSubtitleService.command(this, LiveSubtitleService.ACTION_STOP)
        }
        col.addFull(stopButton, 8)

        errorText = body().apply { setTextColor(themeColor(M.attr.colorOnErrorContainer)) }
        errorActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        errorCard = card(16, M.attr.colorErrorContainer) {
            addView(row(iconBadge(R.drawable.ic_info, androidx.appcompat.R.attr.colorError, M.attr.colorOnError), stack(errorText)))
            addFull(errorActions, 4)
        }.apply { visibility = View.GONE }
        col.addFull(errorCard, 16)

        col.addFull(sectionTitle(getString(R.string.live_status)))
        col.addFull(card(16) {
            val rows = listOf(
                R.string.live_status_audio to R.drawable.ic_audio,
                R.string.live_status_language to R.drawable.ic_translate,
                R.string.live_status_asr to R.drawable.ic_memory,
                R.string.live_status_translation to R.drawable.ic_subtitles,
                R.string.live_status_overlay to R.drawable.ic_closed_caption,
                R.string.live_status_connection to R.drawable.ic_shield,
                R.string.live_status_latency to R.drawable.ic_speed,
            )
            rows.forEachIndexed { i, (title, icon) ->
                if (i > 0) addFull(divider(), 10)
                val value = caption()
                status[title] = value
                addFull(row(iconBadge(icon), stack(heading(getString(title)), value)), if (i > 0) 10 else 0)
            }
        })

        col.addFull(sectionTitle(getString(R.string.live_usage)))
        usageText = body(getString(R.string.live_usage_none))
        col.addFull(card(16) { addView(usageText) })

        col.addFull(sectionTitle(getString(R.string.about_privacy)))
        col.addFull(card {
            addView(row(iconBadge(R.drawable.ic_shield), stack(body(getString(R.string.live_privacy)))))
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LiveSession.state.collect(::render)
            }
        }
        startTestSessionIfAsked()
    }

    private fun modeName(m: AudioMode) = getString(
        when (m) {
            AudioMode.INTERNAL -> R.string.live_mode_internal
            AudioMode.MICROPHONE -> R.string.live_mode_mic
            AudioMode.SECOND_DEVICE -> R.string.live_mode_second
        },
    )

    private fun modeHelpText(m: AudioMode) = getString(
        when (m) {
            AudioMode.INTERNAL -> if (Build.VERSION.SDK_INT >= 29) R.string.live_mode_internal_help else R.string.live_error_internal_old
            AudioMode.MICROPHONE -> R.string.live_mode_mic_help
            AudioMode.SECOND_DEVICE -> R.string.live_mode_second_help
        },
    )

    private fun qualityName(q: Quality) = getString(if (q == Quality.FAST) R.string.live_quality_fast else R.string.live_quality_high)

    private fun render(s: LiveSession.State) {
        val running = s.running
        startButton.isEnabled = !running
        stopButton.isEnabled = running
        bigButton.visibility = if (running && s.mode == AudioMode.SECOND_DEVICE) View.VISIBLE else View.GONE
        if (running) localError = false
        status[R.string.live_status_audio]?.text = when {
            !s.captureActive -> getString(if (running) R.string.live_state_starting else R.string.live_state_off)
            s.captureBlocked -> getString(R.string.live_state_blocked)
            s.phase == LiveSession.Phase.PAUSED -> getString(R.string.live_phase_paused)
            else -> getString(R.string.live_state_capture, modeName(s.mode ?: AudioMode.MICROPHONE),
                getString(if (s.hearing) R.string.live_state_hearing else R.string.live_state_quiet)) +
                if (s.noiseSuppressor) getString(R.string.live_state_ns) else ""
        }
        status[R.string.live_status_language]?.text = when {
            s.language.isNotEmpty() -> getString(R.string.live_state_language, Languages.name(s.language), s.languageCount, s.languageOf)
            running && live.sourceLanguage != "auto" -> getString(R.string.live_state_language_fixed, Languages.name(live.sourceLanguage))
            running -> getString(R.string.live_state_detecting)
            else -> getString(R.string.live_state_off)
        }
        status[R.string.live_status_asr]?.text = when (s.phase) {
            LiveSession.Phase.LOADING -> getString(R.string.loading_model, s.model)
            LiveSession.Phase.LISTENING, LiveSession.Phase.PAUSED ->
                getString(R.string.live_state_asr, s.model, getString(if (s.recognising) R.string.live_state_working else R.string.live_state_waiting)) +
                    if (s.fallingBehind) getString(R.string.live_state_behind) else ""
            else -> getString(R.string.live_state_off)
        }
        status[R.string.live_status_translation]?.text = if (running && s.translator.isNotEmpty()) {
            getString(if (s.translator == "M2M100") R.string.live_state_mt_m2m100 else R.string.live_state_mt_whisper)
        } else getString(R.string.live_state_off)
        status[R.string.live_status_overlay]?.text = getString(
            when {
                s.overlayActive -> R.string.live_state_overlay_on
                running && s.mode == AudioMode.SECOND_DEVICE -> R.string.live_state_overlay_unused
                else -> R.string.live_state_off
            },
        )
        status[R.string.live_status_connection]?.text = getString(R.string.live_state_offline)
        status[R.string.live_status_latency]?.text = s.latency?.let {
            getString(R.string.live_state_latency, it.totalMs / 1000.0, it.endpointMs, it.queueMs, it.recognitionMs, it.translationMs, it.renderMs, it.samples)
        } ?: getString(R.string.live_state_latency_none)
        s.usage?.let { u ->
            usageText.text = getString(R.string.live_usage_text, formatTime(u.durationSec), u.recognisedSec / 60, u.lines, u.translatedLines,
                u.averageLatencyMs / 1000.0, u.droppedLate + u.droppedBacklog)
        }

        when {
            s.phase == LiveSession.Phase.ERROR && s.error != null -> showError(s.error, errorActionsFor(s))
            running && s.captureBlocked -> showError(getString(R.string.live_blocked_long), listOf(getString(R.string.live_action_use_mic) to ::confirmSwitchToMic))
            s.phase == LiveSession.Phase.ENDED && s.notice != null -> showError(s.notice, emptyList())
            !localError -> errorCard.visibility = View.GONE
        }
    }

    private fun errorActionsFor(s: LiveSession.State): List<Pair<String, () -> Unit>> = when (s.error) {
        getString(R.string.live_error_overlay_denied) -> overlayActions()
        getString(R.string.live_error_mic_denied) -> listOf(getString(R.string.live_action_app_settings) to ::openAppSettings)
        else -> listOf(getString(R.string.live_action_retry) to ::startClicked)
    }

    private fun showError(message: String, actions: List<Pair<String, () -> Unit>>) {
        errorText.text = message
        errorActions.removeAllViews()
        for ((label, action) in actions) errorActions.addFull(tonalButton(label) { action() }, 8)
        errorCard.visibility = View.VISIBLE
    }

    private fun overlayActions() = listOf(
        getString(R.string.live_action_retry) to ::startClicked,
        getString(R.string.live_action_second_device) to {
            live.audioMode = AudioMode.SECOND_DEVICE
            recreate()
        },
    )

    // --- Starting: explain, then overlay, notifications, record audio, screen-capture consent.

    private fun startClicked() {
        localError = true
        errorCard.visibility = View.GONE
        pendingMode = live.audioMode
        if (pendingMode == AudioMode.INTERNAL && Build.VERSION.SDK_INT < 29) {
            showError(getString(R.string.live_error_internal_old), listOf(getString(R.string.live_action_use_mic) to ::switchToMicrophoneAndStart))
            return
        }
        if (app.models.vadModel() == null || app.models.installedAsr().isEmpty()) {
            showError(getString(R.string.models_missing), emptyList())
            return
        }
        MaterialAlertDialogBuilder(this)
            .setIcon(if (pendingMode == AudioMode.INTERNAL) R.drawable.ic_audio else R.drawable.ic_mic)
            .setTitle(R.string.live_consent_title)
            .setMessage(
                getString(
                    when (pendingMode) {
                        AudioMode.INTERNAL -> R.string.live_consent_internal
                        AudioMode.MICROPHONE -> R.string.live_consent_mic
                        AudioMode.SECOND_DEVICE -> R.string.live_consent_second
                    },
                ),
            )
            .setPositiveButton(R.string.live_consent_continue) { _, _ -> checkOverlay() }
            .setNegativeButton(R.string.cancel) { _, _ -> localError = false }
            .show()
    }

    private fun checkOverlay() {
        if (!pendingMode.usesOverlay || Settings.canDrawOverlays(this)) return checkNotifications()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.live_overlay_title)
            .setMessage(R.string.live_overlay_message)
            .setPositiveButton(R.string.live_overlay_open) { _, _ ->
                overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton(R.string.cancel) { _, _ -> showError(getString(R.string.live_error_overlay_denied), overlayActions()) }
            .show()
    }

    /** The ongoing notification (with Stop) needs this on Android 13+; the session runs either way. */
    private fun checkNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else checkMicrophone()
    }

    private fun checkMicrophone() {
        if (granted(Manifest.permission.RECORD_AUDIO)) launchSession() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun micDenied() {
        val permanently = !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        val actions = if (permanently) listOf(getString(R.string.live_action_app_settings) to ::openAppSettings)
        else listOf(getString(R.string.live_action_retry) to ::startClicked)
        showError(getString(R.string.live_error_mic_denied), actions)
    }

    private fun launchSession() {
        localError = false
        when (pendingMode) {
            AudioMode.INTERNAL -> {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                // The whole display: capture is of audio only, and the per-app choice of Android 14+ does not apply to it.
                val intent = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else mpm.createScreenCaptureIntent()
                projectionConsent.launch(intent)
            }
            AudioMode.MICROPHONE -> LiveSubtitleService.start(this, AudioMode.MICROPHONE)
            AudioMode.SECOND_DEVICE -> {
                LiveSubtitleService.start(this, AudioMode.SECOND_DEVICE)
                startActivity(Intent(this, SecondDeviceActivity::class.java))
            }
        }
    }

    private fun switchToMicrophoneAndStart() {
        live.audioMode = AudioMode.MICROPHONE
        modeHelp.text = modeHelpText(AudioMode.MICROPHONE)
        recreate()
    }

    /** Capture blocked during a session: switch only after the user agrees. */
    private fun confirmSwitchToMic() {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_mic)
            .setTitle(R.string.live_use_mic)
            .setMessage(R.string.live_mic_confirm)
            .setPositiveButton(R.string.live_use_mic_yes) { _, _ -> LiveSubtitleService.command(this, LiveSubtitleService.ACTION_USE_MIC) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /**
     * Test hook for the emulator: `test_audio` = name of a file in the app's external files
     * folder `live-test/` (put there with adb), played through the pipeline in real time with
     * `test_mode` deciding overlay (MICROPHONE) or big screen (SECOND_DEVICE). It needs the
     * overlay and record-audio permissions granted beforehand.
     */
    private fun startTestSessionIfAsked() {
        val name = intent.getStringExtra(EXTRA_TEST_AUDIO) ?: return
        if (LiveSession.state.value.running) return
        val file = File(getExternalFilesDir("live-test"), File(name).name)
        if (!file.isFile) return
        val mode = runCatching { AudioMode.valueOf(intent.getStringExtra(EXTRA_TEST_MODE) ?: "") }.getOrDefault(AudioMode.MICROPHONE)
        LiveSubtitleService.start(this, mode, testFile = file.path)
        if (mode == AudioMode.SECOND_DEVICE) startActivity(Intent(this, SecondDeviceActivity::class.java))
    }

    companion object {
        const val EXTRA_TEST_AUDIO = "test_audio"
        const val EXTRA_TEST_MODE = "test_mode"
    }
}

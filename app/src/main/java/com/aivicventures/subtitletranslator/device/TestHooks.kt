package com.aivicventures.subtitletranslator.device

import android.content.Intent
import com.aivicventures.subtitletranslator.BuildConfig

/**
 * Switch for the test overrides that screens accept as intent extras (model, threads,
 * clearing a movie's cache, ...). Exported screens can be started by any app, so in a release
 * build the overrides are ignored unless a developer runs
 * `adb shell setprop debug.subtrans.testhooks 1` before starting the app. Apps cannot set
 * `debug.*` properties, so other apps cannot turn them on.
 */
object TestHooks {
    const val PROPERTY = "debug.subtrans.testhooks"

    val enabled: Boolean by lazy {
        BuildConfig.DEBUG || runCatching {
            ProcessBuilder("getprop", PROPERTY).start().inputStream.bufferedReader().use { it.readText().trim() == "1" }
        }.getOrDefault(false)
    }

    /** [intent] when test overrides are on, otherwise an intent without extras. */
    fun extras(intent: Intent): Intent = if (enabled) intent else Intent()
}

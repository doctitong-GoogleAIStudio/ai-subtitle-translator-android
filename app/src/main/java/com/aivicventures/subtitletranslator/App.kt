package com.aivicventures.subtitletranslator

import android.app.Application
import com.aivicventures.subtitletranslator.cache.CacheDb
import com.aivicventures.subtitletranslator.models.ModelManager
import com.aivicventures.subtitletranslator.settings.LiveSettings
import com.aivicventures.subtitletranslator.settings.Settings

class App : Application() {
    val db by lazy { CacheDb(this) }
    val settings by lazy { Settings(this) }
    val models by lazy { ModelManager(this) }
    val liveSettings by lazy { LiveSettings(this) }
}

val android.content.Context.app: App get() = applicationContext as App

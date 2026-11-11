package com.aivicventures.subtitletranslator.browser

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.aivicventures.subtitletranslator.app
import com.aivicventures.subtitletranslator.live.ContextMemory
import com.aivicventures.subtitletranslator.translate.M2M100Translator
import com.aivicventures.subtitletranslator.translate.TranslatorNative
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates the page's own caption cues into English on the phone (M2M100), ahead of the
 * video: the cues from the current position onward first, so the timestamps of the original
 * captions are kept exactly and nothing has to be recognised from sound. One worker thread;
 * the model is loaded on first use and freed by [close].
 *
 * Context: a sentence the captions split over several cues is translated as one sentence
 * and its English shared out over those cues ([Captions.sentenceAt]); names seen in the
 * English are kept to one spelling ([ContextMemory.harmonize]). Both live only as long as
 * this object.
 */
class CaptionTranslator(
    context: Context,
    /** Track language as the page names it (BCP 47), or null. */
    pageLanguage: String?,
    /** 1 = greedy (Fast), 4 = beam search (High accuracy). */
    private val beam: Int,
    /** Called on the worker thread whenever more English is ready or the state changes. */
    private val onChange: () -> Unit,
) : AheadSubtitles {
    enum class State {
        /** Loading the translation model. */
        LOADING,
        TRANSLATING,
        /** Every known cue is in English. */
        READY,
        /** The captions are already English. */
        ENGLISH,
        /** The language is unknown or not supported, or the model is missing: originals shown. */
        ORIGINAL,
    }

    private val appContext = context.applicationContext
    private val lock = Object()
    private val cues = ArrayList<Cue>()
    private val seen = HashSet<Cue>()
    private val english = ConcurrentHashMap<Cue, String>()
    @Volatile private var closed = false
    @Volatile var language: String? = Captions.languageOf(pageLanguage)
        private set
    @Volatile var state = State.LOADING
        private set
    /** Video position the translation works forward from. */
    @Volatile override var position = 0.0
        set(v) {
            field = v
            synchronized(lock) { lock.notifyAll() }
        }
    /** Average translation time per cue, for the diagnostics. */
    @Volatile var msPerCue = 0L
        private set
    @Volatile var translatedCount = 0
        private set
    private var mt: M2M100Translator? = null
    private val memory = ContextMemory(maxLines = 4)
    /** Sentences translated from more than one cue (diagnostics). */
    @Volatile var mergedSentences = 0
        private set
    private val worker = Thread(::run, "caption-mt").apply { start() }

    fun add(list: List<Cue>) {
        if (list.isEmpty()) return
        synchronized(lock) {
            var added = false
            for (c in list) if (c.text.isNotEmpty() && c.end > c.start && seen.add(c)) {
                cues += c
                added = true
            }
            if (added) {
                cues.sortBy { it.start }
                lock.notifyAll()
            }
        }
    }

    fun cueCount(): Int = synchronized(lock) { cues.size }

    private fun snapshot(): List<Cue> = synchronized(lock) { ArrayList(cues) }

    fun isTranslated(c: Cue) = english.containsKey(c)

    /** English for video time [t]: the lines showing then, and whether one is still untranslated. */
    override fun textAt(t: Double): Pair<String, Boolean> {
        val active = Captions.activeAt(snapshot(), t)
        var missing = false
        val text = active.mapNotNull { c -> english[c] ?: run { missing = true; null } }.joinToString("\n")
        return text to missing
    }

    override fun readyAhead(now: Double, horizon: Double): Double = Captions.readyAhead(snapshot(), ::isTranslated, now, horizon)

    /** Translating (or ready for more cues) and there are cues: Smart Delay applies. */
    override val holdable: Boolean
        get() = (state == State.TRANSLATING || state == State.LOADING || state == State.READY) && cueCount() > 0

    /** Up to [BATCH] sentences (each one or more cues) from the current position on. */
    private fun nextBatch(): List<List<Cue>> {
        val from = position - 1.0
        val out = ArrayList<List<Cue>>(BATCH)
        var i = 0
        while (i < cues.size && out.size < BATCH) {
            val c = cues[i]
            if (c.end < from || english.containsKey(c)) {
                i++
                continue
            }
            val sentence = Captions.sentenceAt(cues, i, { !english.containsKey(it) })
            out += sentence
            i += sentence.size
        }
        return out
    }

    private fun run() {
        try {
            while (!closed) {
                var batch: List<List<Cue>>
                synchronized(lock) {
                    batch = nextBatch()
                    while (!closed && batch.isEmpty()) {
                        if (state == State.TRANSLATING) {
                            state = State.READY
                            onChange()
                        }
                        lock.wait(1000)
                        batch = nextBatch()
                    }
                }
                if (closed) break
                translate(batch)
                onChange()
            }
        } catch (_: InterruptedException) {
        } finally {
            mt?.close()
            mt = null
        }
    }

    private fun translate(sentences: List<List<Cue>>) {
        val batch = sentences.flatten()
        val lang = language ?: Captions.guessLanguage(snapshot().take(40).map { it.text })
            ?: appContext.app.liveSettings.sourceLanguage.takeIf { it != "auto" }
        language = lang
        if (lang == "en") {
            state = State.ENGLISH
            batch.forEach { english[it] = it.text }
            return
        }
        val m = if (lang != null && M2M100Translator.supports(lang)) model() else null
        if (m == null) {
            // Nothing can translate these: the original captions are shown, and the status says why.
            state = State.ORIGINAL
            batch.forEach { english[it] = it.text }
            return
        }
        state = State.TRANSLATING
        val t0 = SystemClock.elapsedRealtime()
        val out = runCatching { m.translate(sentences.map { s -> Captions.joinSentence(s.map { it.text }, lang) }, lang!!, beam) }
            .onFailure { Log.w(TAG, "caption translation failed", it) }.getOrNull()
        val ms = SystemClock.elapsedRealtime() - t0
        if (out == null || out.size != sentences.size) {
            batch.forEach { english[it] = it.text }
            return
        }
        for ((k, s) in sentences.withIndex()) {
            val text = memory.harmonize(out[k].trim())
            memory.add(s.first().start, "", text, lang ?: "")
            if (s.size > 1) mergedSentences++
            Captions.splitSentence(text, s.map { it.text.length }).forEachIndexed { i, part -> english[s[i]] = part }
        }
        translatedCount += batch.size
        val per = ms / batch.size
        msPerCue = if (msPerCue == 0L) per else (msPerCue * 3 + per) / 4
        Log.i(TAG, "captions $lang: ${batch.size} cues in ${sentences.size} sentences, $ms ms (beam $beam)")
    }

    private var modelTried = false

    private fun model(): M2M100Translator? {
        if (mt != null || modelTried) return mt
        modelTried = true
        state = State.LOADING
        onChange()
        val models = appContext.app.models
        val spec = models.translationModel()
        if (spec == null || !TranslatorNative.available) return null
        mt = runCatching {
            models.weights(spec).use { M2M100Translator(models.localFolder(spec), it, appContext.app.settings.threads) }
        }.onFailure { Log.w(TAG, "M2M100 unavailable for captions", it) }.getOrNull()
        return mt
    }

    override fun close() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
        // The worker frees the model when its current batch is done.
    }

    companion object {
        private const val TAG = "Captions"
        /** Cues per translation call: small, so the cue that is due next is never stuck behind many. */
        private const val BATCH = 4
    }
}

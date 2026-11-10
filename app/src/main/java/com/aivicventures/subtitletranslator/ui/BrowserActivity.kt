package com.aivicventures.subtitletranslator.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aivicventures.subtitletranslator.R
import com.aivicventures.subtitletranslator.app
import com.aivicventures.subtitletranslator.browser.BrowserDb
import com.aivicventures.subtitletranslator.browser.CaptionTrack
import com.aivicventures.subtitletranslator.browser.CaptionTranslator
import com.aivicventures.subtitletranslator.browser.Captions
import com.aivicventures.subtitletranslator.browser.Cue
import com.aivicventures.subtitletranslator.live.AudioMode
import com.aivicventures.subtitletranslator.live.LiveSession
import com.aivicventures.subtitletranslator.live.LiveSubtitleService
import com.aivicventures.subtitletranslator.live.OutlinedTextView
import com.aivicventures.subtitletranslator.live.PageAudioBus
import com.aivicventures.subtitletranslator.live.Quality
import com.aivicventures.subtitletranslator.settings.Languages
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The built-in movie browser: tabs, address bar, bookmarks, history and full-screen video in
 * Android's WebView, with English subtitles drawn over the page by this app.
 *
 * Subtitles come from the shared live engine ([LiveSubtitleService] in [AudioMode.PAGE]). The
 * sound is read in the page itself (assets/browser/page.js, Web Audio), which needs no
 * permission; where a site does not allow that, the viewer can choose internal audio (screen
 * capture consent) or the microphone. Nothing is downloaded: the page plays as it normally
 * would and only its sound, while subtitles are on, is recognised on the phone.
 */
class BrowserActivity : ComponentActivity() {
    private class Tab(val web: WebView) {
        var title = ""
        var url = ""
        /** The frame whose video plays now, to send it commands (tap on/off). */
        var player: JavaScriptReplyProxy? = null
        /** Every frame of the page that runs the page script (from its hello), for commands to all. */
        val frames = ArrayList<JavaScriptReplyProxy>()
    }

    private val db by lazy { BrowserDb(this) }
    private val prefs by lazy { getSharedPreferences("browser", MODE_PRIVATE) }
    private val live by lazy { app.liveSettings }
    private val tabs = ArrayList<Tab>()
    private var current = -1
    private val tab: Tab? get() = tabs.getOrNull(current)
    private var pageScript: String? = null
    private val bridgeOk by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }

    private lateinit var root: FrameLayout
    private lateinit var browserColumn: LinearLayout
    private lateinit var address: EditText
    private lateinit var progress: LinearProgressIndicator
    private lateinit var webHolder: FrameLayout
    private lateinit var subtitles: OutlinedTextView
    private lateinit var fullHolder: FrameLayout
    private lateinit var fullSubtitles: OutlinedTextView
    private lateinit var tabsButton: MaterialButton
    private lateinit var backButton: MaterialButton
    private lateinit var forwardButton: MaterialButton
    private lateinit var statusView: TextView
    private lateinit var subtitleButton: MaterialButton
    private lateinit var sourceButton: MaterialButton
    private lateinit var qualityButton: MaterialButton
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    /** Video state reported by the page. */
    private var videoTime = 0.0
    private var videoRate = 1.0
    private var videoPlaying = false
    private var videoLandscape = true
    private var videoDrm = false
    /** Subtitles were started from this screen (and stop with it). */
    private var ownSession = false
    private var tapProblem: String? = null
    private var pendingSource: Source? = null
    /** When [videoTime] was reported, so the clock runs on between the page's reports. */
    private var videoTimeAt = 0L

    // Captions of the page's video, translated ahead of time (no sound involved).
    private val main = Handler(Looper.getMainLooper())
    private var captionTracks: List<CaptionTrack> = emptyList()
    private var captions: CaptionTranslator? = null
    private var captionProblem: String? = null
    /** Smart Delay paused the video and will resume it; a pause by the viewer is never undone. */
    private var heldBySmartDelay = false
    /** After starting or a seek, Smart Delay waits for the whole buffer, not just the next line. */
    private var needFullBuffer = false
    /** The viewer pressed play while Smart Delay waited: no new hold before this time. */
    private var noHoldUntil = 0L
    private var lastStatusAt = 0L
    private val captionTick = object : Runnable {
        override fun run() {
            onCaptionTick()
            main.postDelayed(this, CAPTION_TICK_MS)
        }
    }

    enum class Source { AUTOMATIC, CAPTIONS, PAGE, INTERNAL, MICROPHONE }

    /** Smart Delay buffer target in seconds, 0 = off. */
    private var smartDelaySec: Int
        get() = prefs.getInt("smart_delay", 0)
        set(v) = prefs.edit().putInt("smart_delay", v).apply()

    private var source: Source
        get() = runCatching { Source.valueOf(prefs.getString("source", null) ?: "") }.getOrDefault(Source.AUTOMATIC)
        set(v) = prefs.edit().putString("source", v.name).apply()

    private val searchTemplate: String
        get() = BrowserDb.SEARCH_ENGINES[prefs.getString("search", null)] ?: BrowserDb.SEARCH_ENGINES.values.first()

    private val projectionConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK && r.data != null) {
            startSession(AudioMode.INTERNAL, r.resultCode, r.data)
        } else {
            showStatusOnce(getString(R.string.browser_internal_refused))
        }
    }

    /** What to do once audio recording is allowed (the microphone, or internal audio's consent). */
    private var afterRecordAudio: (() -> Unit)? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val next = afterRecordAudio
        afterRecordAudio = null
        if (ok) next?.invoke() else showStatusOnce(getString(R.string.browser_mic_refused))
    }

    private fun hasRecordAudio() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Android counts capturing other apps' sound as recording audio, so both sources need it. */
    private fun withRecordAudio(then: () -> Unit) {
        if (hasRecordAudio()) then() else {
            afterRecordAudio = then
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingSource?.let { s -> pendingSource = null; startWith(s) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageScript = runCatching { assets.open("browser/page.js").bufferedReader().use { it.readText() } }.getOrNull()
        buildViews()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> customCallback?.onCustomViewHidden() ?: exitFullScreen()
                    tab?.web?.canGoBack() == true -> tab?.web?.goBack()
                    else -> finish()
                }
            }
        })
        val start = intent?.dataString?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        newTab(start ?: prefs.getString("home", null) ?: HOME)
        lifecycleScope.launch { LiveSession.state.collect(::onSession) }
    }

    // ---- Views ----

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface)) }
        browserColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        backButton = iconButton(R.drawable.ic_back, getString(R.string.browser_back)) { tab?.web?.takeIf { it.canGoBack() }?.goBack() }
        forwardButton = iconButton(R.drawable.ic_forward, getString(R.string.browser_forward)) { tab?.web?.takeIf { it.canGoForward() }?.goForward() }
        address = EditText(this).apply {
            setSingleLine()
            hint = getString(R.string.browser_address_hint)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            textSize = 15f
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    go(text.toString())
                    true
                } else false
            }
        }
        val reload = iconButton(R.drawable.ic_refresh, getString(R.string.browser_reload)) { tab?.web?.reload() }
        tabsButton = button("1", 0, R.attr.appTextButtonStyle) { showTabs() }.apply { minWidth = dp(44); contentDescription = getString(R.string.browser_tabs) }
        val menu = iconButton(R.drawable.ic_more, getString(R.string.browser_menu)) { showMenu(it) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            addView(backButton)
            addView(forwardButton)
            addView(address, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(reload)
            addView(tabsButton)
            addView(menu)
        }
        progress = LinearProgressIndicator(this).apply { isIndeterminate = false; max = 100; visibility = View.INVISIBLE }

        webHolder = FrameLayout(this)
        subtitles = subtitleView()
        webHolder.addView(subtitles, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(16); leftMargin = dp(12); rightMargin = dp(12)
        })

        statusView = caption(getString(R.string.browser_status_off)).apply { maxLines = 2 }
        subtitleButton = tonalButton(getString(R.string.browser_subtitles_start), R.drawable.ic_closed_caption) { toggleSubtitles() }
        sourceButton = button("", R.drawable.ic_mic, R.attr.appTextButtonStyle) { chooseSource() }
        qualityButton = button("", 0, R.attr.appTextButtonStyle) { chooseQuality() }
        val settingsButton = iconButton(R.drawable.ic_settings, getString(R.string.live_settings_title)) {
            startActivity(Intent(this, LiveSettingsActivity::class.java))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainer))
            addView(statusView)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(subtitleButton)
                addView(sourceButton)
                addView(qualityButton)
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(settingsButton)
            })
        }

        browserColumn.addView(bar)
        browserColumn.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        browserColumn.addView(webHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        browserColumn.addView(controls)
        root.addView(browserColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fullHolder = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        fullSubtitles = subtitleView()
        root.addView(fullHolder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(browserColumn) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        applyAppearance()
        updateControls()
    }

    private fun subtitleView() = OutlinedTextView(this).apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
    }

    private fun applyAppearance() {
        val a = live.appearance()
        for (v in listOf(subtitles, fullSubtitles)) {
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, a.sizeSp.toFloat())
            v.setTextColor(a.color)
            v.typeface = Typeface.create(Typeface.SANS_SERIF, if (a.bold) Typeface.BOLD else Typeface.NORMAL)
            v.outline = a.outline
            v.maxLines = a.maxLines + 1
            v.setPadding(dp(10), dp(4), dp(10), dp(6))
            v.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(a.backgroundPercent * 255 / 100, 0, 0, 0))
            }
        }
    }

    // ---- Tabs and navigation ----

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(url: String) {
        if (tabs.size >= MAX_TABS) {
            Toast.makeText(this, getString(R.string.browser_tabs_full, MAX_TABS), Toast.LENGTH_SHORT).show()
            return
        }
        val web = WebView(this)
        val t = Tab(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        // Sign-ins on video sites often use a second domain.
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                return when (u.scheme?.lowercase()) {
                    "http", "https", "about", "data", "blob" -> false
                    else -> {
                        if (request.isForMainFrame && request.hasGesture()) offerExternal(u)
                        true
                    }
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                t.url = url
                t.player = null
                t.frames.clear()
                if (t === tab) {
                    address.setText(url)
                    progress.visibility = View.VISIBLE
                    resetVideo()
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                t.url = url
                t.title = view.title ?: url
                if (t === tab) {
                    progress.visibility = View.INVISIBLE
                    if (!address.hasFocus()) address.setText(url)
                    updateControls()
                }
                if (url.startsWith("http")) db.visit(url, t.title)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                if (t === tab) progress.progress = p
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                t.title = title ?: t.title
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                enterFullScreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullScreen()
            }

            // No camera, microphone or location for web pages; protected (DRM) playback is
            // refused too, since its sound could not be subtitled anyway.
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }
        }
        web.setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(this, R.string.browser_no_downloads, Toast.LENGTH_LONG).show()
        }
        if (bridgeOk && pageScript != null) {
            WebViewCompat.addDocumentStartJavaScript(web, pageScript!!, setOf("*"))
            WebViewCompat.addWebMessageListener(web, "subtransBridge", setOf("*")) { _, message, _, _, reply ->
                onPageMessage(t, message.data ?: return@addWebMessageListener, reply)
            }
        }
        tabs.add(t)
        switchTo(tabs.size - 1)
        web.loadUrl(url)
    }

    private fun switchTo(i: Int) {
        tab?.web?.let {
            it.onPause()
            webHolder.removeView(it)
        }
        current = i
        val t = tab ?: return
        webHolder.addView(t.web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        t.web.onResume()
        address.setText(t.url)
        resetVideo()
        updateControls()
    }

    private fun closeTab(i: Int) {
        val t = tabs.getOrNull(i) ?: return
        val wasCurrent = i == current
        if (wasCurrent) {
            webHolder.removeView(t.web)
            current = -1
        } else if (current > i) {
            current--
        }
        tabs.removeAt(i)
        t.web.destroy()
        when {
            tabs.isEmpty() -> newTab(HOME)
            wasCurrent -> switchTo(minOf(i, tabs.size - 1))
            else -> updateControls()
        }
    }

    private fun go(input: String) {
        val url = BrowserDb.toUrl(input, searchTemplate)
        if (url.isEmpty()) return
        tab?.web?.loadUrl(url)
        tab?.web?.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
    }

    private fun offerExternal(u: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browser_external_title)
            .setMessage(getString(R.string.browser_external_message, u.scheme))
            .setPositiveButton(R.string.browser_external_open) { _, _ ->
                val i = if (u.scheme == "intent") runCatching { Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull() else Intent(Intent.ACTION_VIEW, u)
                i?.apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
                try {
                    if (i != null) startActivity(i)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this, R.string.browser_external_none, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTabs() {
        val labels = tabs.mapIndexed { i, t -> (if (i == current) "● " else "") + t.title.ifBlank { t.url.ifBlank { getString(R.string.browser_new_tab) } } }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browser_tabs)
            .setItems(labels.toTypedArray()) { _, which -> switchTo(which) }
            .setPositiveButton(R.string.browser_new_tab) { _, _ -> newTab(HOME) }
            .setNeutralButton(R.string.browser_close_tab) { _, _ -> closeTab(current) }
            .show()
    }

    private fun showMenu(anchor: View) {
        val t = tab
        val url = t?.url.orEmpty()
        PopupMenu(this, anchor).apply {
            val bookmarked = url.startsWith("http") && db.isBookmarked(url)
            menu.add(0, 1, 0, R.string.browser_new_tab)
            if (url.startsWith("http")) menu.add(0, 2, 0, if (bookmarked) R.string.browser_bookmark_remove else R.string.browser_bookmark_add)
            menu.add(0, 3, 0, R.string.browser_bookmarks)
            menu.add(0, 4, 0, R.string.browser_history)
            menu.add(0, 5, 0, R.string.browser_search_engine)
            menu.add(0, 6, 0, R.string.browser_clear_data)
            menu.add(0, 8, 0, R.string.browser_smart_delay)
            menu.add(0, 9, 0, R.string.browser_floating)
            menu.add(0, 7, 0, R.string.browser_help)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> newTab(HOME)
                    2 -> if (bookmarked) db.removeBookmark(url) else db.addBookmark(url, t?.title.orEmpty())
                    3 -> showEntries(getString(R.string.browser_bookmarks), db.bookmarks(), removable = true)
                    4 -> showEntries(getString(R.string.browser_history), db.history(), removable = false)
                    5 -> chooseSearchEngine()
                    6 -> clearData()
                    7 -> showHelp()
                    8 -> chooseSmartDelay()
                    9 -> startActivity(Intent(this@BrowserActivity, LiveActivity::class.java))
                }
                true
            }
            show()
        }
    }

    private fun showEntries(title: String, entries: List<BrowserDb.Entry>, removable: Boolean) {
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.browser_list_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(entries.map { "${it.title}\n${it.url}" }.toTypedArray()) { _, which -> tab?.web?.loadUrl(entries[which].url) }
            .setNegativeButton(R.string.cancel, null)
        if (removable) {
            b.setNeutralButton(R.string.browser_bookmark_edit) { _, _ ->
                val labels = entries.map { it.title }.toTypedArray()
                val keep = BooleanArray(entries.size) { true }
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.browser_bookmark_edit)
                    .setMultiChoiceItems(labels, keep) { _, which, checked -> keep[which] = checked }
                    .setPositiveButton(android.R.string.ok) { _, _ -> entries.forEachIndexed { i, e -> if (!keep[i]) db.removeBookmark(e.url) } }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        } else {
            b.setNeutralButton(R.string.browser_history_clear) { _, _ -> db.clearHistory() }
        }
        b.show()
    }

    private fun chooseSearchEngine() {
        val names = BrowserDb.SEARCH_ENGINES.keys.toList()
        val selected = names.indexOf(prefs.getString("search", null)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browser_search_engine)
            .setSingleChoiceItems(names.toTypedArray(), selected) { d, which ->
                prefs.edit().putString("search", names[which]).apply()
                d.dismiss()
            }
            .show()
    }

    private fun clearData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browser_clear_data)
            .setMessage(R.string.browser_clear_data_message)
            .setPositiveButton(R.string.browser_clear) { _, _ ->
                db.clearHistory()
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                tabs.forEach { it.web.clearCache(true); it.web.clearHistory() }
                Toast.makeText(this, R.string.browser_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browser_help)
            .setMessage(R.string.browser_help_text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---- Full screen ----

    private fun enterFullScreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = callback
        fullHolder.addView(view, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullHolder.addView(fullSubtitles, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(28); leftMargin = dp(24); rightMargin = dp(24)
        })
        fullHolder.visibility = View.VISIBLE
        browserColumn.visibility = View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        requestedOrientation = if (videoLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        renderLines(LiveSession.state.value)
    }

    private fun exitFullScreen() {
        val v = customView ?: return
        fullHolder.removeAllViews()
        fullHolder.visibility = View.GONE
        browserColumn.visibility = View.VISIBLE
        customView = null
        customCallback = null
        if (v.parent != null) (v.parent as ViewGroup).removeView(v)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        renderLines(LiveSession.state.value)
    }

    // ---- Page messages ----

    private fun onPageMessage(t: Tab, data: String, reply: JavaScriptReplyProxy) {
        val m = runCatching { JSONObject(data) }.getOrNull() ?: return
        when (m.optString("type")) {
            "pcm" -> if (t === tab && ownSession) PageAudioBus.offerBase64(m.optString("pcm"))
            "hello" -> {
                if (t.frames.none { it === reply }) t.frames += reply
                // Subtitles already on: a frame that appears later (an embedded player) hears it too.
                if (t === tab && ownSession && isPageSession() && LiveSession.state.value.running) {
                    runCatching { reply.postMessage("{\"cmd\":\"tap\",\"on\":true}") }
                }
            }
            "tracks" -> {
                if (t !== tab) return
                val arr = m.optJSONArray("list")
                val list = (0 until (arr?.length() ?: 0)).mapNotNull { k ->
                    arr!!.optJSONObject(k)?.let { o -> CaptionTrack(o.optInt("i"), o.optString("kind"), o.optString("label"), o.optString("lang")) }
                }
                // A frame without tracks (an advert, say) must not hide the main video's tracks.
                if (list.isEmpty() && t.player != null && reply !== t.player) return
                captionTracks = list
                if (list.isNotEmpty() && t.player == null) t.player = reply
                updateControls()
            }
            "cues" -> {
                if (t !== tab) return
                val c = captions ?: return
                val arr = m.optJSONArray("cues") ?: return
                val list = ArrayList<Cue>(arr.length())
                for (k in 0 until arr.length()) {
                    val q = arr.optJSONArray(k) ?: continue
                    list += Cue(q.optDouble(0), q.optDouble(1), Captions.clean(q.optString(2)))
                }
                c.add(list)
            }
            "cues-error" -> {
                if (t !== tab) return
                captionProblem = m.optString("why")
                Log.i(TAG, "captions unavailable: $captionProblem")
                val c = captions
                if (c != null && c.cueCount() == 0) onCaptionsFailed()
            }
            "media" -> {
                if (t !== tab) return
                val ev = m.optString("ev")
                if (ev == "play" || ev == "playing") t.player = reply
                val reported = m.optDouble("t", 0.0)
                if (ev == "time" && captions != null && Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "clock drift %.3f s".format(java.util.Locale.ROOT, reported - currentVideoTime()))
                }
                videoTime = reported
                videoTimeAt = SystemClock.elapsedRealtime()
                videoRate = m.optDouble("rate", 1.0)
                videoPlaying = !m.optBoolean("paused", true) && ev != "ended" && ev != "emptied"
                videoDrm = m.optBoolean("drm", false)
                val w = m.optInt("w", 0)
                val h = m.optInt("h", 0)
                if (w > 0 && h > 0) videoLandscape = w >= h
                PageAudioBus.paused = !videoPlaying || ev == "seeking"
                if (ev == "seeking" && ownSession) {
                    // The lines being worked on belong to the old position.
                    LiveSubtitleService.resync()
                }
                captions?.let { c ->
                    if (ev == "seeking" || ev == "seeked") {
                        c.position = videoTime
                        needFullBuffer = smartDelaySec > 0
                    }
                    if ((ev == "play" || ev == "playing") && heldBySmartDelay && !resumeSent) {
                        // The viewer chose to play now: respect it.
                        heldBySmartDelay = false
                        needFullBuffer = false
                        noHoldUntil = SystemClock.elapsedRealtime() + 10_000
                    }
                    if (ev == "playing") resumeSent = false
                }
                if ((ev == "playing" || ev == "play") && ownSession && isPageSession()) sendTap(true)
                updateControls()
            }
            "tap" -> {
                if (t !== tab) return
                if (m.optBoolean("ok")) {
                    tapProblem = null
                    Log.i(TAG, "page audio tapped at ${m.optInt("rate")} Hz")
                } else {
                    tapProblem = m.optString("why")
                    Log.i(TAG, "page audio unavailable: $tapProblem ${m.optString("msg")}")
                    // Waiting for one touch on the page is not a failure: the status asks for it.
                    if (ownSession && isPageSession() && tapProblem != "suspended") onTapFailed(tapProblem!!)
                }
                updateControls()
            }
        }
    }

    /**
     * Subtitles from the page's sound on or off, in every frame: the frame whose video will
     * play is not known before it plays, and the viewer's tap on play must find it switched on
     * (Chromium starts page audio only during such a tap).
     */
    private fun sendTap(on: Boolean) {
        val t = tab ?: return
        val msg = "{\"cmd\":\"tap\",\"on\":$on}"
        val targets = (t.frames + listOfNotNull(t.player)).distinctBy { System.identityHashCode(it) }
        for (p in targets) runCatching { p.postMessage(msg) }
    }

    private fun resetVideo() {
        if (captions != null) stopCaptions()
        captionTracks = emptyList()
        captionProblem = null
        videoTime = 0.0
        videoRate = 1.0
        videoPlaying = false
        videoDrm = false
        tapProblem = null
        PageAudioBus.paused = true
    }

    // ---- Subtitles ----

    private fun isPageSession() = LiveSession.state.value.mode == AudioMode.PAGE

    private fun toggleSubtitles() {
        if (captions != null) {
            stopCaptions()
            return
        }
        if (LiveSession.state.value.running) {
            stopSession()
            return
        }
        // Priority: the video's own captions, then its sound, then the microphone (asked first).
        val s = when (source) {
            Source.AUTOMATIC -> when {
                bridgeOk && Captions.pick(captionTracks) != null && captionProblem == null -> Source.CAPTIONS
                bridgeOk -> Source.PAGE
                else -> Source.MICROPHONE
            }
            else -> source
        }
        if (s == Source.CAPTIONS) {
            startCaptions()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && s != Source.PAGE &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // The ongoing notification is how Android shows that sound is being captured.
            pendingSource = s
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startWith(s)
    }

    private fun startWith(s: Source) {
        when (s) {
            Source.CAPTIONS -> startCaptions()
            Source.PAGE, Source.AUTOMATIC -> {
                if (!bridgeOk) {
                    showStatusOnce(getString(R.string.browser_webview_old))
                    return
                }
                startSession(AudioMode.PAGE)
            }
            Source.INTERNAL -> {
                if (Build.VERSION.SDK_INT < 29) {
                    showStatusOnce(getString(R.string.live_error_internal_old))
                    return
                }
                withRecordAudio {
                    projectionConsent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
                }
            }
            Source.MICROPHONE -> withRecordAudio { startSession(AudioMode.MICROPHONE) }
        }
    }

    private fun startSession(mode: AudioMode, resultCode: Int = 0, data: Intent? = null) {
        if (LiveSession.state.value.running) {
            LiveSubtitleService.command(this, LiveSubtitleService.ACTION_STOP)
        }
        tapProblem = null
        ownSession = true
        LiveSubtitleService.start(this, mode, resultCode, data, inApp = true)
        if (mode == AudioMode.PAGE) sendTap(true)
        updateControls()
    }

    private fun stopSession() {
        sendTap(false)
        if (LiveSession.state.value.running) LiveSubtitleService.command(this, LiveSubtitleService.ACTION_STOP)
        ownSession = false
        updateControls()
    }

    /** The page's sound cannot be read: say why and offer the other sources (never switched to silently). */
    private fun onTapFailed(why: String) {
        stopSession()
        val message = getString(
            when (why) {
                "drm" -> R.string.browser_tap_drm
                "suspended" -> R.string.browser_tap_suspended
                "nosource" -> R.string.browser_tap_nosource
                else -> R.string.browser_tap_blocked
            },
        )
        if (isFinishing) return
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browser_tap_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
        if (why != "suspended") {
            if (Build.VERSION.SDK_INT >= 29) b.setPositiveButton(R.string.browser_use_internal) { _, _ -> startWith(Source.INTERNAL) }
            b.setNeutralButton(R.string.browser_use_mic) { _, _ -> startWith(Source.MICROPHONE) }
        }
        b.show()
    }

    private fun chooseSource() {
        val items = Source.entries
        val labels = items.map { getString(sourceLabel(it)) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browser_source_title)
            .setSingleChoiceItems(labels, items.indexOf(source)) { d, which ->
                source = items[which]
                d.dismiss()
                updateControls()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseQuality() {
        val items = Quality.entries
        val labels = items.map { getString(if (it == Quality.FAST) R.string.live_quality_fast else R.string.live_quality_high) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browser_quality_title)
            .setSingleChoiceItems(labels, items.indexOf(live.quality)) { d, which ->
                live.quality = items[which]
                d.dismiss()
                updateControls()
                if (LiveSession.state.value.running) Toast.makeText(this, R.string.browser_quality_next, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun sourceLabel(s: Source) = when (s) {
        Source.AUTOMATIC -> R.string.browser_source_auto
        Source.CAPTIONS -> R.string.browser_source_captions
        Source.PAGE -> R.string.browser_source_page
        Source.INTERNAL -> R.string.browser_source_internal
        Source.MICROPHONE -> R.string.browser_source_mic
    }

    private fun onSession(s: LiveSession.State) {
        if (ownSession && !s.running && s.phase != LiveSession.Phase.STARTING) {
            if (s.phase == LiveSession.Phase.ERROR || s.phase == LiveSession.Phase.ENDED) sendTap(false)
        }
        renderLines(s)
        updateControls()
    }

    private fun renderLines(s: LiveSession.State) {
        // Captions are drawn by video time in onCaptionTick.
        if (captions != null) return
        val text = if (s.running) s.lines.joinToString("\n") { it.text } else ""
        renderText(text, s.lines.lastOrNull()?.provisional == true)
    }

    private fun renderText(text: String, provisional: Boolean) {
        val target = if (customView != null) fullSubtitles else subtitles
        val other = if (target === subtitles) fullSubtitles else subtitles
        other.visibility = View.GONE
        target.text = text
        target.alpha = if (provisional) 0.85f else 1f
        target.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateControls() {
        val t = tab
        backButton.isEnabled = t?.web?.canGoBack() == true
        forwardButton.isEnabled = t?.web?.canGoForward() == true
        tabsButton.text = tabs.size.toString()
        val s = LiveSession.state.value
        val on = s.running || captions != null
        subtitleButton.text = getString(if (on) R.string.browser_subtitles_stop else R.string.browser_subtitles_start)
        subtitleButton.setIconResource(if (on) R.drawable.ic_stop else R.drawable.ic_closed_caption)
        // Short on the button (the bar must fit a phone); the full wording is in the choice dialog.
        sourceButton.text = getString(
            when (source) {
                Source.AUTOMATIC -> R.string.browser_source_auto_short
                Source.CAPTIONS -> R.string.browser_source_captions_short
                Source.PAGE -> R.string.browser_source_page_short
                Source.INTERNAL -> R.string.browser_source_internal_short
                Source.MICROPHONE -> R.string.browser_source_mic_short
            },
        )
        sourceButton.setIconResource(if (source == Source.CAPTIONS) R.drawable.ic_subtitles else if (source == Source.INTERNAL) R.drawable.ic_audio else R.drawable.ic_mic)
        qualityButton.text = getString(if (live.quality == Quality.FAST) R.string.live_quality_fast else R.string.live_quality_high)
        statusView.text = statusText(s)
    }

    /** One plain-language status line, as in the spec: Listening, Detecting language, ... */
    private fun statusText(s: LiveSession.State): String {
        val parts = ArrayList<String>()
        val c = captions
        if (c != null) {
            val lang = c.language?.let { Languages.name(it) } ?: "?"
            parts += when {
                heldBySmartDelay -> getString(R.string.browser_captions_building,
                    c.readyAhead(currentVideoTime(), smartDelaySec.toDouble()).toInt(), smartDelaySec)
                c.state == CaptionTranslator.State.LOADING -> getString(R.string.browser_captions_loading)
                c.state == CaptionTranslator.State.ENGLISH -> getString(R.string.browser_captions_english)
                c.state == CaptionTranslator.State.ORIGINAL -> getString(R.string.browser_captions_original)
                c.state == CaptionTranslator.State.TRANSLATING -> getString(R.string.browser_captions_translating, lang)
                else -> getString(R.string.browser_captions_active, lang)
            }
            if (smartDelaySec > 0) parts += getString(R.string.browser_smart_delay_on, smartDelaySec)
            if (live.showLatency && c.msPerCue > 0) parts += getString(R.string.browser_captions_speed, c.msPerCue)
        } else if (!s.running) {
            parts += when {
                s.phase == LiveSession.Phase.ERROR && s.error != null -> s.error
                tapProblem == "drm" || videoDrm -> getString(R.string.browser_status_drm)
                else -> getString(R.string.browser_status_off)
            }
        } else {
            parts += when {
                s.phase == LiveSession.Phase.STARTING || s.phase == LiveSession.Phase.LOADING -> getString(R.string.live_phase_loading)
                s.phase == LiveSession.Phase.PAUSED -> getString(R.string.browser_status_paused)
                s.captureBlocked -> getString(R.string.browser_status_internal_blocked)
                s.mode == AudioMode.PAGE && !videoPlaying -> getString(R.string.browser_status_waiting)
                s.mode == AudioMode.PAGE && tapProblem == "suspended" -> getString(R.string.browser_status_tap_page)
                s.recognising && s.lines.isEmpty() -> getString(R.string.browser_status_transcribing)
                s.lines.isNotEmpty() -> getString(R.string.browser_status_active)
                s.hearing && s.languageCount < 3 && s.language.isEmpty() -> getString(R.string.browser_status_detecting)
                else -> getString(R.string.browser_status_listening)
            }
            if (s.language.isNotEmpty()) parts += Languages.name(s.language)
            if (s.fallingBehind) parts += getString(R.string.browser_status_behind)
            s.latency?.let { l -> if (live.showLatency && l.totalMs > 0) parts += getString(R.string.browser_status_delay, l.totalMs / 1000.0) }
            // Smart Delay needs upcoming dialogue; sound is only heard as it plays.
            if (smartDelaySec > 0) parts += getString(R.string.browser_smart_delay_unsupported)
        }
        if (videoRate != 1.0 && videoPlaying) parts += getString(R.string.browser_status_speed, videoRate)
        return parts.joinToString(" · ")
    }

    private fun showStatusOnce(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    // ---- Captions and Smart Delay ----

    private var resumeSent = false
    private var shownCaption = ""

    private fun sendCommand(json: String) {
        val proxy = tab?.player ?: return
        runCatching { proxy.postMessage(json) }
    }

    /** The video's position now: the last report plus the time since, at the playing speed. */
    private fun currentVideoTime(): Double =
        if (videoPlaying && videoTimeAt > 0) videoTime + (SystemClock.elapsedRealtime() - videoTimeAt) / 1000.0 * videoRate
        else videoTime

    private fun startCaptions() {
        if (!bridgeOk) {
            showStatusOnce(getString(R.string.browser_webview_old))
            return
        }
        val track = Captions.pick(captionTracks)
        if (track == null) {
            showStatusOnce(getString(R.string.browser_captions_none))
            return
        }
        // One source at a time: a sound session of this screen ends.
        if (LiveSession.state.value.running) stopSession()
        captionProblem = null
        val beam = if (live.quality == Quality.HIGH_ACCURACY) 4 else 1
        captions = CaptionTranslator(this, track.language, beam) { main.post { if (captions != null) updateControls() } }
            .also { it.position = currentVideoTime() }
        heldBySmartDelay = false
        resumeSent = false
        needFullBuffer = smartDelaySec > 0
        noHoldUntil = 0
        Log.i(TAG, "captions on: track ${track.index} kind=${track.kind} lang=${track.language.ifEmpty { "-" }} smartDelay=$smartDelaySec beam=$beam")
        sendCommand("{\"cmd\":\"captions\",\"on\":true,\"i\":${track.index}}")
        main.removeCallbacks(captionTick)
        main.post(captionTick)
        updateControls()
    }

    private fun stopCaptions() {
        main.removeCallbacks(captionTick)
        sendCommand("{\"cmd\":\"captions\",\"on\":false}")
        if (heldBySmartDelay) sendCommand("{\"cmd\":\"resume\"}")
        heldBySmartDelay = false
        captions?.close()
        captions = null
        renderText("", false)
        updateControls()
    }

    private fun onCaptionTick() {
        val c = captions ?: return
        val t = currentVideoTime()
        if (kotlin.math.abs(c.position - t) > 0.5) c.position = t
        val text = c.textAt(t).first
        if (text != shownCaption) {
            shownCaption = text
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "caption at %.2f s: %s".format(java.util.Locale.ROOT, t, text.replace("\n", " ")))
        }
        renderText(text, false)
        smartDelay(c, t)
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatusAt >= 500) {
            lastStatusAt = now
            statusView.text = statusText(LiveSession.state.value)
        }
    }

    /**
     * Smart Delay (captions only): the video waits until [smartDelaySec] seconds ahead are in
     * English, after starting and after each seek, and again if translation falls behind.
     */
    private fun smartDelay(c: CaptionTranslator, t: Double) {
        val target = smartDelaySec
        val translating = c.state == CaptionTranslator.State.TRANSLATING || c.state == CaptionTranslator.State.LOADING ||
            c.state == CaptionTranslator.State.READY
        if (target <= 0 || !translating || c.cueCount() == 0) {
            if (heldBySmartDelay) resumeVideo()
            return
        }
        val ahead = c.readyAhead(t, target.toDouble())
        if (heldBySmartDelay) {
            if (ahead >= target) {
                needFullBuffer = false
                resumeVideo()
            }
            return
        }
        if (!videoPlaying || SystemClock.elapsedRealtime() < noHoldUntil) return
        if ((needFullBuffer && ahead < target) || ahead < HOLD_BELOW_SEC) {
            needFullBuffer = true
            heldBySmartDelay = true
            Log.i(TAG, "smart delay: hold at %.1f s, %.1f s ready".format(java.util.Locale.ROOT, t, ahead))
            sendCommand("{\"cmd\":\"hold\"}")
            updateControls()
        } else if (needFullBuffer) {
            needFullBuffer = false
        }
    }

    private fun resumeVideo() {
        heldBySmartDelay = false
        resumeSent = true
        Log.i(TAG, "smart delay: resume at %.1f s".format(java.util.Locale.ROOT, currentVideoTime()))
        sendCommand("{\"cmd\":\"resume\"}")
        updateControls()
    }

    /** The captions could not be read: say so and offer the sound sources (never switched silently). */
    private fun onCaptionsFailed() {
        stopCaptions()
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browser_captions_failed_title)
            .setMessage(R.string.browser_captions_failed)
            .setNegativeButton(R.string.cancel, null)
        if (bridgeOk) b.setPositiveButton(R.string.browser_use_page) { _, _ -> startWith(Source.PAGE) }
        b.setNeutralButton(R.string.browser_use_mic) { _, _ -> startWith(Source.MICROPHONE) }
        b.show()
    }

    private fun chooseSmartDelay() {
        val values = Captions.SMART_DELAY_CHOICES
        val labels = values.map { if (it == 0) getString(R.string.browser_smart_delay_off) else getString(R.string.browser_smart_delay_value, it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browser_smart_delay_title)
            .setSingleChoiceItems(labels, values.indexOf(smartDelaySec).coerceAtLeast(0)) { d, which ->
                smartDelaySec = values[which]
                needFullBuffer = smartDelaySec > 0 && captions != null
                d.dismiss()
                updateControls()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Lifecycle ----

    override fun onResume() {
        super.onResume()
        tab?.web?.onResume()
        applyAppearance()
        updateControls()
    }

    override fun onPause() {
        tab?.web?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        // Page audio exists only while this screen shows the page: end a session started here.
        if (ownSession && LiveSession.state.value.running) LiveSubtitleService.command(this, LiveSubtitleService.ACTION_STOP)
        main.removeCallbacks(captionTick)
        captions?.close()
        captions = null
        tabs.forEach { it.web.destroy() }
        tabs.clear()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Browser"
        private const val MAX_TABS = 8
        private const val CAPTION_TICK_MS = 100L
        /** Smart Delay holds the video when less than this is translated ahead. */
        private const val HOLD_BELOW_SEC = 1.0
        const val HOME = "https://duckduckgo.com/"
    }
}

package com.termuxbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Process-wide single WebView. Ye Activity ke bahar jinda rehta hai:
 *  - Activity dikh rahi ho  -> WebView Activity ke andar
 *  - Minimize / Home dabane par -> WebView ek overlay window me (Android ise "visible" maanta hai)
 */
object BrowserHolder {
    const val HOME_URL = "https://claude.ai"
    const val AGENT_PORT = 5000           // tumhara agent.py ka port

    // Overlay window ka alpha. 0 mat rakhna (rendering ruk sakti hai). Kam rakho taaki dikhe nahi.
    const val OVERLAY_ALPHA = 0.01f

    // Sirf in sites par agent.js inject hoga aur bridge allowed hai
    val ALLOWED_HOSTS = setOf("claude.ai", "chatgpt.com", "chat.deepseek.com", "gemini.google.com")
    private val ALLOWED_ORIGINS = ALLOWED_HOSTS.map { "https://$it" }.toSet()

    lateinit var appCtx: Context
        private set
    lateinit var webView: WebView
        private set
    private lateinit var ctxWrapper: MutableContextWrapper

    @Volatile var inOverlay = false
        private set
    var autoOverlay = true                // Activity band/hide hote hi overlay me chala jaye
    var onUrlChange: ((String) -> Unit)? = null

    private var currentActivityRef: java.lang.ref.WeakReference<Activity>? = null
    fun currentActivity(): Activity? = currentActivityRef?.get()
    fun setCurrentActivity(a: Activity?) {
        currentActivityRef = if (a == null) null else java.lang.ref.WeakReference(a)
    }

    private var inited = false
    private var bridgeOk = false
    private val bridgeExec = Executors.newCachedThreadPool()

    private val agentJs: String by lazy {
        try {
            appCtx.assets.open("agent.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "console.error('agent.js asset nahi mila')"
        }
    }

    // GM_xmlhttpRequest ka shim: native bridge ke through localhost:5000 tak
    private val SHIM = """
(function(){
  if (window.__gmShim) return; window.__gmShim = true;
  var cbs = {}, n = 0;
  TermuxBridge.onmessage = function(ev){
    var m = JSON.parse(ev.data), c = cbs[m.id];
    if (!c) return; delete cbs[m.id];
    if (m.error) { if (c.onerror) c.onerror({error: m.error}); }
    else if (c.onload) c.onload({status: m.status, responseText: m.body, readyState: 4, finalUrl: c.url});
  };
  window.GM_xmlhttpRequest = function(o){
    var id = ++n;
    cbs[id] = {onload: o.onload, onerror: o.onerror, url: o.url};
    TermuxBridge.postMessage(JSON.stringify({id: id, method: o.method || 'GET', url: o.url, headers: o.headers || {}, data: (o.data == null ? null : o.data)}));
    return {abort: function(){ delete cbs[id]; }};
  };
  window.unsafeWindow = window;
})();
"""

    @SuppressLint("SetJavaScriptEnabled")
    fun init(ctx: Context) {
        if (inited) return
        inited = true
        appCtx = ctx.applicationContext
        ctxWrapper = MutableContextWrapper(appCtx)
        webView = WebView(ctxWrapper)

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportMultipleWindows(false)
        // "; wv" hatao taaki normal Chrome jaisa dikhe (login me kaam aata hai)
        s.userAgentString = s.userAgentString.replace("; wv", "").replace("Version/4.0 ", "")

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val sc = request.url.scheme
                return sc != "http" && sc != "https"     // intent:// wagairah block
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                injectIfAllowed(url)
                url?.let { onUrlChange?.invoke(it) }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                injectIfAllowed(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                url?.let { onUrlChange?.invoke(it) }
            }
        }

        // Secure bridge: sirf allowed https origins, sirf main frame
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView, "TermuxBridge", ALLOWED_ORIGINS
            ) { _, message, _, isMainFrame, reply ->
                val data = message.data
                if (isMainFrame && data != null) {
                    bridgeExec.execute { handleXhr(data, reply) }
                }
            }
            bridgeOk = true
        }

        webView.loadUrl(HOME_URL)
    }

    private fun injectIfAllowed(url: String?) {
        if (!bridgeOk || url == null) return
        val host = Uri.parse(url).host ?: return
        if (host !in ALLOWED_HOSTS) return
        webView.evaluateJavascript(SHIM, null)
        webView.evaluateJavascript(
            "(function(){ if (window.__termuxAgentLoaded) return; window.__termuxAgentLoaded = true;\n" +
                agentJs + "\n})();", null
        )
    }

    // GM_xmlhttpRequest -> sirf http://localhost:5000
    private fun handleXhr(raw: String, reply: JavaScriptReplyProxy) {
        var id = 0
        try {
            val o = JSONObject(raw)
            id = o.getInt("id")
            val u = Uri.parse(o.getString("url"))
            val hostOk = u.host == "localhost" || u.host == "127.0.0.1"
            if (u.scheme != "http" || !hostOk || u.port != AGENT_PORT) {
                reply.postMessage(JSONObject().put("id", id).put("error", "blocked url").toString())
                return
            }
            val c = URL(u.toString()).openConnection() as HttpURLConnection
            c.requestMethod = o.optString("method", "GET").uppercase()
            c.connectTimeout = 5000
            c.readTimeout = 120000
            val h = o.optJSONObject("headers")
            h?.keys()?.forEach { k -> c.setRequestProperty(k, h.getString(k)) }
            if (!o.isNull("data")) {
                c.doOutput = true
                c.outputStream.use { it.write(o.getString("data").toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            reply.postMessage(JSONObject().put("id", id).put("status", status).put("body", body).toString())
        } catch (e: Exception) {
            try {
                reply.postMessage(JSONObject().put("id", id).put("error", e.toString()).toString())
            } catch (_: Exception) {
            }
        }
    }

    // ── Window management ────────────────────────────────────────────────────
    private fun wm() = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun detachFromParent() {
        if (inOverlay) {
            try { wm().removeView(webView) } catch (_: Exception) {}
            inOverlay = false
        } else {
            (webView.parent as? ViewGroup)?.removeView(webView)
        }
    }

    fun attachToActivity(activity: Activity, container: FrameLayout) {
        // IMPORTANT: setBaseContext() mat badlo — context change se WebView recreate (reload) ho jata hai.
        // Sirf parent change karo. WebView hamesha appCtx pe rehta hai.
        if (inOverlay) detachFromParent()
        if (webView.parent !== container) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            container.addView(
                webView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        webView.onResume()
        webView.resumeTimers()
    }

    /** WebView ko full-size, lekin lagbhag invisible, untouchable overlay window me daalo. */
    fun moveToOverlay(): Boolean {
        if (!Settings.canDrawOverlays(appCtx)) return false
        detachFromParent()
        // ctxWrapper.setBaseContext() mat badlo — context change se WebView reload hota hai
        val dm = appCtx.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            dm.widthPixels, dm.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.alpha = OVERLAY_ALPHA
        wm().addView(webView, lp)
        inOverlay = true
        webView.onResume()
        webView.resumeTimers()
        return true
    }

    fun releaseActivity() {
        if (!inOverlay) detachFromParent()
        // ctxWrapper.setBaseContext() mat badlo — WebView reload na ho
    }
}

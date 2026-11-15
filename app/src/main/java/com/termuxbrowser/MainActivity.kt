package com.termuxbrowser

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var container: FrameLayout
    private lateinit var urlBar: EditText
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Row 1: URL bar
        urlBar = EditText(this).apply {
            setSingleLine()
            hint = "URL ya search"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
        }
        root.addView(
            urlBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // Row 2: buttons
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("◀") { goBack() })
        row.addView(btn("⟳") { BrowserHolder.webView.reload() })
        row.addView(btn("Token") { copyToken() })
        row.addView(btn("⬇ Minimize") { doMinimize() })
        root.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // WebView container
        container = FrameLayout(this)
        root.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, BrowserService::class.java))
    }

    override fun onStart() {
        super.onStart()
        BrowserHolder.attachToActivity(this, container)
        BrowserHolder.onUrlChange = { u -> runOnUiThread { if (!urlBar.hasFocus()) urlBar.setText(u) } }
        BrowserHolder.webView.url?.let { urlBar.setText(it) }
    }

    override fun onStop() {
        BrowserHolder.onUrlChange = null
        // WebView ko activity me hi rehne do — overlay move se WebView reload ho jata hai
        // (login/OTP state kho jaati hai). Background me JS still chalta hai.
        super.onStop()
    }

    override fun onDestroy() {
        BrowserHolder.releaseActivity()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = BrowserHolder.webView
        if (w.canGoBack()) w.goBack() else moveTaskToBack(true)
    }

    private fun btn(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun goBack() {
        val w = BrowserHolder.webView
        if (w.canGoBack()) w.goBack()
    }

    private fun go(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        BrowserHolder.webView.loadUrl(url)
        urlBar.clearFocus()
    }

    private fun copyToken() {
        val t = Cfg.token(this)
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("token", t))
        Toast.makeText(this, "Token copy ho gaya:\n$t", Toast.LENGTH_LONG).show()
    }

    private fun doMinimize() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "\"Display over other apps\" permission ON karo, phir wapas aakar Minimize dabao", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !prefs.getBoolean("askedBatt", false)) {
            prefs.edit().putBoolean("askedBatt", true).apply()
            Toast.makeText(this, "Battery restriction hatane ke liye Allow dabao, phir Minimize dobara", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
            return
        }
        moveTaskToBack(true)   // onStop overlay wala kaam khud kar dega
    }
}

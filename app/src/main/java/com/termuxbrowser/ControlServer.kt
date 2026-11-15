package com.termuxbrowser

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Minimal HTTP server (sirf 127.0.0.1:8765). Har request me token chahiye:
 *   header  X-Token: <token>     (ya ?t=<token>)
 *
 * Endpoints:
 *   GET  /ping
 *   GET  /url
 *   POST /load       body = URL
 *   GET  /reload
 *   POST /eval       body = JS code  -> JSON result
 *   POST /click      body = CSS selector
 *   POST /text       body = CSS selector -> innerText
 *   GET  /minimize   (overlay me bhejo)
 *   GET  /show       (browser screen samne lao)
 */
class ControlServer(private val token: String) {
    companion object {
        const val PORT = 8765
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        if (server != null) return
        thread(isDaemon = true, name = "control-server") {
            try {
                val ss = ServerSocket(PORT, 20, InetAddress.getByName("127.0.0.1"))
                server = ss
                while (!ss.isClosed) {
                    val s = ss.accept()
                    pool.execute { handle(s) }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        pool.shutdownNow()
    }

    private fun handle(s: Socket) {
        s.use {
            s.soTimeout = 30000
            val inp = BufferedInputStream(s.getInputStream())
            val head = readHead(inp) ?: return
            val lines = head.split("\r\n")
            val parts = lines[0].split(" ")
            if (parts.size < 2) return
            val target = parts[1]

            val headers = HashMap<String, String>()
            for (i in 1 until lines.size) {
                val idx = lines[i].indexOf(':')
                if (idx > 0) {
                    headers[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
                }
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len > 5_000_000) { respond(s, 413, "too large"); return }
            val body = if (len > 0) String(readN(inp, len), Charsets.UTF_8) else ""

            val uri = Uri.parse("http://x$target")
            val tok = headers["x-token"] ?: uri.getQueryParameter("t")
            if (tok != token) { respond(s, 401, "unauthorized"); return }

            val out = try {
                route(uri.path ?: "/", uri, body)
            } catch (e: Exception) {
                respond(s, 500, "error: ${e.message}")
                return
            }
            respond(s, 200, out)
        }
    }

    private fun route(path: String, q: Uri, body: String): String {
        val arg = body.trim().ifEmpty { q.getQueryParameter("a") ?: "" }
        return when (path) {
            "/ping" -> "pong"
            "/url" -> onMain { BrowserHolder.webView.url ?: "" }
            "/load" -> {
                if (!(arg.startsWith("http://") || arg.startsWith("https://"))) "only http(s) urls"
                else onMain { BrowserHolder.webView.loadUrl(arg); "ok" }
            }
            "/reload" -> onMain { BrowserHolder.webView.reload(); "ok" }
            "/eval" -> evalJs(body.ifBlank { q.getQueryParameter("js") ?: "" })
            "/click" -> evalJs(
                "(function(s){var e=document.querySelector(s);if(!e)return 'notfound';e.click();return 'ok'})(" +
                    JSONObject.quote(arg) + ")"
            )
            "/text" -> evalJs(
                "(function(s){var e=document.querySelector(s);return e?e.innerText:null})(" +
                    JSONObject.quote(arg) + ")"
            )
            "/minimize" -> onMain {
                // Activity ko background me bhejo (overlay me nahi, warna WebView reload hoga)
                val act = currentActivity()
                if (act != null) {
                    act.moveTaskToBack(true)
                    "ok"
                } else "no activity"
            }
            "/show" -> {
                val ctx = BrowserHolder.appCtx
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
                "ok"
            }
            else -> "unknown endpoint"
        }
    }

    private fun <T> onMain(f: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return f()
        val latch = CountDownLatch(1)
        var res: Any? = null
        var err: Throwable? = null
        main.post {
            try { res = f() } catch (t: Throwable) { err = t }
            latch.countDown()
        }
        if (!latch.await(10, TimeUnit.SECONDS)) throw RuntimeException("main thread timeout")
        err?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return res as T
    }

    private fun evalJs(code: String): String {
        val latch = CountDownLatch(1)
        var out = "null"
        main.post {
            BrowserHolder.webView.evaluateJavascript(code) { r ->
                out = r ?: "null"
                latch.countDown()
            }
        }
        if (!latch.await(20, TimeUnit.SECONDS)) return "timeout"
        return out
    }

    private fun readHead(i: InputStream): String? {
        val sb = ByteArrayOutputStream()
        var state = 0
        while (sb.size() < 16384) {
            val b = i.read()
            if (b < 0) return null
            sb.write(b)
            state = when {
                b == 13 && (state == 0 || state == 2) -> state + 1
                b == 10 && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
            if (state == 4) return sb.toString("UTF-8").removeSuffix("\r\n\r\n")
        }
        return null
    }

    private fun readN(i: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = i.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return buf.copyOf(off)
    }

    private fun respond(s: Socket, code: Int, text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            401 -> "Unauthorized"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val o = s.getOutputStream()
        o.write(
            ("HTTP/1.1 $code $status\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        o.write(b)
        o.flush()
    }
}

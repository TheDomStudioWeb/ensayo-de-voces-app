package com.thedom.ensayo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0A0A0A")
        window.navigationBarColor = Color.parseColor("#0A0A0A")

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/songs/", WebViewAssetLoader.InternalStoragePathHandler(this, Songs.root(this)))
            .build()

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0A0A0A"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
        }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Canción compartida desde WhatsApp, Archivos, etc. */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                   else i.getParcelableExtra(Intent.EXTRA_STREAM)) ?: return
        importUri(uri)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 11) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) importUri(uri) else js("window.onPicked && window.onPicked(null)")
        }
    }

    private fun importUri(uri: Uri) {
        js("window.onImporting && window.onImporting()")
        Thread {
            try {
                var name = "Canción"
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: name
                }
                val ext = name.substringAfterLast('.', "audio").lowercase().take(5)
                val title = name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { "Canción" }
                val id = System.currentTimeMillis().toString(36)
                val dir = Songs.dir(this, id).apply { mkdirs() }
                contentResolver.openInputStream(uri)!!.use { inp ->
                    File(dir, "original.$ext").outputStream().use { inp.copyTo(it, 1 shl 16) }
                }
                val meta = JSONObject().put("id", id).put("name", title)
                    .put("created", System.currentTimeMillis()).put("state", "nueva")
                Songs.save(this, meta)
                js("window.onPicked && window.onPicked(${meta})")
            } catch (e: Exception) {
                js("window.onPicked && window.onPicked({error:${JSONObject.quote(e.message ?: "No se pudo abrir")}})")
            }
        }.start()
    }

    fun js(code: String) = main.post { try { web.evaluateJavascript(code, null) } catch (_: Exception) {} }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.onBack ? window.onBack() : false") { r ->
            if (r != "true") {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    /** Puente con la interfaz: en JS es window.Android */
    inner class Bridge {
        @JavascriptInterface fun songs(): String = Songs.list(this@MainActivity).toString()
        @JavascriptInterface fun song(id: String): String = Songs.meta(this@MainActivity, id)?.toString() ?: "null"
        @JavascriptInterface fun status(): String = SeparationService.status.toString()
        @JavascriptInterface fun models(): String = JSONObject()
            .put("voces", Models.ready(this@MainActivity, Models.VOCES))
            .put("coro", Models.ready(this@MainActivity, Models.KARAOKE)).toString()

        @JavascriptInterface fun pick() = main.post {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/mp4", "application/ogg"))
            @Suppress("DEPRECATION")
            startActivityForResult(i, 11)
        }

        @JavascriptInterface fun separate(id: String, coro: Boolean, hq: Boolean) {
            SeparationService.status = JSONObject().put("running", true).put("id", id).put("stage", "Preparando…").put("progress", 0)
            SeparationService.start(this@MainActivity, id, coro, hq)
        }

        @JavascriptInterface fun cancel() { SeparationService.cancel = true }

        @JavascriptInterface fun rename(id: String, name: String) {
            val m = Songs.meta(this@MainActivity, id) ?: return
            Songs.save(this@MainActivity, m.put("name", name))
        }

        @JavascriptInterface fun delete(id: String) {
            if (SeparationService.status.optBoolean("running") && SeparationService.status.optString("id") == id) return
            Songs.delete(this@MainActivity, id)
        }
    }
}

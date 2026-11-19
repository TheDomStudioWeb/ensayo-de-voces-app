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
        if (i?.action == Intent.ACTION_VIEW) { i.data?.let { importUri(it) }; return }
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
                val tmp = File(cacheDir, "entrada.$ext")
                contentResolver.openInputStream(uri)!!.use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 16) } }
                if (Paquete.looksLikeZip(tmp)) {
                    // Paquete de ensayo ya separado (por ejemplo, mandado por WhatsApp)
                    val meta = try { Paquete.import(this, tmp) } finally { tmp.delete() }
                    js("window.onPicked && window.onPicked(${meta})")
                    return@Thread
                }
                val id = System.currentTimeMillis().toString(36)
                val dir = Songs.dir(this, id).apply { mkdirs() }
                if (!tmp.renameTo(File(dir, "original.$ext"))) {
                    tmp.copyTo(File(dir, "original.$ext"), true); tmp.delete()
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
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/mp4", "application/ogg", "application/zip"))
            @Suppress("DEPRECATION")
            startActivityForResult(i, 11)
        }

        @JavascriptInterface fun separate(id: String, coro: Boolean, hq: Boolean, motor: String) {
            SeparationService.status = JSONObject().put("running", true).put("id", id).put("stage", "Preparando…").put("progress", 0)
            SeparationService.start(this@MainActivity, id, coro, hq, motor)
        }

        /** El modelo a veces confunde cuál es la principal: intercambia los nombres de las dos pistas de voz. */
        @JavascriptInterface fun swapVoices(id: String): String {
            val m = Songs.meta(this@MainActivity, id) ?: return "null"
            val st = m.optJSONArray("stems") ?: return m.toString()
            var a = -1; var b = -1
            for (i in 0 until st.length()) {
                when (st.getJSONObject(i).optString("file")) { "principal.wav" -> a = i; "coro.wav" -> b = i }
            }
            if (a >= 0 && b >= 0) {
                val la = st.getJSONObject(a).getString("label")
                st.getJSONObject(a).put("label", st.getJSONObject(b).getString("label"))
                st.getJSONObject(b).put("label", la)
                // la "principal" queda primero
                val first = st.getJSONObject(a); val second = st.getJSONObject(b)
                st.put(a, second); st.put(b, first)
                m.put("swapped", !m.optBoolean("swapped"))
                Songs.save(this@MainActivity, m)
            }
            return m.toString()
        }

        @JavascriptInterface fun cancel() { SeparationService.cancel = true }

        /** Arma el paquete de ensayo y abre "Compartir" (WhatsApp, etc.). */
        @JavascriptInterface fun share(id: String) {
            Thread {
                try {
                    var last = 0L
                    val zip = Paquete.export(this@MainActivity, id) { p ->
                        val now = System.currentTimeMillis()
                        if (now - last > 300) { last = now; js("window.onShareProgress && window.onShareProgress(${p})") }
                    }
                    val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.archivos", zip)
                    val send = Intent(Intent.ACTION_SEND).setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_TEXT, "Paquete de ensayo: abrilo con Ensayo de Voces")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    main.post { startActivity(Intent.createChooser(send, "Mandar paquete de ensayo")) }
                    js("window.onShareProgress && window.onShareProgress(-1)")
                } catch (e: Exception) {
                    js("window.onShareProgress && window.onShareProgress(-2, ${JSONObject.quote(e.message ?: "No se pudo armar el paquete")})")
                }
            }.start()
        }

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

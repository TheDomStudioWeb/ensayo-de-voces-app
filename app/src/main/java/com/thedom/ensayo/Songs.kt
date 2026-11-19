package com.thedom.ensayo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Canciones guardadas: cada una en files/songs/<id>/ con meta.json, el original y las pistas. */
object Songs {
    fun root(ctx: Context) = File(ctx.filesDir, "songs").apply { mkdirs() }
    fun dir(ctx: Context, id: String) = File(root(ctx), id)

    fun meta(ctx: Context, id: String): JSONObject? {
        val f = File(dir(ctx, id), "meta.json")
        return if (f.exists()) try { JSONObject(f.readText()) } catch (_: Exception) { null } else null
    }

    @Synchronized fun save(ctx: Context, m: JSONObject) {
        val d = dir(ctx, m.getString("id")).apply { mkdirs() }
        val tmp = File(d, "meta.json.tmp")
        tmp.writeText(m.toString())
        tmp.renameTo(File(d, "meta.json"))
    }

    fun list(ctx: Context): JSONArray {
        val items = root(ctx).listFiles()?.mapNotNull { meta(ctx, it.name) } ?: emptyList()
        val arr = JSONArray()
        items.sortedByDescending { it.optLong("created") }.forEach { arr.put(it) }
        return arr
    }

    fun delete(ctx: Context, id: String) { dir(ctx, id).deleteRecursively() }
}

/** Modelos abiertos de separación (UVR / MDX-Net, formato ONNX). Se bajan una sola vez. */
object Models {
    class Spec(val file: String, val nFft: Int, val dimF: Int, val label: String, private val fixedUrl: String? = null) {
        val url get() = fixedUrl ?: "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/$file"
    }

    val VOCES = Spec("Kim_Vocal_2.onnx", 7680, 3072, "voces y música")
    val KARAOKE = Spec("UVR_MDXNET_KARA_2.onnx", 5120, 2048, "voz principal y coros")
    /** BS-RoFormer voz masculina / femenina (aufr33), convertido por tools/export_fm.py */
    val FM = Spec("bs_roformer_femenina_masculina_fp16.onnx", 0, 0, "voz femenina y masculina",
        "https://github.com/TheDomStudioWeb/ensayo-de-voces-app/releases/download/modelos/bs_roformer_femenina_masculina_fp16.onnx")

    fun file(ctx: Context, s: Spec) = File(File(ctx.filesDir, "models").apply { mkdirs() }, s.file)
    fun ready(ctx: Context, s: Spec) = file(ctx, s).let { it.exists() && it.length() > 1_000_000 }

    fun download(ctx: Context, s: Spec, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val dest = file(ctx, s)
        if (ready(ctx, s)) return
        val tmp = File(dest.parentFile, dest.name + ".part")
        var url = URL(s.url)
        var conn: HttpURLConnection
        var hops = 0
        while (true) { // GitHub redirige a su CDN
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000; conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 6) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
            if (code != 200) throw Exception("No se pudo bajar el modelo (código $code)")
            break
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { inp ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var got = 0L
                while (true) {
                    if (cancelled()) throw InterruptedException("cancelado")
                    val k = inp.read(buf); if (k < 0) break
                    out.write(buf, 0, k); got += k
                    if (total > 0) progress(got.toFloat() / total)
                }
            }
        }
        conn.disconnect()
        if (tmp.length() < 1_000_000) throw Exception("El modelo bajó incompleto, probá de nuevo")
        tmp.renameTo(dest)
    }
}

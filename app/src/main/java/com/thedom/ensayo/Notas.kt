package com.thedom.ensayo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Arma pitch.json (gráfico tonal) con las pistas de voz de una canción ya separada. */
object Notas {
    fun file(ctx: Context, id: String) = File(Songs.dir(ctx, id), "pitch.json")

    fun build(ctx: Context, id: String, progress: (Float) -> Unit) {
        val meta = Songs.meta(ctx, id) ?: return
        val stems = meta.optJSONArray("stems") ?: return
        val dir = Songs.dir(ctx, id)
        val voices = (0 until stems.length()).map { stems.getJSONObject(it) }
            .filter { !it.optString("file").startsWith("musica") }
        val out = JSONArray()
        voices.forEachIndexed { i, s ->
            val f = File(dir, s.getString("file"))
            val (l, r, n) = if (f.name.endsWith(".wav")) {
                val w = Engine.readWav(f); Triple(w[0], w[1], w[0].size)
            } else {
                val p = AudioIO.decode(f) { }; Triple(p.left, p.right, p.frames)
            }
            val res = Pitch.analyze(l, r, n) { p -> progress((i + p) / voices.size) }
            out.put(Pitch.toJson(s.getString("file"), s.getString("label"), res))
        }
        val json = JSONObject().put("version", 1).put("paso", Pitch.STEP).put("voces", out)
        val tmp = File(dir, "pitch.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file(ctx, id))
    }
}

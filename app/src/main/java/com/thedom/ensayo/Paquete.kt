package com.thedom.ensayo

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * "Paquete de ensayo": una canción ya separada, comprimida en un .zip con las pistas en m4a.
 * Se manda por WhatsApp y el otro teléfono solo la abre y la escucha, sin separar nada.
 */
object Paquete {

    /** Arma el paquete de una canción lista. Devuelve el .zip (en la caché, para compartir). */
    fun export(ctx: Context, id: String, progress: (Float) -> Unit): File {
        val meta = Songs.meta(ctx, id) ?: throw Exception("No encuentro la canción")
        val stems = meta.optJSONArray("stems") ?: throw Exception("La canción no está separada")
        val dir = Songs.dir(ctx, id)
        val out = File(File(ctx.cacheDir, "paquetes").apply { mkdirs(); listFiles()?.forEach { it.delete() } },
            safe(meta.optString("name", "cancion")) + " - ensayo.zip")
        val pkStems = JSONArray()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (i in 0 until stems.length()) {
                val s = stems.getJSONObject(i)
                val wav = File(dir, s.getString("file"))
                val m4a = File(ctx.cacheDir, "tmp_$i.m4a")
                encodeAac(wav, m4a) { p -> progress((i + p) / stems.length()) }
                val name = s.getString("file").substringBeforeLast('.') + ".m4a"
                zip.putNextEntry(ZipEntry(name))
                m4a.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                m4a.delete()
                pkStems.put(JSONObject().put("file", name).put("label", s.getString("label")))
            }
            val info = JSONObject().put("app", "ensayo-de-voces").put("version", 1)
                .put("name", meta.optString("name")).put("seconds", meta.optInt("seconds"))
                .put("coro", meta.optBoolean("coro")).put("modo", meta.optString("modo", if (meta.optBoolean("coro")) "coro" else "voces")).put("stems", pkStems)
            zip.putNextEntry(ZipEntry("ensayo.json"))
            zip.write(info.toString().toByteArray())
            zip.closeEntry()
        }
        return out
    }

    /** ¿El archivo es un paquete de ensayo? (empieza como zip) */
    fun looksLikeZip(f: File): Boolean = f.length() > 4 && f.inputStream().use {
        val b = ByteArray(2); it.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
    }

    /** Importa un paquete recibido como canción lista para escuchar. */
    fun import(ctx: Context, zipFile: File): JSONObject {
        val id = System.currentTimeMillis().toString(36)
        val dir = Songs.dir(ctx, id).apply { mkdirs() }
        var info: JSONObject? = null
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val name = File(e.name).name // sin carpetas (seguridad)
                    if (e.isDirectory || name.isEmpty()) continue
                    if (name == "ensayo.json") info = JSONObject(zin.readBytes().toString(Charsets.UTF_8))
                    else if (name.endsWith(".m4a")) File(dir, name).outputStream().use { zin.copyTo(it) }
                }
            }
            val inf = info ?: throw Exception("Ese archivo no es un paquete de Ensayo de Voces")
            val stems = inf.getJSONArray("stems")
            for (i in 0 until stems.length()) {
                val f = File(dir, File(stems.getJSONObject(i).getString("file")).name)
                if (!f.exists()) throw Exception("Al paquete le falta una pista")
            }
            val meta = JSONObject().put("id", id).put("name", inf.optString("name", "Canción"))
                .put("created", System.currentTimeMillis()).put("state", "lista").put("received", true)
                .put("seconds", inf.optInt("seconds")).put("coro", inf.optBoolean("coro")).put("modo", inf.optString("modo", if (inf.optBoolean("coro")) "coro" else "voces")).put("stems", stems)
            Songs.save(ctx, meta)
            return meta
        } catch (e: Exception) {
            dir.deleteRecursively(); throw e
        }
    }

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(60).ifEmpty { "cancion" }

    /** WAV propio (16 bits estéreo 44,1 kHz) a AAC 192 kbps en m4a, con el codificador del teléfono. */
    private fun encodeAac(wav: File, dest: File, progress: (Float) -> Unit) {
        val total = (wav.length() - 44).coerceAtLeast(0)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Engine.SR, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        val input: InputStream = BufferedInputStream(FileInputStream(wav), 1 shl 16)
        try {
            var skip = 44L; while (skip > 0) skip -= input.skip(skip)
            var read = 0L
            var inDone = false
            var outDone = false
            val chunk = ByteArray(8192)
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        buf.clear()
                        val want = minOf(chunk.size, buf.remaining())
                        var n = 0
                        while (n < want) { val k = input.read(chunk, n, want - n); if (k < 0) break; n += k }
                        val pts = read / 4 * 1_000_000L / Engine.SR
                        if (n <= 0) {
                            codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            buf.put(chunk, 0, n)
                            codec.queueInputBuffer(ii, 0, n, pts, 0)
                            read += n
                            if (total > 0) progress(read.toFloat() / total)
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat); muxer.start()
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && track >= 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
        } finally {
            input.close()
            codec.stop(); codec.release()
            if (track >= 0) muxer.stop()
            muxer.release()
        }
    }
}

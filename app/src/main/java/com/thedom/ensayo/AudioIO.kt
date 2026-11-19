package com.thedom.ensayo

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/** Decodifica cualquier audio que entienda Android (mp3, m4a, wav, ogg, flac…) a estéreo 44,1 kHz en float. */
object AudioIO {

    class Pcm(val left: FloatArray, val right: FloatArray, val frames: Int)

    fun decode(file: File, progress: (Float) -> Unit): Pcm {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        var track = -1
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) { track = i; break }
        }
        require(track >= 0) { "El archivo no tiene audio" }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()

        // Capacidad inicial según la duración; crece si hace falta
        var cap = ((durationUs / 1e6 + 2) * rate).toInt().coerceAtLeast(rate * 10)
        var l = FloatArray(cap)
        var r = FloatArray(cap)
        var count = 0
        var floatPcm = false
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                        if (durationUs > 0) progress((ex.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                        ex.advance()
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10_000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = codec.outputFormat
                rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            } else if (oi >= 0) {
                val out = codec.getOutputBuffer(oi)!!
                out.position(info.offset); out.limit(info.offset + info.size)
                out.order(ByteOrder.LITTLE_ENDIAN)
                val bytesPerSample = if (floatPcm) 4 else 2
                val frames = info.size / (bytesPerSample * channels)
                if (count + frames > cap) {
                    cap = ((count + frames) * 1.3).toInt()
                    l = l.copyOf(cap); r = r.copyOf(cap)
                }
                if (floatPcm) {
                    val fb = out.asFloatBuffer()
                    for (f in 0 until frames) {
                        val a = fb.get(f * channels)
                        l[count + f] = a
                        r[count + f] = if (channels > 1) fb.get(f * channels + 1) else a
                    }
                } else {
                    val sb = out.asShortBuffer()
                    for (f in 0 until frames) {
                        val a = sb.get(f * channels) / 32768f
                        l[count + f] = a
                        r[count + f] = if (channels > 1) sb.get(f * channels + 1) / 32768f else a
                    }
                }
                count += frames
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()

        if (rate == Engine.SR) return Pcm(l, r, count)
        // Remuestreo lineal a 44,1 kHz (alcanza para separar voces)
        val n = (count.toLong() * Engine.SR / rate).toInt()
        val nl = FloatArray(n); val nr = FloatArray(n)
        val step = rate.toDouble() / Engine.SR
        for (i in 0 until n) {
            val pos = i * step
            val j = pos.toInt().coerceAtMost(count - 1)
            val k = (j + 1).coerceAtMost(count - 1)
            val t = (pos - j).toFloat()
            nl[i] = l[j] + (l[k] - l[j]) * t
            nr[i] = r[j] + (r[k] - r[j]) * t
        }
        return Pcm(nl, nr, n)
    }
}

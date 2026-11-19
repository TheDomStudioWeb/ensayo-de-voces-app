package com.thedom.ensayo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer

/** Modelo ONNX corriendo en el procesador del teléfono. */
/**
 * Modelo ONNX corriendo en el teléfono. motor: "xnnpack" (procesador acelerado),
 * "nnapi" (chip gráfico / de IA, en 16 bits) o "cpu". Si el motor elegido falla, cae a "cpu".
 */
class OrtModel(path: String, dimF: Int, wanted: String) : Engine.Model, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val shape = longArrayOf(1, 4, dimF.toLong(), Engine.DIM_T.toLong())
    var motor = wanted
        private set

    init {
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        fun opts(m: String) = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            when (m) {
                "xnnpack" -> { setIntraOpNumThreads(1); addXnnpack(mapOf("intra_op_num_threads" to cores.toString())) }
                "nnapi" -> { setIntraOpNumThreads(cores); addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.USE_FP16)) }
                else -> setIntraOpNumThreads(cores)
            }
        }
        session = try { env.createSession(path, opts(wanted)) } catch (e: Throwable) {
            motor = "cpu"; env.createSession(path, opts("cpu"))
        }
        inputName = session.inputNames.iterator().next()
    }

    override fun run(input: FloatArray, out: FloatArray) {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { t ->
            session.run(mapOf(inputName to t)).use { res ->
                (res.get(0) as OnnxTensor).floatBuffer.get(out)
            }
        }
    }

    override fun close() { session.close() }
}

/**
 * Servicio en primer plano: separa la canción aunque se apague la pantalla.
 * El estado lo lee la interfaz con Bridge.status().
 */
class SeparationService : Service() {

    companion object {
        @Volatile var status: JSONObject = JSONObject().put("running", false)
        @Volatile var cancel = false
        private const val CH = "proceso"
        private const val NID = 7

        fun start(ctx: Context, id: String, coro: Boolean, hq: Boolean, motor: String) {
            val i = Intent(ctx, SeparationService::class.java)
                .putExtra("id", id).putExtra("coro", coro).putExtra("hq", hq).putExtra("motor", motor)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private var worker: Thread? = null
    private var lastNotif = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id")
        startAsForeground(notification("Preparando…", 0))
        if (id == null || worker?.isAlive == true) return START_NOT_STICKY
        val coro = intent.getBooleanExtra("coro", false)
        val hq = intent.getBooleanExtra("hq", true)
        val motor = intent.getStringExtra("motor") ?: "xnnpack"
        cancel = false
        worker = Thread({ work(id, coro, hq, motor) }, "separacion").apply { start() }
        return START_NOT_STICKY
    }

    private fun startAsForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NID, n)
    }

    private fun work(id: String, coro: Boolean, hq: Boolean, wanted: String) {
        val wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ensayo:separar")
        wl.acquire(3 * 60 * 60 * 1000L)
        val t0 = System.currentTimeMillis()
        val meta = Songs.meta(this, id) ?: JSONObject().put("id", id)
        val dir = Songs.dir(this, id)
        try {
            meta.put("state", "procesando").put("coro", coro).put("hq", hq).put("motor", wanted).remove("error")
            Songs.save(this, meta)

            // Pesos de cada etapa en la barra total
            val wDecode = 0.04f
            val wVoces = if (coro) 0.60f else 0.96f
            val wKara = if (coro) 0.36f else 0f

            for (spec in listOfNotNull(Models.VOCES, if (coro) Models.KARAOKE else null)) {
                if (!Models.ready(this, spec)) {
                    Models.download(this, spec,
                        { p -> report(id, "Bajando el modelo de ${spec.label} (solo la primera vez)", p, null, t0) },
                        { cancel })
                }
            }

            val original = dir.listFiles()?.firstOrNull { it.name.startsWith("original") }
                ?: throw Exception("No encuentro el archivo original")
            report(id, "Leyendo la canción", 0f, 0f, t0)
            var pcm: AudioIO.Pcm? = AudioIO.decode(original) { p -> report(id, "Leyendo la canción", p, p * wDecode, t0) }
            meta.put("seconds", pcm!!.frames / Engine.SR)
            Songs.save(this, meta)

            val pg = { label: String, base: Float, w: Float ->
                object : Engine.Progress {
                    override fun on(p: Float) = report(id, label, p, base + p * w, t0)
                    override fun cancelled() = cancel
                }
            }

            var used = wanted
            OrtModel(Models.file(this, Models.VOCES).absolutePath, Models.VOCES.dimF, wanted).use { m ->
                used = m.motor
                Engine.WavWriter(File(dir, "voces.wav")).use { a ->
                    Engine.WavWriter(File(dir, "musica.wav")).use { b ->
                        Engine.separate(m, Models.VOCES.nFft, Models.VOCES.dimF, hq,
                            pcm!!.left, pcm!!.right, pcm!!.frames, a, b, pg("Separando voces y música", wDecode, wVoces))
                    }
                }
            }
            pcm = null // libera la memoria antes de la segunda etapa

            val stems = JSONArray()
            stems.put(JSONObject().put("file", "musica.wav").put("label", "Música"))
            if (coro) {
                val v = Engine.readWav(File(dir, "voces.wav"))
                OrtModel(Models.file(this, Models.KARAOKE).absolutePath, Models.KARAOKE.dimF, wanted).use { m ->
                    Engine.WavWriter(File(dir, "principal.wav")).use { a ->
                        Engine.WavWriter(File(dir, "coro.wav")).use { b ->
                            Engine.separate(m, Models.KARAOKE.nFft, Models.KARAOKE.dimF, hq,
                                v[0], v[1], v[0].size, a, b, pg("Separando voz principal y coros", wDecode + wVoces, wKara))
                        }
                    }
                }
                stems.put(JSONObject().put("file", "principal.wav").put("label", "Voz principal"))
                stems.put(JSONObject().put("file", "coro.wav").put("label", "Coros"))
            } else {
                stems.put(JSONObject().put("file", "voces.wav").put("label", "Voces"))
            }

            val secs = ((System.currentTimeMillis() - t0) / 1000).toInt()
            meta.put("state", "lista").put("stems", stems).put("procSeconds", secs).put("motor", used).put("swapped", false)
            Songs.save(this, meta)
            status = JSONObject().put("running", false).put("id", id).put("done", true).put("procSeconds", secs)
            finalNotification("Lista: ${meta.optString("name")}", "Separada en ${secs / 60} min ${secs % 60} s")
        } catch (e: Throwable) {
            val msg = if (e is InterruptedException) "Cancelado" else (e.message ?: e.javaClass.simpleName)
            meta.put("state", if (e is InterruptedException) "nueva" else "error").put("error", msg)
            Songs.save(this, meta)
            status = JSONObject().put("running", false).put("id", id).put("error", msg)
            finalNotification("No se pudo separar", msg)
        } finally {
            if (wl.isHeld) wl.release()
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun report(id: String, stage: String, p: Float, total: Float?, t0: Long) {
        val elapsed = (System.currentTimeMillis() - t0) / 1000.0
        val s = JSONObject().put("running", true).put("id", id).put("stage", stage)
            .put("progress", p.toDouble()).put("elapsed", elapsed)
        if (total != null) {
            s.put("total", total.toDouble())
            if (total > 0.06f) s.put("eta", elapsed / total * (1 - total))
        }
        status = s
        val now = System.currentTimeMillis()
        if (now - lastNotif > 1500) {
            lastNotif = now
            val pct = ((total ?: p) * 100).toInt()
            getSystemService(NotificationManager::class.java).notify(NID, notification(stage, pct))
        }
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH, "Separación de canciones", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @Suppress("DEPRECATION")
    private fun builder(): Notification.Builder {
        channel()
        return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
    }

    private fun notification(text: String, pct: Int): Notification = builder()
        .setSmallIcon(R.drawable.ic_stat_voces)
        .setContentTitle("Ensayo de Voces")
        .setContentText(text)
        .setProgress(100, pct, false)
        .setOngoing(true)
        .setContentIntent(openApp())
        .build()

    private fun finalNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(NID + 1, builder()
            .setSmallIcon(R.drawable.ic_stat_voces)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build())
    }
}

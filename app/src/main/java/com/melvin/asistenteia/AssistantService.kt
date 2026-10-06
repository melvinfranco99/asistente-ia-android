package com.melvin.asistenteia

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Servicio en primer plano que mantiene vivo al asistente en segundo plano:
 *  - Escucha continua esperando la palabra de activación (opcional).
 *  - Escucha de una orden al pulsar "Hablar" (notificación, tile, botón de asistente).
 *  - Envía la orden al agente de IA y lee la respuesta en voz alta.
 */
class AssistantService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    private enum class Mode { IDLE, WAKE, COMMAND, BUSY, SPEAKING }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var agent: ClaudeAgent
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mode = Mode.IDLE
    private var job: Job? = null
    private var listenAfterSpeaking = false
    private var restartRunnable: Runnable? = null
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        agent = ClaudeAgent(prefs, PhoneTools(applicationContext, prefs))
        createChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            EventLog.add("Falta el permiso de micrófono")
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground("Preparado")
        running = true

        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_LISTEN -> listenForCommand()
            ACTION_TEXT -> intent.getStringExtra(EXTRA_TEXT)?.let { process(it) }
            else -> if (mode == Mode.IDLE) idle()
        }
        return START_STICKY
    }

    // ---------- Estados ----------

    /** Estado de reposo: escucha continua de la palabra de activación, o nada. */
    private fun idle() {
        cancelRestart()
        if (prefs.continuous) {
            mode = Mode.WAKE
            setStatus("Esperando “${prefs.wakeWord}”…")
            startRecognizer(wake = true)
        } else {
            mode = Mode.IDLE
            stopRecognizer()
            setStatus("En espera. Pulsa “Hablar”.")
        }
    }

    private fun listenForCommand() {
        if (mode == Mode.BUSY) { EventLog.add("Ocupado con la orden anterior"); return }
        tts?.stop()
        cancelRestart()
        mode = Mode.COMMAND
        setStatus("Te escucho…")
        beep()
        main.postDelayed({ if (mode == Mode.COMMAND) startRecognizer(wake = false) }, 250)
    }

    private fun process(command: String) {
        if (mode == Mode.BUSY) return
        stopRecognizer()
        mode = Mode.BUSY
        EventLog.add("🗣 $command")
        setStatus("Pensando: $command")
        job = scope.launch {
            val reply = try {
                agent.handle(command) { step -> EventLog.add(step) }
            } catch (e: Exception) {
                "Ha ocurrido un error: ${e.message?.take(100)}"
            }
            EventLog.add("🤖 $reply")
            listenAfterSpeaking = reply.trim().endsWith("?")
            speak(reply)
        }
    }

    private fun speak(text: String) {
        if (prefs.speak && ttsReady) {
            mode = Mode.SPEAKING
            setStatus(text)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
        } else {
            setStatus(text)
            afterSpeaking()
        }
    }

    private fun afterSpeaking() {
        if (listenAfterSpeaking) {
            listenAfterSpeaking = false
            mode = Mode.IDLE
            listenForCommand()
        } else {
            // Pequeña pausa para no captar el final de nuestra propia voz.
            mode = Mode.IDLE
            scheduleRestart(1200) { idle() }
        }
    }

    // ---------- Reconocimiento de voz ----------

    private fun startRecognizer(wake: Boolean) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Este móvil no tiene reconocimiento de voz (instala la app de Google)")
            return
        }
        stopRecognizer()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
            r.setRecognitionListener(this)
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                if (!wake) {
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                }
            }
            r.startListening(i)
        }
    }

    private fun stopRecognizer() {
        recognizer?.run { runCatching { cancel() }; runCatching { destroy() } }
        recognizer = null
    }

    override fun onResults(results: Bundle?) {
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val best = list.firstOrNull()?.trim().orEmpty()
        when (mode) {
            Mode.WAKE -> {
                val wake = PhoneTools.norm(prefs.wakeWord)
                val hit = list.firstOrNull { PhoneTools.norm(it).contains(wake) }
                if (hit == null) { scheduleRestart(150) { if (mode == Mode.WAKE) startRecognizer(true) }; return }
                // Lo que venga después de la palabra de activación es la orden.
                val n = PhoneTools.norm(hit)
                val rest = n.substring(n.indexOf(wake) + wake.length).trim()
                if (rest.length < 2) listenForCommand() else process(rest)
            }
            Mode.COMMAND -> {
                if (best.isEmpty()) onError(SpeechRecognizer.ERROR_NO_MATCH) else process(best)
            }
            else -> {}
        }
    }

    override fun onError(error: Int) {
        when (mode) {
            Mode.WAKE -> {
                // Silencio o ruido: reiniciar. Si el micro está ocupado (p. ej. grabando vídeo), esperar más.
                val delay = when (error) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_AUDIO,
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 5000L
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> 3000L
                    else -> 300L
                }
                scheduleRestart(delay) { if (mode == Mode.WAKE) startRecognizer(true) }
            }
            Mode.COMMAND -> {
                EventLog.add("No te he entendido (error $error)")
                mode = Mode.IDLE
                listenAfterSpeaking = false
                speak("No te he entendido.")
            }
            else -> {}
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ---------- Texto a voz ----------

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts?.language = Locale("es", "ES")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { if (mode == Mode.SPEAKING) afterSpeaking() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { if (mode == Mode.SPEAKING) afterSpeaking() } }
        })
        ttsReady = true
    }

    // ---------- Utilidades ----------

    private fun scheduleRestart(ms: Long, block: () -> Unit) {
        cancelRestart()
        restartRunnable = Runnable(block).also { main.postDelayed(it, ms) }
    }

    private fun cancelRestart() {
        restartRunnable?.let { main.removeCallbacks(it) }
        restartRunnable = null
    }

    private fun beep() = runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }

    private fun setStatus(s: String) {
        EventLog.setStatus(s)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s))
    }

    private fun shutdown() {
        running = false
        cancelRestart()
        job?.cancel()
        stopRecognizer()
        tts?.stop()
        mode = Mode.IDLE
        EventLog.setStatus("Detenido")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        cancelRestart()
        stopRecognizer()
        tts?.shutdown()
        tone?.release()
        scope.cancel()
        EventLog.setStatus("Detenido")
        super.onDestroy()
    }

    private fun startInForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Asistente activo", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Notificación permanente mientras el asistente funciona en segundo plano"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val talk = PendingIntent.getService(this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_LISTEN), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Asistente IA")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "🎤 Hablar", talk)
            .addAction(0, "Detener", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_LISTEN = "com.melvin.asistenteia.LISTEN"
        const val ACTION_STOP = "com.melvin.asistenteia.STOP"
        const val ACTION_TEXT = "com.melvin.asistenteia.TEXT"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL = "assistant"
        private const val NOTIF_ID = 7

        @Volatile
        var running = false
            private set

        fun start(ctx: Context, action: String? = null, text: String? = null) {
            val i = Intent(ctx, AssistantService::class.java).setAction(action)
            if (text != null) i.putExtra(EXTRA_TEXT, text)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) = start(ctx, ACTION_STOP)
    }
}

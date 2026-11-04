package ir.smmammad.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SpeechWakeService : Service(), TextToSpeech.OnInitListener {
    private val channelId = "smmammad_voice_assistant"
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ready = false
    private var awaitingCommand = false
    private var listening = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DastyarEsMammad:VoiceWake")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(41, buildNotification())
        if (SpeechRecognizer.isRecognitionAvailable(this) && recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { speech ->
                speech.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) { listening = true }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { listening = false }
                    override fun onError(error: Int) {
                        listening = false
                        handler.postDelayed({ startListeningSafely() }, 1400)
                    }
                    override fun onResults(results: android.os.Bundle?) {
                        listening = false
                        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty().trim()
                        processSpeech(spoken)
                    }
                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
            }
            if (ready) startListeningSafely()
        }
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val fa = tts?.setLanguage(Locale("fa", "IR"))
            if (fa == TextToSpeech.LANG_MISSING_DATA || fa == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale("fa")
            }
            ready = true
            startListeningSafely()
        }
    }

    private fun processSpeech(text: String) {
        if (text.isBlank()) {
            handler.postDelayed({ startListeningSafely() }, 500)
            return
        }
        val normalized = text.lowercase(Locale.ROOT)
        val wakePhrases = listOf("مشتی چه خبر", "مشتی", "دستیار اس ممد", "اس ممد")
        val wake = wakePhrases.firstOrNull { normalized.contains(it) }
        if (!awaitingCommand && wake == null) {
            handler.postDelayed({ startListeningSafely() }, 400)
            return
        }
        if (!awaitingCommand && wake != null) {
            val command = normalized.substringAfter(wake, "").trim()
            if (command.isNotBlank()) {
                handleCommand(command)
            } else {
                speak("جانم، گوش می‌کنم. بگو چه کاری انجام بدم.")
                handler.postDelayed({ awaitingCommand = true; startListeningSafely() }, 1600)
            }
            return
        }
        awaitingCommand = false
        handleCommand(text)
    }

    private fun handleCommand(command: String) {
        awaitingCommand = false
        val key = SecureApiKeyStore.read(this)
        if (key.isNullOrBlank()) {
            speak("اول کلید رایگان هوش مصنوعی را از تنظیمات برنامه وارد کن.")
            handler.postDelayed({ startListeningSafely() }, 3000)
            return
        }
        speak("چشم، دارم بررسی می‌کنم.")
        serviceScope.launch {
            val answer = try {
                askOpenRouter(key, emptyList(), command)
            } catch (e: Exception) {
                "ارتباط برقرار نشد. اینترنت و کلید اتصال را بررسی کن."
            }
            speak(answer)
            handler.postDelayed({ startListeningSafely() }, 1200)
        }
    }

    private fun startListeningSafely() {
        if (!ready || listening || recognizer == null) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            handler.postDelayed({ startListeningSafely() }, 2000)
        }
    }

    private fun speak(message: String) {
        tts?.speak(message.take(3500), TextToSpeech.QUEUE_FLUSH, null, "smmammad_reply")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "دستیار صوتی اس ممد", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, channelId) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("دستیار اس ممد فعال است")
            .setContentText("برای بیدار کردن بگو: مشتی چه خبر")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

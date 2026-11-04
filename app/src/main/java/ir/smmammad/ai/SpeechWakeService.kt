package ir.smmammad.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.provider.Settings
import android.provider.MediaStore
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
                speakAndListen("جانم، گوش می‌کنم. بگو چه کاری انجام بدم.", awaitCommand = true)
            }
            return
        }
        awaitingCommand = false
        handleCommand(text)
    }

    private fun handleCommand(command: String) {
        awaitingCommand = false
        if (tryHandleLocalCommand(command)) return
        val key = SecureApiKeyStore.read(this)
        if (key.isNullOrBlank()) {
            speakAndListen("اول کلید رایگان هوش مصنوعی را از تنظیمات برنامه وارد کن.")
            return
        }
        speak("چشم، دارم بررسی می‌کنم.")
        serviceScope.launch {
            val answer = try {
                askOpenRouter(key, emptyList(), command)
            } catch (e: Exception) {
                "ارتباط برقرار نشد. اینترنت و کلید اتصال را بررسی کن."
            }
            speakAndListen(answer)
        }
    }

    /**
     * Executes a small allowlist of safe, explicit phone navigation commands locally.
     * No accessibility clicks or hidden actions are performed.
     */
    private fun tryHandleLocalCommand(rawCommand: String): Boolean {
        val command = rawCommand.lowercase(Locale.ROOT)
        val intent: Intent? = when {
            command.contains("تنظیمات وای فای") || command.contains("تنظیمات wifi") ->
                Intent(Settings.ACTION_WIFI_SETTINGS)
            command.contains("تنظیمات بلوتوث") ->
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            command.contains("تنظیمات گوشی") || command == "تنظیمات" || command.contains("باز کن تنظیمات") ->
                Intent(Settings.ACTION_SETTINGS)
            command.contains("دوربین") || command.contains("عکس بگیر") ->
                Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            else -> null
        }
        if (intent != null) {
            return try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                speakAndListen("چشم، بازش کردم.")
                true
            } catch (_: Exception) {
                speakAndListen("نتونستم این بخش رو باز کنم.")
                true
            }
        }

        val packageNameToOpen = when {
            command.contains("واتساپ") || command.contains("whatsapp") -> "com.whatsapp"
            command.contains("تلگرام") || command.contains("telegram") -> "org.telegram.messenger"
            command.contains("اینستاگرام") || command.contains("instagram") -> "com.instagram.android"
            command.contains("کروم") || command.contains("chrome") -> "com.android.chrome"
            command.contains("یوتیوب") || command.contains("youtube") -> "com.google.android.youtube"
            else -> null
        } ?: return false

        return try {
            val launch = packageManager.getLaunchIntentForPackage(packageNameToOpen)
            if (launch == null) {
                speakAndListen("این برنامه روی گوشی نصب نیست.")
            } else {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
                speakAndListen("چشم، بازش کردم.")
            }
            true
        } catch (_: Exception) {
            speakAndListen("باز کردن برنامه انجام نشد.")
            true
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

    private fun speakAndListen(message: String, awaitCommand: Boolean = false) {
        val engine = tts
        if (engine == null) {
            if (awaitCommand) this.awaitingCommand = true
            handler.postDelayed({ startListeningSafely() }, 500)
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                handler.post {
                    if (awaitCommand) this@SpeechWakeService.awaitingCommand = true
                    startListeningSafely()
                }
            }

            override fun onError(utteranceId: String?) {
                handler.post {
                    if (awaitCommand) this@SpeechWakeService.awaitingCommand = true
                    startListeningSafely()
                }
            }
        })
        val result = engine.speak(
            message.take(3500),
            TextToSpeech.QUEUE_FLUSH,
            null,
            "smmammad_reply"
        )
        if (result == TextToSpeech.ERROR) {
            if (awaitCommand) this.awaitingCommand = true
            handler.postDelayed({ startListeningSafely() }, 700)
        }
    }

    private fun speak(message: String) {
        tts?.speak(message.take(3500), TextToSpeech.QUEUE_FLUSH, null, "smmammad_ack")
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

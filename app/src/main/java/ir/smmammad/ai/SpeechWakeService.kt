package ir.smmammad.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import android.provider.MediaStore
import android.net.Uri
import java.net.URLEncoder
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
    private var serviceActive = false
    private var awaitingCommand = false
    private var listening = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DastyarEsMammad:VoiceWake")
        wakeLock?.setReferenceCounted(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(41, buildNotification())
        serviceActive = true
        if (wakeLock?.isHeld != true) wakeLock?.acquire()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (recognizer == null) {
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
        }
        // Speech recognition must not depend on TTS initialization; some phones have no
        // Persian TTS voice installed even though speech recognition is available.
        startListeningSafely()
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val fa = tts?.setLanguage(Locale("fa", "IR"))
            if (fa == TextToSpeech.LANG_MISSING_DATA || fa == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale("fa")
            }
        }
    }

    private fun processSpeech(text: String) {
        if (text.isBlank()) {
            handler.postDelayed({ startListeningSafely() }, 500)
            return
        }
        val normalized = text.lowercase(Locale.ROOT)
        val wakePhrases = listOf("مشتی چه خبر", "سلام مشتی", "مشتی", "سلام دستیار", "دستیار اس ممد", "اس ممد", "تسلا", "ادیسون")
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
                val detail = e.message?.takeIf { it.isNotBlank() } ?: "خطای نامشخص"
                "اتصال به هوش مصنوعی ناموفق بود: $detail"
            }
            speakAndListen(answer)
        }
    }

    /**
     * Executes a small allowlist of safe, explicit phone navigation commands locally.
     * No accessibility clicks or hidden actions are performed.
     */
    private fun tryHandleLocalCommand(rawCommand: String): Boolean {
        // Fast on-device commands adapted from the user's Dastyar AI Studio prototype.
        val parsed = VoiceCommandRouter.parse(rawCommand)
        if (parsed != null) {
            when (parsed.type) {
                "greeting" -> speakAndListen("سلام مشتی! چاکریم، بفرما در خدمتم.")
                "reply" -> speakAndListen(parsed.argument)
                "save_note" -> {
                    if (parsed.argument.isBlank()) {
                        speakAndListen("متن یادداشت رو بعد از دستور بگو.")
                    } else {
                        VoiceCommandRouter.saveNote(this, parsed.argument)
                        speakAndListen("یادداشتت روی گوشی ذخیره شد.")
                    }
                }
                "list_notes" -> {
                    val notes = VoiceCommandRouter.readNotes(this)
                    speakAndListen(if (notes.isBlank()) "هنوز یادداشتی ذخیره نکردی." else "یادداشت‌های ذخیره‌شده: $notes")
                }
                else -> return false
            }
            return true
        }
        val command = normalizeDigits(rawCommand.lowercase(Locale.ROOT))

        // Safe, explicit system intents: the user confirms the final call/message/alarm in Android UI.
        val phoneNumber = Regex("(?:\\+?\\d[\\d \\-()]{7,}\\d)").find(command)?.value
            ?.replace(Regex("[^+0-9]"), "")
        if ((command.contains("تماس") || command.contains("زنگ بزن")) && phoneNumber != null) {
            return launchExternal(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")), "صفحه تماس باز شد؛ برای برقراری تماس خودت تأیید کن.")
        }

        if ((command.contains("پیامک") || command.contains("اس ام اس") || command.contains("sms")) && phoneNumber != null) {
            val body = command.substringAfter("متن", "").trim()
            val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phoneNumber")).apply {
                if (body.isNotBlank()) putExtra("sms_body", body)
            }
            return launchExternal(sms, "صفحه پیامک باز شد؛ متن و گیرنده را بررسی و خودت ارسال کن.")
        }

        val timerMinutes = Regex("(\\d+)\\s*(?:دقیقه|دقيقه)").find(command)?.groupValues?.get(1)?.toIntOrNull()
        if ((command.contains("تایمر") || command.contains("زمان‌سنج")) && timerMinutes != null && timerMinutes > 0) {
            return launchExternal(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, timerMinutes * 60).putExtra(AlarmClock.EXTRA_MESSAGE, "دستیار اس ممد"), "تنظیم تایمر را در ساعت گوشی تأیید کن.")
        }

        val alarmHour = Regex("(?:ساعت\\s*)(\\d{1,2})(?::(\\d{2}))?").find(command)
        if ((command.contains("آلارم") || command.contains("زنگ") || command.contains("بیدارم")) && alarmHour != null) {
            val hour = alarmHour.groupValues[1].toIntOrNull()
            val minute = alarmHour.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            if (hour != null && hour in 0..23 && minute in 0..59) {
                return launchExternal(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "دستیار اس ممد"), "صفحه تنظیم زنگ هشدار باز شد؛ ساعت را تأیید کن.")
            }
        }

        if (command.startsWith("جستجو ") || command.startsWith("بگرد ") || command.startsWith("در اینترنت ")) {
            val query = command.removePrefix("جستجو ").removePrefix("بگرد ").removePrefix("در اینترنت ").trim()
            if (query.isNotBlank()) {
                return launchExternal(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", query), "نتایج جستجو باز شد.")
            }
        }

        if (command.contains("موزیک") || command.contains("آهنگ") || command.contains("آهنگ پخش")) {
            val query = URLEncoder.encode(rawCommand, "UTF-8")
            return launchExternal(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$query")), "نتایج موسیقی باز شد.")
        }

        val intent: Intent? = when {
            command.contains("ماشین حساب") || command.contains("حسابگر") ->
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR)
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
        if (intent != null) return launchExternal(intent, "چشم، بازش کردم.")

        val packageNameToOpen = when {
            command.contains("جیمیل") || command.contains("gmail") -> "com.google.android.gm"
            command.contains("نقشه") || command.contains("مسیریاب") -> "com.google.android.apps.maps"
            command.contains("گالری") -> "com.google.android.gallery3d"
            command.contains("فایل") || command.contains("مدیریت فایل") -> "com.google.android.documentsui"
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

    private fun launchExternal(intent: Intent, successMessage: String): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            speakAndListen(successMessage)
            true
        } catch (_: Exception) {
            speakAndListen("این قابلیت یا برنامه روی گوشی در دسترس نیست.")
            true
        }
    }

    private fun normalizeDigits(value: String): String {
        val persian = "۰۱۲۳۴۵۶۷۸۹"
        val arabic = "٠١٢٣٤٥٦٧٨٩"
        return buildString(value.length) {
            value.forEach { ch ->
                val p = persian.indexOf(ch)
                val a = arabic.indexOf(ch)
                append(when {
                    p >= 0 -> ('0'.code + p).toChar()
                    a >= 0 -> ('0'.code + a).toChar()
                    else -> ch
                })
            }
        }
    }

    private fun startListeningSafely() {
        if (!serviceActive || listening || recognizer == null) return
        try {
            // Mark the request active before calling the platform API to avoid duplicate
            // startListening calls while waiting for onReadyForSpeech().
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            listening = true
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            listening = false
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
        serviceActive = false
        listening = false
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

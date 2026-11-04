package ir.smmammad.ai

import android.content.Context
import android.app.Activity
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AssistantScreen() }
    }
}

data class ChatMessage(val text: String, val fromUser: Boolean)

@Composable
fun AssistantScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf(SecureApiKeyStore.read(context).orEmpty()) }
    var keyDraft by remember { mutableStateOf(apiKey) }
    var showSettings by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var voiceActive by remember { mutableStateOf(false) }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val intent = Intent(context, SpeechWakeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            voiceActive = true
        }
    }
    val messages = remember {
        mutableStateListOf<ChatMessage>().apply {
            addAll(ChatHistoryStore.load(context).takeLast(80))
            if (isEmpty()) add(ChatMessage("سلام! بپرس.", false))
        }
    }
    LaunchedEffect(messages.size) {
        ChatHistoryStore.save(context, messages.toList())
    }
    val voiceInputLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val recognized = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!recognized.isNullOrBlank()) {
                prompt = listOf(prompt, recognized).filter { it.isNotBlank() }.joinToString(" ")
            }
        }
    }
    val launchVoiceInput: () -> Unit = {
        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "به فارسی صحبت کن")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            voiceInputLauncher.launch(speechIntent)
        } catch (_: Exception) {
            messages.add(ChatMessage("سرویس تشخیص گفتار روی گوشی در دسترس نیست.", false))
        }
    }
    val voicePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchVoiceInput()
        else messages.add(ChatMessage("برای تبدیل گفتار به متن، مجوز میکروفون لازم است.", false))
    }
    val replyTts = remember(context) { TextToSpeech(context, null) }
    DisposableEffect(replyTts) {
        onDispose {
            replyTts.stop()
            replyTts.shutdown()
        }
    }
    val navy = Color(0xFF081526)
    val panel = Color(0xFF12263D)

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF32D6C2), background = navy, surface = panel)) {
        Column(Modifier.fillMaxSize().background(navy).padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("اس‌ممد", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = {
                    if (voiceActive) {
                        context.stopService(Intent(context, SpeechWakeService::class.java))
                        voiceActive = false
                    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        val intent = Intent(context, SpeechWakeService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
                        voiceActive = true
                    } else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) {
                    Text(if (voiceActive) "■" else "م", color = if (voiceActive) Color(0xFFFFC66D) else Color(0xFF32D6C2), fontSize = 18.sp)
                }
                IconButton(onClick = { keyDraft = apiKey; showSettings = true }) {
                    Text("⚙", color = Color(0xFF32D6C2), fontSize = 18.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages) { msg ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (msg.fromUser) Arrangement.Start else Arrangement.End
                    ) {
                        Surface(
                            color = if (msg.fromUser) Color(0xFF174B59) else panel,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.widthIn(max = 310.dp)
                        ) {
                            Column(Modifier.padding(13.dp)) {
                                Text(msg.text, color = Color.White, fontSize = 14.sp)
                                if (!msg.fromUser) {
                                    TextButton(
                                        contentPadding = PaddingValues(0.dp),
                                        onClick = {
                                            replyTts.language = Locale("fa", "IR")
                                            replyTts.speak(
                                                msg.text.take(3500),
                                                TextToSpeech.QUEUE_FLUSH,
                                                null,
                                                "smmammad_chat_reply"
                                            )
                                        }
                                    ) {
                                        Text("🔊 پخش پاسخ", color = Color(0xFF32D6C2), fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                if (loading) {
                    item {
                        Text("…", color = Color(0xFF32D6C2), fontSize = 12.sp)
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(panel, RoundedCornerShape(18.dp)).padding(8.dp)
            ) {
                BasicTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.weight(1f).padding(10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    decorationBox = { inner ->
                        if (prompt.isEmpty()) Text("پیام…", color = Color.Gray)
                        inner()
                    }
                )
                IconButton(
                    enabled = !loading,
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            launchVoiceInput()
                        } else {
                            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                ) {
                    Text("🎙", color = Color(0xFF32D6C2), fontSize = 19.sp)
                }
                IconButton(
                    enabled = !loading,
                    onClick = {
                        val q = prompt.trim()
                        if (q.isNotEmpty()) {
                            messages.add(ChatMessage(q, true))
                            prompt = ""
                            if (apiKey.isBlank()) {
                                messages.add(ChatMessage("برای فعال شدن چت‌بات آنلاین، ابتدا کلید رایگان OpenRouter را از تنظیمات وارد کن.", false))
                                keyDraft = ""
                                showSettings = true
                            } else {
                                loading = true
                                scope.launch {
                                    try {
                                        val history = messages.dropLast(1).takeLast(16)
                                        val answer = askOpenRouter(apiKey, history, q)
                                        messages.add(ChatMessage(answer, false))
                                    } catch (e: Exception) {
                                        messages.add(ChatMessage("ارتباط با سرویس برقرار نشد: ${e.message ?: "خطای نامشخص"}. اینترنت و کلید API را بررسی کن و دوباره تلاش کن.", false))
                                    } finally {
                                        loading = false
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Text("➤", color = Color(0xFF32D6C2), fontSize = 20.sp)
                }
            }
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("تنظیمات") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("کلید رایگان OpenRouter", fontSize = 13.sp)
                        OutlinedTextField(
                            value = keyDraft,
                            onValueChange = { keyDraft = it },
                            label = { Text("API Key") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        Text("openrouter.ai/keys", color = Color(0xFF32D6C2), fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val cleaned = keyDraft.trim()
                        if (cleaned.isNotEmpty()) {
                            try {
                                SecureApiKeyStore.save(context, cleaned)
                                apiKey = cleaned
                                messages.add(ChatMessage("کلید اتصال ذخیره شد. حالا می‌توانی سؤال بپرسی.", false))
                                showSettings = false
                            } catch (e: Exception) {
                                messages.add(ChatMessage("ذخیره امن کلید انجام نشد: ${e.message}", false))
                            }
                        } else {
                            SecureApiKeyStore.clear(context)
                            apiKey = ""
                            showSettings = false
                        }
                    }) { Text("ثبت") }
                },
                dismissButton = {
                    TextButton(onClick = { showSettings = false }) { Text("بستن") }
                }
            )
        }
    }
}

internal suspend fun askOpenRouter(apiKey: String, history: List<ChatMessage>, latest: String): String =
    withContext(Dispatchers.IO) {
        val messagesJson = JSONArray()
        messagesJson.put(JSONObject()
            .put("role", "system")
            .put("content", "تو دستیار فارسی‌زبان اس ممد هستی؛ در تعمیر برد و لوازم خانگی کوچک دقیق، مرحله‌به‌مرحله و ایمن راهنمایی کن. برای برق شهر همیشه هشدار قطع برق و رعایت ایمنی بده. اگر اطلاعات کافی نیست سؤال روشن‌کننده بپرس. پاسخ را فارسی و قابل فهم بنویس."))
        history.filter { it.text.isNotBlank() }.forEach {
            messagesJson.put(JSONObject()
                .put("role", if (it.fromUser) "user" else "assistant")
                .put("content", it.text))
        }
        messagesJson.put(JSONObject().put("role", "user").put("content", latest))

        val payload = JSONObject()
            .put("model", "openrouter/free")
            .put("messages", messagesJson)
            .put("temperature", 0.4)
            .toString()

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Title", "Dastyar Es Mammad")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "خطای HTTP ${response.code}")
            }
            val root = JSONObject(body)
            root.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content")
                .ifBlank { "پاسخی از مدل دریافت نشد؛ دوباره امتحان کن." }
        }
    }

internal object SecureApiKeyStore {
    private const val PREFS = "assistant_secure_settings"
    private const val KEY_NAME = "openrouter_key"
    private const val ALIAS = "dastyar_openrouter_aes"

    fun save(context: Context, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_NAME, encoded).apply()
    }

    fun read(context: Context): String? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, null) ?: return null
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 12)
            val encrypted = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_NAME).apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

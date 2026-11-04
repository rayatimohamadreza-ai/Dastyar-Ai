package ir.smmammad.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AssistantScreen() }
    }
}

data class ChatMessage(val text: String, val fromUser: Boolean)

@Composable
fun AssistantScreen() {
    var prompt by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf(ChatMessage("سلام مشتی! من دستیار اس ممد هستم. در حالت آفلاین آماده‌ام. برای پاسخ هوشمند مبتنی بر مدل زبانی، باید مدل محلی سازگار روی گوشی بارگذاری شود.", false)) }
    val navy = Color(0xFF081526)
    val panel = Color(0xFF12263D)
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF32D6C2), background = navy, surface = panel)) {
        Column(Modifier.fillMaxSize().background(navy).padding(16.dp)) {
            Text("دستیار اس ممد", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("هوش مصنوعی محلی • بدون اتصال به سرویس ابری", color = Color(0xFF8FA9C2), fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(messages) { msg ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.fromUser) Arrangement.Start else Arrangement.End) {
                        Surface(color = if (msg.fromUser) Color(0xFF174B59) else panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 310.dp)) {
                            Text(msg.text, color = Color.White, modifier = Modifier.padding(13.dp), fontSize = 14.sp)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(panel, RoundedCornerShape(18.dp)).padding(8.dp)) {
                BasicTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.weight(1f).padding(10.dp), textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp), decorationBox = { inner -> if (prompt.isEmpty()) Text("پیامت را بنویس...", color = Color.Gray); inner() })
                IconButton(onClick = {
                    val q = prompt.trim()
                    if (q.isNotEmpty()) {
                        messages.add(ChatMessage(q, true))
                        messages.add(ChatMessage(offlineReply(q), false))
                        prompt = ""
                    }
                }) { Icon(Icons.Default.Send, contentDescription = "ارسال", tint = Color(0xFF32D6C2)) }
            }
            Spacer(Modifier.height(8.dp))
            Text("نسخه آزمایشی آفلاین؛ موتور مدل زبانی محلی هنوز به برنامه متصل نشده است.", color = Color(0xFFFFC66D), fontSize = 11.sp)
        }
    }
}

private fun offlineReply(input: String): String {
    val q = input.lowercase()
    return when {
        listOf("پنکه", "موتور", "خازن").any { q.contains(it) } ->
            "برای عیب‌یابی ایمن، ابتدا دستگاه را از برق جدا کن. محور موتور را از نظر گیرپاژ بررسی کن و ظرفیت خازن را با مولتی‌متر دارای حالت ظرفیت‌سنج اندازه بگیر. برای تشخیص دقیق، مدل دستگاه و علائم را بفرست. این پاسخ راهنمای ثابت آفلاین است، نه تحلیل مدل هوش مصنوعی."
        listOf("سلام", "درود").any { q.contains(it) } -> "سلام! بگو ببینم چه کاری از دستم برمیاد؟"
        else -> "این نسخه هنوز مدل زبانی هوش مصنوعی را در خود ندارد؛ فعلاً فقط رابط و پاسخ‌های پایه آفلاین فعال‌اند. مرحله بعد، افزودن موتور استنتاج محلی و مدل سازگار با سخت‌افزار گوشی است."
    }
}

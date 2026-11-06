package ir.smmammad.ai

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight native Persian voice-command router.
 * Adapted from the user's Dastyar AI Studio prototype and implemented in Kotlin.
 * All local commands run on-device without an API request.
 */
internal object VoiceCommandRouter {
    data class Action(val type: String, val argument: String = "")

    private val wakeWords = listOf(
        "مشتی چه خبر", "سلام مشتی", "مشتی",
        "سلام دستیار", "دستیار اس ممد", "اس ممد",
        "تسلا", "ادیسون"
    )

    fun removeWakeWord(raw: String): String {
        val text = normalize(raw)
        val word = wakeWords.firstOrNull { text.contains(normalize(it)) } ?: return text
        return text.replace(normalize(word), " ").trim()
    }

    fun parse(raw: String): Action? {
        val q = normalize(removeWakeWord(raw))
        if (q.isBlank()) return null

        if (q.startsWith("سلام") || q.startsWith("درود") || q == "مشتی") {
            return Action("greeting")
        }
        if (q.contains("چه خبر") || q.contains("اوضاع چطوره") || q.contains("حالت چطوره")) {
            return Action("greeting")
        }
        if (q.contains("ساعت چنده") || q.contains("ساعت چند است") || q == "ساعت") {
            val time = SimpleDateFormat("HH:mm", Locale("fa", "IR")).format(Date())
            return Action("reply", "الان ساعت $time است.")
        }
        if (q.contains("امروز چه روزیه") || q.contains("تاریخ امروز") || q.contains("امروز چندمه")) {
            val date = SimpleDateFormat("EEEE d MMMM yyyy", Locale("fa", "IR")).format(Date())
            return Action("reply", "امروز $date است.")
        }
        if (q.startsWith("یادداشت بنویس") || q.startsWith("یادداشت کن") || q.startsWith("ثبت یادداشت")) {
            val note = q.replaceFirst(Regex("^(یادداشت بنویس|یادداشت کن|ثبت یادداشت)\\s*"), "").trim()
            return Action("save_note", note)
        }
        if (q.contains("یادداشت ها") || q.contains("یادداشت‌ها") || q.contains("یادداشتام")) {
            return Action("list_notes")
        }
        return null
    }

    fun saveNote(context: Context, note: String) {
        val prefs = context.getSharedPreferences("assistant_local_notes", Context.MODE_PRIVATE)
        val old = prefs.getString("notes", "").orEmpty()
        val updated = (listOf(note) + old.split("\\n").filter { it.isNotBlank() }).take(50).joinToString("\\n")
        prefs.edit().putString("notes", updated).apply()
    }

    fun readNotes(context: Context): String =
        context.getSharedPreferences("assistant_local_notes", Context.MODE_PRIVATE)
            .getString("notes", "").orEmpty()

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('ي', 'ی')
        .replace('ك', 'ک')
        .replace('‌', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
}

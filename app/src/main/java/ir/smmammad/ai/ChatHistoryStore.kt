package ir.smmammad.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight local chat history. Stored only in this app's private preferences.
 * Keeps the most recent 80 messages to limit storage on older phones.
 */
internal object ChatHistoryStore {
    private const val PREFS = "assistant_chat_history"
    private const val KEY_MESSAGES = "messages"
    private const val MAX_MESSAGES = 80

    fun load(context: Context): List<ChatMessage> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MESSAGES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                val start = (array.length() - MAX_MESSAGES).coerceAtLeast(0)
                for (i in start until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val text = item.optString("text")
                    if (text.isNotBlank()) {
                        add(ChatMessage(text, item.optBoolean("fromUser", false)))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.takeLast(MAX_MESSAGES).forEach { message ->
            array.put(
                JSONObject()
                    .put("text", message.text.take(12000))
                    .put("fromUser", message.fromUser)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MESSAGES, array.toString())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_MESSAGES)
            .apply()
    }
}

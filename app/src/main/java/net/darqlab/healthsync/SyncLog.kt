package net.darqlab.healthsync

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class LogEntry(
    val timestamp: String,
    val message: String,
    val isError: Boolean = false
)

object SyncLog {

    private const val PREFS_NAME = "sync_log"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 50

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun add(context: Context, message: String, isError: Boolean = false) {
        val entries = getAll(context).toMutableList()
        entries.add(0, LogEntry(
            timestamp = timeFormatter.format(Instant.now()),
            message = message,
            isError = isError
        ))
        if (entries.size > MAX_ENTRIES) {
            entries.subList(MAX_ENTRIES, entries.size).clear()
        }
        save(context, entries)
    }

    fun getAll(context: Context): List<LogEntry> {
        val json = prefs(context).getString(KEY_ENTRIES, "[]") ?: "[]"
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            LogEntry(
                timestamp = obj.getString("timestamp"),
                message = obj.getString("message"),
                isError = obj.optBoolean("isError", false)
            )
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().putString(KEY_ENTRIES, "[]").apply()
    }

    private fun save(context: Context, entries: List<LogEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("timestamp", entry.timestamp)
                put("message", entry.message)
                put("isError", entry.isError)
            })
        }
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }
}

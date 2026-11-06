package com.nat.autoanswer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class VoiceMessage(
    val id: String,
    val number: String,
    val timestamp: Long,
    val durationMs: Long,
    val fileName: String,
    val listened: Boolean,
    val starred: Boolean = false,
)

/** Tiny file-backed store: audio files in filesDir/messages, metadata in an index JSON. */
class MessageStore(private val context: Context) {
    private val dir = File(context.filesDir, "messages").apply { mkdirs() }
    private val index = File(dir, "index.json")

    fun audioFile(name: String) = File(dir, name)

    fun newAudioFile(): File = File(dir, "msg_${System.currentTimeMillis()}.m4a")

    @Synchronized
    fun all(): List<VoiceMessage> {
        if (!index.exists()) return emptyList()
        return try {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                VoiceMessage(
                    id = o.getString("id"),
                    number = o.optString("number", "Unknown"),
                    timestamp = o.getLong("timestamp"),
                    durationMs = o.getLong("durationMs"),
                    fileName = o.getString("fileName"),
                    listened = o.optBoolean("listened", false),
                    starred = o.optBoolean("starred", false),
                )
            }.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(m: VoiceMessage) = save(all() + m)

    @Synchronized
    fun setNumber(id: String, number: String) =
        save(all().map { if (it.id == id) it.copy(number = number) else it })

    @Synchronized
    fun markListened(id: String) = save(all().map { if (it.id == id) it.copy(listened = true) else it })

    @Synchronized
    fun toggleStar(id: String) = save(all().map { if (it.id == id) it.copy(starred = !it.starred) else it })

    @Synchronized
    fun delete(id: String) {
        val list = all()
        list.firstOrNull { it.id == id }?.let { audioFile(it.fileName).delete() }
        save(list.filterNot { it.id == id })
    }

    private fun save(list: List<VoiceMessage>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("number", it.number)
                    .put("timestamp", it.timestamp)
                    .put("durationMs", it.durationMs)
                    .put("fileName", it.fileName)
                    .put("listened", it.listened)
                    .put("starred", it.starred)
            )
        }
        index.writeText(arr.toString())
    }
}

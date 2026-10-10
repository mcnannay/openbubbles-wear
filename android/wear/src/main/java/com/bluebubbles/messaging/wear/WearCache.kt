package com.bluebubbles.messaging.wear

import android.content.Context
import com.bluebubbles.messaging.wear.protocol.WearCodec
import com.bluebubbles.messaging.wear.protocol.WearCommandDto
import com.bluebubbles.messaging.wear.protocol.WearInboxDto
import com.bluebubbles.messaging.wear.protocol.WearProtocol
import com.bluebubbles.messaging.wear.protocol.WearThreadDto
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class WearOutboxEntry(
    val command: WearCommandDto,
    val status: String = "pending",
    val messageId: String? = null,
    val errorMessage: String? = null,
)

/** Small bounded watch-side cache. Keeps the latest inbox, 30 thread snapshots, avatars, and outbox. */
class WearCache(context: Context) {
    private val prefs = context.getSharedPreferences("openbubbles_wear_cache", Context.MODE_PRIVATE)
    private val avatarDir = File(context.filesDir, "wear_avatars").apply { mkdirs() }

    fun loadInbox(): WearInboxDto = prefs.getString("inbox", null)
        ?.let { runCatching { WearCodec.inboxFromJson(it) }.getOrNull() }
        ?: WearInboxDto()

    fun saveInbox(value: WearInboxDto) {
        prefs.edit().putString("inbox", WearCodec.toJson(value)).apply()
    }

    fun loadThreads(): Map<String, WearThreadDto> {
        val root = runCatching { JSONObject(prefs.getString("threads", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val result = linkedMapOf<String, WearThreadDto>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val json = root.optString(id)
            val thread = runCatching { WearCodec.threadFromJson(json) }.getOrNull() ?: continue
            result[id] = thread
        }
        return result
    }

    fun saveThread(value: WearThreadDto) {
        val chatId = value.chat?.id ?: return
        val root = runCatching { JSONObject(prefs.getString("threads", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val order = runCatching { JSONArray(prefs.getString("thread_order", "[]") ?: "[]") }.getOrDefault(JSONArray())

        root.put(chatId, WearCodec.toJson(value))
        val ids = mutableListOf<String>()
        for (i in 0 until order.length()) {
            val id = order.optString(i)
            if (id.isNotBlank() && id != chatId) ids += id
        }
        ids.add(0, chatId)
        while (ids.size > 30) {
            val removed = ids.removeLast()
            root.remove(removed)
        }

        val newOrder = JSONArray()
        ids.forEach { id -> newOrder.put(id) }
        prefs.edit()
            .putString("threads", root.toString())
            .putString("thread_order", newOrder.toString())
            .apply()
    }

    fun saveAvatar(chatId: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        runCatching { avatarFile(chatId).writeBytes(bytes) }
    }

    fun loadAvatar(chatId: String): ByteArray? = runCatching {
        avatarFile(chatId).takeIf { it.isFile }?.readBytes()
    }.getOrNull()

    fun pruneAvatars(keepChatIds: Set<String>) {
        val keepFiles = keepChatIds.map { avatarFile(it).name }.toSet()
        avatarDir.listFiles()?.forEach { file ->
            if (file.name !in keepFiles) runCatching { file.delete() }
        }
    }

    fun loadOutbox(): List<WearOutboxEntry> {
        val array = runCatching { JSONArray(prefs.getString("outbox", "[]") ?: "[]") }.getOrDefault(JSONArray())
        val result = mutableListOf<WearOutboxEntry>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val command = runCatching { WearCodec.commandFromJson(row.getString("command")) }.getOrNull() ?: continue
            result += WearOutboxEntry(
                command = command,
                status = row.optString("status", "pending"),
                messageId = row.optString("messageId").ifBlank { null },
                errorMessage = row.optString("errorMessage").ifBlank { null },
            )
        }
        return result
    }

    fun putOutbox(entry: WearOutboxEntry) {
        val rows = loadOutbox().toMutableList()
        val id = entry.command.commandId
        val index = rows.indexOfFirst { it.command.commandId == id }
        if (index >= 0) rows[index] = entry else rows += entry
        saveOutbox(rows)
    }

    fun removeOutbox(commandId: String) {
        saveOutbox(loadOutbox().filterNot { it.command.commandId == commandId })
    }

    private fun avatarFile(chatId: String): File =
        File(avatarDir, "${WearProtocol.stablePathKey(chatId)}.img")

    private fun saveOutbox(entries: List<WearOutboxEntry>) {
        val array = JSONArray()
        entries.takeLast(100).forEach { entry ->
            array.put(
                JSONObject()
                    .put("command", WearCodec.toJson(entry.command))
                    .put("status", entry.status)
                    .put("messageId", entry.messageId ?: "")
                    .put("errorMessage", entry.errorMessage ?: ""),
            )
        }
        // Outbox durability matters more than throughput: persist before the command is sent.
        prefs.edit().putString("outbox", array.toString()).commit()
    }
}

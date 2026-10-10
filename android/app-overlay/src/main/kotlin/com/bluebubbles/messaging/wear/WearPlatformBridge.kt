package com.bluebubbles.messaging.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import com.bluebubbles.messaging.wear.protocol.WearAckDto
import com.bluebubbles.messaging.wear.protocol.WearAckStatus
import com.bluebubbles.messaging.wear.protocol.WearCodec
import com.bluebubbles.messaging.wear.protocol.WearProtocol
import com.bluebubbles.messaging.services.backend_ui_interop.DartWorkManager
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Native half of the Flutter <-> Wear Data Layer bridge.
 *
 * Important reliability rule: every new command is persisted before it is handed to Dart.
 * It remains in the pending journal until Dart reports a terminal result. This makes normal
 * watch retries idempotent. Commands left in "executing" by a process crash are not replayed
 * until RECOVERY_MS has elapsed, which avoids the common duplicate-send crash window.
 */
object WearPlatformBridge {
    private const val CHANNEL = "com.openbubbles/wear"
    private const val PREFS = "openbubbles_wear"
    private const val PENDING = "pending_commands_v2"
    private const val COMPLETED = "completed_commands_v2"
    private const val MAX_COMPLETED = 200
    private const val RECOVERY_MS = 6 * 60 * 1000L
    private const val AVATAR_SIZE_PX = 96

    private val lock = Any()

    @Volatile private var channel: MethodChannel? = null
    @Volatile private var attachedEngine: FlutterEngine? = null
    @Volatile private var dartReady = false

    fun attach(context: Context, engine: FlutterEngine) {
        // A newly attached engine is not ready until Dart installs its MethodChannel
        // handler and explicitly calls startBridge. Carrying dartReady across engines can
        // otherwise drop a command into an engine whose Dart side has not started yet.
        synchronized(lock) { dartReady = false }
        attachedEngine = engine
        engine.addEngineLifecycleListener(object : FlutterEngine.EngineLifecycleListener {
            override fun onPreEngineRestart() {
                synchronized(lock) { dartReady = false }
            }

            override fun onEngineWillDestroy() {
                detach(engine)
            }
        })
        channel = MethodChannel(engine.dartExecutor.binaryMessenger, CHANNEL).also { methodChannel ->
            methodChannel.setMethodCallHandler { call, result ->
                when (call.method) {
                    "publishInbox" -> {
                        val json = call.argument<String>("json")
                            ?: return@setMethodCallHandler result.error("BAD_ARGS", "json missing", null)
                        publishData(context, WearProtocol.PATH_INBOX, json, result)
                    }
                    "publishThread" -> {
                        val chatId = call.argument<String>("chatId")
                            ?: return@setMethodCallHandler result.error("BAD_ARGS", "chatId missing", null)
                        val json = call.argument<String>("json")
                            ?: return@setMethodCallHandler result.error("BAD_ARGS", "json missing", null)
                        publishData(context, WearProtocol.threadPath(chatId), json, result)
                    }
                    "publishState" -> {
                        val json = call.argument<String>("json")
                            ?: return@setMethodCallHandler result.error("BAD_ARGS", "json missing", null)
                        publishData(context, WearProtocol.PATH_STATE, json, result)
                    }
                    "startBridge" -> {
                        val commands = synchronized(lock) {
                            dartReady = true
                            commandsReadyForDispatch(context)
                        }
                        result.success(commands)
                    }
                    "completeCommand" -> {
                        val commandId = call.argument<String>("commandId")
                            ?: return@setMethodCallHandler result.error("BAD_ARGS", "commandId missing", null)
                        val status = call.argument<String>("status")
                            ?: return@setMethodCallHandler result.error("BAD_ARGS", "status missing", null)
                        val ack = WearAckDto(
                            commandId = commandId,
                            status = status,
                            messageId = call.argument<String>("messageId"),
                            errorCode = call.argument<String>("errorCode"),
                            errorMessage = call.argument<String>("errorMessage"),
                        )
                        completeCommand(context, ack, result)
                    }
                    else -> result.notImplemented()
                }
            }
        }
    }

    fun detach(engine: FlutterEngine) {
        if (attachedEngine === engine) {
            synchronized(lock) { dartReady = false }
            channel?.setMethodCallHandler(null)
            channel = null
            attachedEngine = null
        }
    }

    /**
     * Persist and (if Flutter is ready) dispatch a command.
     * Returns the immediate ACK to send to the watch.
     */
    fun receiveCommand(context: Context, json: String, sourceNodeId: String): WearAckDto {
        val command = runCatching { WearCodec.commandFromJson(json) }.getOrNull()
            ?: return WearAckDto(status = WearAckStatus.FAILED, errorCode = "BAD_COMMAND", errorMessage = "Malformed command JSON")
        val commandId = command.commandId
            ?: return WearAckDto(status = WearAckStatus.FAILED, errorCode = "BAD_COMMAND", errorMessage = "Missing commandId")

        var needsBackgroundWake = false
        synchronized(lock) {
            val previous = findCompleted(context, commandId)
            if (previous != null) {
                return WearAckDto(
                    commandId = commandId,
                    status = WearAckStatus.DUPLICATE,
                    messageId = previous.optString("messageId").ifBlank { null },
                    errorCode = "previous:${previous.optString("status")}",
                    errorMessage = previous.optString("errorMessage").ifBlank { null },
                )
            }

            val pending = readArray(context, PENDING)
            val existingIndex = findPendingIndex(pending, commandId)
            if (existingIndex >= 0) {
                // Already queued/executing. Do not dispatch it twice. If there is no
                // live Dart bridge, however, a repeat delivery is a useful chance to
                // wake the existing OpenBubbles background worker again.
                val entry = pending.getJSONObject(existingIndex)
                entry.put("sourceNodeId", sourceNodeId)
                writeArray(context, PENDING, pending)
                needsBackgroundWake = !dartReady || channel == null
            } else {
                val now = System.currentTimeMillis()
                val entry = JSONObject()
                    .put("commandId", commandId)
                    .put("json", json)
                    .put("sourceNodeId", sourceNodeId)
                    .put("state", if (dartReady && channel != null) "executing" else "queued")
                    .put("updatedAtMs", now)
                pending.put(entry)
                writeArray(context, PENDING, pending)

                if (dartReady && channel != null) {
                    channel?.invokeMethod("wearCommand", mapOf("json to json, "sourceNodeId" to sourceNodeId))
                } else {
                    needsBackgroundWake = true
                }
            }
        }

        if (needsBackgroundWake) {
            // Reuse OpenBubbles' existing expedited DartWorker. The worker engine is wired
            // to this bridge by the small upstream hook documented in INTEGRATION.md.
            DartWorkManager.createWorker(
                context.applicationContext,
                "wear-wake",
                hashMapOf<String, Any?>("commandId" to commandId),
            ) {}
        }

        return WearAckDto(commandId = commandId, status = WearAckStatus.ACCEPTED)
    }

    fun sendAck(context: Context, nodeId: String, ack: WearAckDto) {
        Wearable.getMessageClient(context).sendMessage(
            nodeId,
            WearProtocol.PATH_ACK,
            WearCodec.toJson(ack).toByteArray(Charsets.UTF_8),
        )
    }

    private fun completeCommand(context: Context, ack: WearAckDto, result: MethodChannel.Result) {
        val commandId = ack.commandId
        if (commandId.isNullOrBlank()) {
            result.error("BAD_ARGS", "commandId missing", null)
            return
        }

        val sourceNodeId: String?
        synchronized(lock) {
            val pending = readArray(context, PENDING)
            val index = findPendingIndex(pending, commandId)
            sourceNodeId = if (index >= 0) {
                val entry = pending.getJSONObject(index)
                val node = entry.optString("sourceNodeId").ifBlank { null }
                pending.remove(index)
                writeArray(context, PENDING, pending)
                node
            } else null

            rememberCompleted(context, ack)
        }

        if (sourceNodeId == null) {
            result.success(false)
            return
        }

        Wearable.getMessageClient(context).sendMessage(
            sourceNodeId,
            WearProtocol.PATH_ACK,
            WearCodec.toJson(ack).toByteArray(Charsets.UTF_8),
        ).addOnCompleteListener { result.success(it.isSuccessful) }
    }

    /** Called once Dart installs its handler. Returns only commands safe to dispatch now. */
    private fun commandsReadyForDispatch(context: Context): List<String> {
        val pending = readArray(context, PENDING)
        val now = System.currentTimeMillis()
        val ready = mutableListOf<String>()
        var changed = false

        for (i in 0 until pending.length()) {
            val entry = pending.getJSONObject(i)
            val state = entry.optString("state", "queued")
            val age = now - entry.optLong("updatedAtMs", 0L)
            if (state == "queued" || (state == "executing" && age >= RECOVERY_MS)) {
                entry.put("state", "executing")
                entry.put("updatedAtMs", now)
                val json = entry.optString("json")
                if (json.isNotBlank()) ready += json
                changed = true
            }
        }
        if (changed) writeArray(context, PENDING, pending)
        return ready
    }

    private fun publishData(context: Context, path: String, json: String, result: MethodChannel.Result) {
        val request = PutDataMapRequest.create(path).apply {
            dataMap.putString(WearProtocol.DATA_JSON, json)
            dataMap.putLong(wearProtocol.DATA_UPDATED_AT, System.currentTimeMillis())
            attachAvatarAssets(context, path, json, this)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
            .addOnSuccessListener { result.success(true) }
            .addOnFailureListener { result.error("WEAR_DATA", it.message, null) }
    }

    /**
     * Avatar paths are phone-local and meaningless on the watch. Attach small, deduplicated
     * Data Layer Assets to the same inbox/thread DataItem so the watch can cache the image.
     */
    private fun attachAvatarAssets(
        context: Context,
        path: String,
        json: String,
        request: PutDataMapRequest,
    ) {
        val chats = when {
            path == WearProtocol.PATH_INBOX ->
                runCatching { WearCodec.inboxFromJson(json).chats }.getOrDefault(emptyList())
            path.startsWith(WearProtocol.PATH_THREAD_PREFIX) ->
                listOfNotNull(runCatching { WearCodec.threadFromJson(json).chat }.getOrNull())
            else -> emptyList()
        }

        for (chat in chats) {
            val chatId = chat.id ?: continue
            val source = chat.avatarKey ?: continue
            val bytes = avatarThumbnail(context, source) ?: continue
            request.dataMap.putAsset(WearProtocol.avatarAssetKey(chatId), Asset.createFromBytes(bytes))
        }
    }

    private fun avatarThumbnail(context: Context, source: String): ByteArray? = runCatching {
        val bitmap = when {
            source.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(source))
                ?.use { BitmapFactory.decodeStream(it) }
            source.startsWith("file://") -> Uri.parse(source).path?.let(BitmapFactory::decodeFile)
            else -> BitmapFactory.decodeFile(source)
        } ?: return@runCatching null

        val thumbnail = ThumbnailUtils.extractThumbnail(
            bitmap,
            AVATAR_SIZE_PX,
            AVATAR_SIZE_PX,
            ThumbnailUtils.OPTIONS_RECYCLE_INPUT,
        ) ?: return@runCatching null

        ByteArrayOutputStream().use { output ->
            if (!thumbnail.compress(Bitmap.CompressFormat.PNG, 100, output)) return@runCatching null
            output.toByteArray()
        }
    }.getOrNull()

    private fun rememberCompleted(context: Context, ack: WearAckDto) {
        val completed = readArray(context, COMPLETED)
        val row = JSONObject()
            .put("commandId", ack.commandId)
            .put("status", ack.status)
            .put("messageId", ack.messageId ?: "")
            .put("errorCode", ack.errorCode ?: "")
            .put("errorMessage", ack.errorMessage ?: "")
            .put("completedAtMs", System.currentTimeMillis())
        completed.put(row)

        val trimmed = JSONArray()
        val start = (completed.length() - MAX_COMPLETED).coerceAtLeast(0)
        for (i in start until completed.length()) trimmed.put(completed.getJSONObject(i))
        writeArray(context, COMPLETED, trimmed)
    }

    private fun findCompleted(context: Context, commandId: String): JSONObject? {
        val completed = readArray(context, COMPLETED)
        for (i in completed.length() - 1 downTo 0) {
            val row = completed.getJSONObject(i)
            if (row.optString("commandId") == commandId) return row
        }
        return null
    }

    private fun findPendingIndex(array: JSONArray, commandId: String): Int {
        for (i in 0 until array.length()) {
            if (array.getJSONObject(i).optString("commandId") == commandId) return i
        }
        return -1
    }

    private fun readArray(context: Context, key: String): JSONArray {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
        return if (raw.isNullOrBlank()) JSONArray() else runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private fun writeArray(context: Context, key: String, u©nxe: JSONArray) {
        // commit() is intentional: command journaling must reach disk before ACKing receipt.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, value.toString()).commit()
    }
}

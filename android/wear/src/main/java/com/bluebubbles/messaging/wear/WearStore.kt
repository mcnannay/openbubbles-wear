package com.bluebubbles.messaging.wear

import android.content.Context
import com.bluebubbles.messaging.wear.protocol.WearAckDto
import com.bluebubbles.messaging.wear.protocol.WearCommandDto
import com.bluebubbles.messaging.wear.protocol.WearInboxDto
import com.bluebubbles.messaging.wear.protocol.WearMessageDto
import com.bluebubbles.messaging.wear.protocol.WearStateDto
import com.bluebubbles.messaging.wear.protocol.WearThreadDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

object WearStore {
    private var cache: WearCache? = null

    private val _inbox = MutableStateFlow(WearInboxDto())
    val inbox: StateFlow<WearInboxDto> = _inbox

    private val _threads = MutableStateFlow<Map<String, WearThreadDto>>(emptyMap())
    val threads: StateFlow<Map<String, WearThreadDto>> = _threads

    private val _state = MutableStateFlow(WearStateDto())
    val state: StateFlow<WearStateDto> = _state

    private val _outbox = MutableStateFlow<List<WearOutboxEntry>>(emptyList())
    val outbox: StateFlow<List<WearOutboxEntry>> = _outbox

    private val _phoneConnected = MutableStateFlow(false)
    val phoneConnected: StateFlow<Boolean> = _phoneConnected

    @Synchronized
    fun init(context: Context) {
        if (cache != null) return
        cache = WearCache(context.applicationContext).also {
            _inbox.value = it.loadInbox()
            _threads.value = it.loadThreads()
            _outbox.value = it.loadOutbox()
        }
    }

    fun setPhoneConnected(value: Boolean) { _phoneConnected.value = value }

    fun setInbox(value: WearInboxDto) {
        _inbox.value = value.copy(chats = value.chats.take(30))
        cache?.saveInbox(_inbox.value)
    }

    fun setState(value: WearStateDto) { _state.value = value }

    fun setThread(value: WearThreadDto) {
        val chatId = value.chat?.id ?: return
        val bounded = value.copy(messages = value.messages.takeLast(50))
        val map = _threads.value.toMutableMap()
        map[chatId] = bounded
        _threads.value = map
        cache?.saveThread(bounded)
        reconcileOutbox(chatId, bounded)
    }

    fun addOutgoing(command: WearCommandDto) {
        val entry = WearOutboxEntry(command = command)
        cache?.putOutbox(entry)
        _outbox.value = cache?.loadOutbox() ?: (_outbox.value + entry)
    }

    fun removeOutgoing(commandId: String) {
        cache?.removeOutbox(commandId)
        _outbox.value = cache?.loadOutbox() ?: _outbox.value.filterNot { it.command.commandId == commandId }
    }

    fun replaceOutgoing(oldCommandId: String, command: WearCommandDto) {
        removeOutgoing(oldCommandId)
        addOutgoing(command)
    }

    fun commandFor(commandId: String?): WearOutboxEntry? =
        commandId?.let { id -> _outbox.value.firstOrNull { it.command.commandId == id } }

    fun pendingCommands(): List<WearCommandDto> =
        _outbox.value.filter { it.status == "pending" }.map { it.command }

    /** Returns the affected outbox entry (if this ACK corresponds to an outgoing message). */
    fun onAck(ack: WearAckDto): WearOutboxEntry? {
        val id = ack.commandId ?: return null
        val existing = commandFor(id) ?: return null
        val previousStatus = ack.errorCode?.removePrefix("previous:")
        val nextStatus = when (ack.status) {
            "failed" -> "failed"
            "succeeded" -> "sent"
            "duplicate" -> if (previousStatus == "failed") "failed" else "sent"
            else -> "pending"
        }
        val updated = existing.copy(
            status = nextStatus,
            messageId = ack.messageId ?: existing.messageId,
            errorMessage = ack.errorMessage ?: existing.errorMessage,
        )
        cache?.putOutbox(updated)
        _outbox.value = cache?.loadOutbox() ?: _outbox.value.map { if (it.command.commandId == id) updated else it }
        return updated
    }

    fun displayMessages(chatId: String): List<WearMessageDto> {
        val base = _threads.value[chatId]?.messages.orEmpty().toMutableList()
        val outgoing = _outbox.value.filter { it.command.type == "sendText" && it.command.chatId == chatId }
        for (entry in outgoing) {
            val command = entry.command
            val id = entry.messageId
            if (id != null && base.any { it.id == id }) continue
            val issuedAt = command.issuedAtMs
            val alreadyMatched = base.any { message ->
                val timestamp = message.timestampMs
                message.isFromMe &&
                    message.text?.trim() == command.text?.trim() &&
                    timestamp != null && issuedAt != null &&
                    abs(timestamp - issuedAt) <= 120_000
            }
            if (alreadyMatched) continue
            base += WearMessageDto(
                id = "pending:${command.commandId}",
                chatId = chatId,
                text = command.text,
                timestampMs = command.issuedAtMs,
                isFromMe = true,
                status = entry.status,
                clientCommandId = command.commandId,
            )
        }
        return base.sortedBy { it.timestampMs ?: 0L }
    }

    private fun reconcileOutbox(chatId: String, thread: WearThreadDto) {
        val entries = _outbox.value.filter { it.command.type == "sendText" && it.command.chatId == chatId }
        var changed = false
        for (entry in entries) {
            val command = entry.command
            val matchedById = entry.messageId?.let { id -> thread.messages.any { it.id == id } } == true
            val issuedAt = command.issuedAtMs
            val matchedByContent = thread.messages.any { message ->
                val timestamp = message.timestampMs
                message.isFromMe &&
                    message.text?.trim() == command.text?.trim() &&
                    timestamp != null && issuedAt != null &&
                    abs(timestamp - issuedAt) <= 120_000
            }
            if (matchedById || matchedByContent) {
                val commandId = command.commandId ?: continue
                cache?.removeOutbox(commandId)
                changed = true
            }
        }
        if (changed) _outbox.value = cache?.loadOutbox() ?: _outbox.value
    }
}

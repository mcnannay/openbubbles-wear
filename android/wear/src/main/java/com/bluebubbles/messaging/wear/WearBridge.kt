package com.bluebubbles.messaging.wear

import android.content.Context
import com.bluebubbles.messaging.wear.protocol.WearCodec
import com.bluebubbles.messaging.wear.protocol.WearCommandDto
import com.bluebubbles.messaging.wear.protocol.WearCommandType
import com.bluebubbles.messaging.wear.protocol.WearProtocol
import com.google.android.gms.wearable.Wearable
import java.util.UUID

class WearBridge(private val context: Context) {
    init { WearStore.init(context) }

    fun requestInbox(): String = send(newCommand(WearCommandType.REQUEST_INBOX))

    fun requestThread(chatId: String): String = send(newCommand(WearCommandType.REQUEST_THREAD, chatId = chatId))

    fun sendText(chatId: String, text: String): String {
        val command = newCommand(WearCommandType.SEND_TEXT, chatId = chatId, text = text)
        WearStore.addOutgoing(command)
        return send(command)
    }

    fun markRead(chatId: String, messageId: String? = null): String =
        send(newCommand(WearCommandType.MARK_READ, chatId = chatId, messageId = messageId))

    fun react(chatId: String, messageId: String, reaction: String, partIndex: Int = 0): String =
        send(newCommand(WearCommandType.REACT, chatId = chatId, messageId = messageId, reaction = reaction, partIndex = partIndex))

    /** Retry uncertain delivery with the same UUID. Safe because the phone deduplicates commandId. */
    fun resendPending(command: WearCommandDto) { send(command) }

    /** A terminal failure is a new user action, so it gets a new UUID. */
    fun retryFailed(commandId: String): String? {
        val old = WearStore.commandFor(commandId)?.command ?: return null
        val replacement = old.copy(commandId = UUID.randomUUID().toString(), issuedAtMs = System.currentTimeMillis())
        WearStore.replaceOutgoing(commandId, replacement)
        return send(replacement)
    }

    private fun newCommand(
        type: String,
        chatId: String? = null,
        messageId: String? = null,
        text: String? = null,
        reaction: String? = null,
        partIndex: Int? = null,
    ) = WearCommandDto(
        commandId = UUID.randomUUID().toString(),
        type = type,
        issuedAtMs = System.currentTimeMillis(),
        chatId = chatId,
        messageId = messageId,
        text = text,
        reaction = reaction,
        partIndex = partIndex,
    )

    private fun send(command: WearCommandDto): String {
        val commandId = command.commandId ?: return ""
        val bytes = WearCodec.toJson(command).toByteArray(Charsets.UTF_8)
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            val target = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            if (target == null) {
                WearStore.setPhoneConnected(false)
                return@addOnSuccessListener
            }
            WearStore.setPhoneConnected(true)
            Wearable.getMessageClient(context)
                .sendMessage(target.id, WearProtocol.PATH_COMMAND, bytes)
                .addOnFailureListener { WearStore.setPhoneConnected(false) }
        }.addOnFailureListener { WearStore.setPhoneConnected(false) }
        return commandId
    }
}

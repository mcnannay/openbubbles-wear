package com.bluebubbles.messaging.wear

import android.util.Log
import com.bluebubbles.messaging.wear.protocol.WearAckDto
import com.bluebubbles.messaging.wear.protocol.WearAckStatus
import com.bluebubbles.messaging.wear.protocol.WearCodec
import com.bluebubbles.messaging.wear.protocol.WearProtocol
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class OpenBubblesWearListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearProtocol.PATH_COMMAND) return

        val json = event.data.toString(Charsets.UTF_8)
        val command = runCatching { WearCodec.commandFromJson(json) }.getOrNull()
        if (command == null) {
            Log.w(TAG, "malformed Wear command")
            WearPlatformBridge.sendAck(
                this,
                event.sourceNodeId,
                WearAckDto(status = WearAckStatus.FAILED, errorCode = "BAD_COMMAND", errorMessage = "Malformed command"),
            )
            return
        }
        if (command.protocolVersion != WearProtocol.VERSION || command.commandId.isNullOrBlank()) {
            Log.w(TAG, "rejected command type=${command.type} id=${command.commandId?.take(8) ?: "none"}")
            WearPlatformBridge.sendAck(
                this,
                event.sourceNodeId,
                WearAckDto(
                    commandId = command.commandId,
                    status = WearAckStatus.FAILED,
                    errorCode = "PROTOCOL",
                    errorMessage = "Unsupported protocol or missing commandId",
                ),
            )
            return
        }

        Log.d(TAG, "command type=${command.type} id=${command.commandId.take(8)}")
        val ack = WearPlatformBridge.receiveCommand(applicationContext, json, event.sourceNodeId)
        Log.d(TAG, "immediate ack status=${ack.status} id=${ack.commandId?.take(8) ?: "none"}")
        WearPlatformBridge.sendAck(this, event.sourceNodeId, ack)
    }

    companion object {
        private const val TAG = "OBWearPhone"
    }
}

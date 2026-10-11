package com.bluebubbles.messaging.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bluebubbles.messaging.wear.protocol.WearCommandDto
import com.bluebubbles.messaging.wear.protocol.WearCommandType
import java.util.UUID

/**
 * Debug-build-only hook used by the paired-device resilience script.
 *
 * It intentionally exposes only requestInbox, which is read-only. Mutating message commands are
 * never accepted here, so duplicate/Doze/background tests cannot accidentally send messages.
 */
class WearDebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REQUEST_INBOX) return
        val requestedId = intent.getStringExtra(EXTRA_COMMAND_ID)?.trim()
        val commandId = requestedId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        Log.d(TAG, "debug requestInbox id=${commandId.take(8)}")
        WearBridge(context.applicationContext).resendPending(
            WearCommandDto(
                commandId = commandId,
                type = WearCommandType.REQUEST_INBOX,
                issuedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    companion object {
        const val ACTION_REQUEST_INBOX = "com.openbubbles.wear.DEBUG_REQUEST_INBOX"
        const val EXTRA_COMMAND_ID = "commandId"
        private const val TAG = "OBWearWatch"
    }
}

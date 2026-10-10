package com.bluebubbles.messaging.wear

import com.bluebubbles.messaging.wear.protocol.WearCodec
import com.bluebubbles.messaging.wear.protocol.WearProtocol
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.WearableListenerService

class WearDataService : WearableListenerService() {
    override fun onCreate() {
        super.onCreate()
        WearStore.init(applicationContext)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        WearStore.setPhoneConnected(true)
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val path = event.dataItem.uri.path ?: return@forEach
            val json = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(WearProtocol.DATA_JSON) ?: return@forEach
            when {
                path == WearProtocol.PATH_INBOX ->
                    runCatching { WearCodec.inboxFromJson(json) }.getOrNull()?.let(WearStore::setInbox)
                path.startsWith(WearProtocol.PATH_THREAD_PREFIX) ->
                    runCatching { WearCodec.threadFromJson(json) }.getOrNull()?.let(WearStore::setThread)
                path == WearProtocol.PATH_STATE ->
                    runCatching { WearCodec.stateFromJson(json) }.getOrNull()?.let(WearStore::setState)
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearProtocol.PATH_ACK) return
        WearStore.setPhoneConnected(true)
        val ack = runCatching { WearCodec.ackFromJson(event.data.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        val entry = WearStore.onAck(ack)
        if (entry != null && (ack.status == "succeeded" || ack.status == "duplicate")) {
            entry.command.chatId?.let { WearBridge(applicationContext).requestThread(it) }
            WearBridge(applicationContext).requestInbox()
        }
    }

    override fun onPeerConnected(peer: Node) {
        super.onPeerConnected(peer)
        WearStore.setPhoneConnected(true)
        val bridge = WearBridge(applicationContext)
        WearStore.pendingCommands().forEach(bridge::resendPending)
        bridge.requestInbox()
    }

    override fun onPeerDisconnected(peer: Node) {
        super.onPeerDisconnected(peer)
        WearStore.setPhoneConnected(false)
    }
}

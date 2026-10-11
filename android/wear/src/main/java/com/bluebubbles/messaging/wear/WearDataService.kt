package com.bluebubbles.messaging.wear

import android.util.Log
import com.bluebubbles.messaging.wear.protocol.WearCodec
import com.bluebubbles.messaging.wear.protocol.WearProtocol
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

class WearDataService : WearableListenerService() {
    override fun onCreate() {
        super.onCreate()
        WearStore.init(applicationContext)
        Log.d(TAG, "listener created")
    }

    override fun onDataChanged(events: DataEventBuffer) {
        WearStore.setPhoneConnected(true)
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val path = event.dataItem.uri.path ?: return@forEach
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            val json = dataMap.getString(WearProtocol.DATA_JSON) ?: return@forEach
            when {
                path == WearProtocol.PATH_INBOX -> {
                    val inbox = runCatching { WearCodec.inboxFromJson(json) }.getOrNull() ?: return@forEach
                    Log.d(TAG, "inbox snapshot chats=${inbox.chats.size}")
                    WearStore.setInbox(inbox)
                    OpenBubblesTileService.requestUpdate(applicationContext)
                    OpenBubblesUnreadComplicationService.requestUpdate(applicationContext)
                    for (chat in inbox.chats) {
                        val chatId = chat.id ?: continue
                        loadAvatar(chatId, dataMap.getAsset(WearProtocol.avatarAssetKey(chatId)))
                    }
                }
                path.startsWith(WearProtocol.PATH_THREAD_PREFIX) -> {
                    val thread = runCatching { WearCodec.threadFromJson(json) }.getOrNull() ?: return@forEach
                    Log.d(TAG, "thread snapshot messages=${thread.messages.size}")
                    WearStore.setThread(thread)
                    val chatId = thread.chat?.id ?: return@forEach
                    loadAvatar(chatId, dataMap.getAsset(WearProtocol.avatarAssetKey(chatId)))
                }
                path == WearProtocol.PATH_STATE -> {
                    val state = runCatching { WearCodec.stateFromJson(json) }.getOrNull() ?: return@forEach
                    Log.d(TAG, "state snapshot backendReady=${state.backendReady}")
                    WearStore.setState(state)
                }
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearProtocol.PATH_ACK) return
        WearStore.setPhoneConnected(true)
        val ack = runCatching { WearCodec.ackFromJson(event.data.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        Log.d(TAG, "ack status=${ack.status} id=${ack.commandId?.take(8) ?: "none"}")
        val entry = WearStore.onAck(ack)
        if (entry != null && (ack.status == "succeeded" || ack.status == "duplicate")) {
            entry.command.chatId?.let { WearBridge(applicationContext).requestThread(it) }
            WearBridge(applicationContext).requestInbox()
        }
    }

    override fun onPeerConnected(peer: Node) {
        super.onPeerConnected(peer)
        Log.d(TAG, "peer connected nearby=${peer.isNearby}")
        WearStore.setPhoneConnected(true)
        val bridge = WearBridge(applicationContext)
        WearStore.pendingCommands().forEach(bridge::resendPending)
        bridge.requestInbox()
    }

    override fun onPeerDisconnected(peer: Node) {
        super.onPeerDisconnected(peer)
        Log.d(TAG, "peer disconnected")
        WearStore.setPhoneConnected(false)
    }

    private fun loadAvatar(chatId: String, asset: Asset?) {
        if (asset == null) return
        Wearable.getDataClient(applicationContext)
            .getFdForAsset(asset)
            .addOnSuccessListener { result ->
                val bytes = result.inputStream?.use { it.readBytes() } ?: return@addOnSuccessListener
                if (bytes.isNotEmpty()) {
                    Log.d(TAG, "avatar loaded bytes=${bytes.size}")
                    WearStore.setAvatar(chatId, bytes)
                }
            }
            .addOnFailureListener { error -> Log.w(TAG, "avatar asset load failed", error) }
    }

    companion object {
        private const val TAG = "OBWearWatch"
    }
}

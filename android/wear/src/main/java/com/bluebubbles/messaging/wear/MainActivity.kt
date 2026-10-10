package com.bluebubbles.messaging.wear

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.RemoteInput
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper

class MainActivity : ComponentActivity() {
    private lateinit var bridge: WearBridge
    private var selectedChatId by mutableStateOf<String?>(null)
    private var replyChatId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bridge = WearBridge(this)
        setContent {
            MaterialTheme {
                BackHandler(enabled = selectedChatId != null) { selectedChatId = null }
                AppScaffold {
                    val chatId = selectedChatId
                    if (chatId == null) {
                        InboxScreen(
                            bridge = bridge,
                            onOpenChat = { selectedChatId = it },
                        )
                    } else {
                        ThreadScreen(
                            chatId = chatId,
                            bridge = bridge,
                            onBack = { selectedChatId = null },
                            onReply = { openReplyInput(chatId) },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::bridge.isInitialized) {
            WearStore.pendingCommands().forEach(bridge::resendPending)
            bridge.requestInbox()
        }
    }

    @Deprecated("Kept for Wear RemoteInput compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_REPLY || resultCode != Activity.RESULT_OK || data == null) return
        val text = RemoteInput.getResultsFromIntent(data)
            ?.getCharSequence(KEY_REPLY)
            ?.toString()
            ?.trim()
            .orEmpty()
        val chatId = replyChatId
        replyChatId = null
        if (chatId != null && text.isNotBlank()) bridge.sendText(chatId, text)
    }

    private fun openReplyInput(chatId: String) {
        replyChatId = chatId
        val chat = WearStore.threads.value[chatId]?.chat
            ?: WearStore.inbox.value.chats.firstOrNull { it.id == chatId }
        val remoteInput = RemoteInput.Builder(KEY_REPLY)
            .setLabel("Reply")
            .build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
        RemoteInputIntentHelper.putTitleExtra(intent, chat?.displayName ?: "OpenBubbles")
        RemoteInputIntentHelper.putConfirmLabelExtra(intent, "Send")
        RemoteInputIntentHelper.putInProgressLabelExtra(intent, "Sending…")

        val context = WearStore.threads.value[chatId]?.messages
            .orEmpty()
            .filterNot { it.isFromMe }
            .mapNotNull { message -> message.text?.takeIf { text -> text.isNotBlank() } }
            .takeLast(5)
        if (context.isNotEmpty()) {
            RemoteInputIntentHelper.putSmartReplyContextExtra(intent, context)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_REPLY)
    }

    companion object {
        private const val REQUEST_REPLY = 1001
        private const val KEY_REPLY = "openbubbles_reply"
    }
}

@Composable
private fun InboxScreen(
    bridge: WearBridge,
    onOpenChat: (String) -> Unit,
) {
    val inbox by WearStore.inbox.collectAsState()
    val connected by WearStore.phoneConnected.collectAsState()
    val phoneState by WearStore.state.collectAsState()
    val listState = rememberTransformingLazyColumnState()

    LaunchedEffect(Unit) { bridge.requestInbox() }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    Text("OpenBubbles")
                    Text(
                        when {
                            phoneState.backendReady -> "Phone connected"
                            connected -> "Phone connected · bridge starting"
                            else -> "Phone unavailable · showing cached chats"
                        }
                    )
                }
            }
            if (inbox.chats.isEmpty()) {
                item { Text("No cached conversations yet") }
            }
            items(count = inbox.chats.size) { index ->
                val chat = inbox.chats[index]
                val chatId = chat.id
                if (chatId != null) {
                    Button(
                    onClick = { onOpenChat(chatId) },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Column {
                            val unread = if (chat.unreadCount > 0) " • unread" else ""
                            Text(
                                text = (chat.displayName ?: "Conversation") + unread,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val preview = chat.lastMessagePreview ?: chat.lastMessage?.text
                            if (!preview.isNullOrBlank()) {
                                Text(preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    )
                }
            }
            item {
                Button(
                    onClick = bridge::requestInbox,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Refresh") },
                )
            }
        }
    }
}

@Composable
private fun ThreadScreen(
    chatId: String,
    bridge: WearBridge,
    onBack: () -> Unit,
    onReply: () -> Unit,
) {
    val threads by WearStore.threads.collectAsState()
    val outbox by WearStore.outbox.collectAsState()
    val connected by WearStore.phoneConnected.collectAsState()
    val thread = threads[chatId]
    val chat = thread?.chat ?: WearStore.inbox.value.chats.firstOrNull { it.id == chatId }
    // Observing outbox ensures optimistic/ACK updates recompose displayMessages().
    @Suppress("UNUSED_VARIABLE") val outboxVersion = outbox.size to outbox.sumOf { it.status.hashCode() }
    val messages = WearStore.displayMessages(chatId)
    val listState = rememberTransformingLazyColumnState()

    LaunchedEffect(chatId) {
        bridge.requestThread(chatId)
        bridge.markRead(chatId)
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex + 2)
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Button(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("‹ ${chat?.displayName ?: "Chats"}") },
                )
            }
            item {
                Text(if (connected) "Connected" else "Offline · replies will retry")
            }
            if (messages.isEmpty()) {
                item { Text("Loading conversation…") }
            }
            items(count = messages.size) { index ->
                val message = messages[index]
                val prefix = if (message.isFromMe) "You" else (message.sender ?: "Them")
                val reactionText = message.reactions
                    .mapNotNull { it.emoji ?: it.type }
                    .joinToString(" ")
                val body = when {
                    message.isDeleted -> "Unsent message"
                    !message.text.isNullOrBlank() -> message.text!!
                    message.attachments.isNotEmpty() -> "Attachment"
                    else -> "Message"
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                ) {
                    Text("$prefix: $body")
                    if (reactionText.isNotBlank()) Text(reactionText)
                    if (message.isFromMe && !message.status.isNullOrBlank()) Text(message.status!!)
                    val commandId = message.clientCommandId
                    if (message.status == "failed" && commandId != null) {
                        Button(
                            onClick = { bridge.retryFailed(commandId) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Retry") },
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = onReply,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Reply") },
                )
            }
            item {
                Button(
                    onClick = { bridge.requestThread(chatId) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Refresh") },
                )
            }
        }
    }
}

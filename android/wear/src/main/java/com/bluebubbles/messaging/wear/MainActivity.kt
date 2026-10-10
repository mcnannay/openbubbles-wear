package com.bluebubbles.messaging.wear

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.bluebubbles.messaging.wear.protocol.WearChatDto
import com.bluebubbles.messaging.wear.protocol.WearMessageDto
import com.bluebubbles.messaging.wear.protocol.WearReactionDto
import java.text.DateFormat
import java.util.Date

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
    val avatars by WearStore.avatars.collectAsState()
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
                    Text("OpenBubbles", fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            phoneState.backendReady -> "Phone connected"
                            connected -> "Phone connected · bridge starting"
                            else -> "Phone unavailable · cached chats"
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ConversationAvatar(chat, avatars[chatId], 34.dp)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = chat.displayName ?: "Conversation",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (chat.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.weight(1f),
                                        )
                                        chat.lastMessageTimestampMs?.let { timestamp ->
                                            Text(formatTimestamp(timestamp))
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (chat.unreadCount > 0) {
                                            Text("● ", fontWeight = FontWeight.Bold)
                                        }
                                        val preview = chat.lastMessagePreview ?: chat.lastMessage?.text
                                        Text(
                                            text = preview?.takeIf { it.isNotBlank() } ?: "No messages yet",
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
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
    val avatars by WearStore.avatars.collectAsState()
    val connected by WearStore.phoneConnected.collectAsState()
    val thread = threads[chatId]
    val chat = thread?.chat ?: WearStore.inbox.value.chats.firstOrNull { it.id == chatId }
    val messages = WearStore.displayMessages(chatId)
    val listState = rememberTransformingLazyColumnState()

    LaunchedEffect(chatId) {
        WearStore.markChatRead(chatId)
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
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ConversationAvatar(chat, avatars[chatId], 30.dp)
                            Spacer(Modifier.width(7.dp))
                            Column {
                                Text(
                                    text = chat?.displayName ?: "Chats",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(if (connected) "Connected" else "Offline · replies will retry")
                            }
                        }
                    },
                )
            }
            if (messages.isEmpty()) {
                item { Text("Loading conversation…") }
            }
            items(count = messages.size) { index ->
                val message = messages[index]
                val outboxEntry = message.clientCommandId?.let { commandId ->
                    outbox.firstOrNull { it.command.commandId == commandId }
                }
                MessageBubble(
                    message = message,
                    showSender = chat?.isGroup == true && !message.isFromMe,
                    errorMessage = outboxEntry?.errorMessage,
                    onRetry = message.clientCommandId?.takeIf { message.status == "failed" }?.let { commandId ->
                        { bridge.retryFailed(commandId) }
                    },
                )
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

@Composable
private fun MessageBubble(
    message: WearMessageDto,
    showSender: Boolean,
    errorMessage: String?,
    onRetry: (() -> Unit)?,
) {
    val bubbleColor = if (message.isFromMe) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val body = listOfNotNull(
        message.subject?.takeIf { it.isNotBlank() },
        message.text?.takeIf { it.isNotBlank() },
    ).joinToString("\n").ifBlank {
        when {
            message.isDeleted -> "Unsent message"
            message.attachments.isNotEmpty() -> "Attachment"
            else -> "Message"
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isFromMe) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 158.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bubbleColor)
                .padding(horizontal = 9.dp, vertical = 7.dp),
        ) {
            if (showSender && !message.sender.isNullOrBlank()) {
                Text(message.sender!!, fontWeight = FontWeight.Bold)
            }
            Text(body)
            if (message.reactions.isNotEmpty()) {
                Text(message.reactions.joinToString("  ") { reactionLabel(it) })
            }
            val timestamp = message.timestampMs
            val status = if (message.isFromMe) statusLabel(message.status) else null
            val footer = listOfNotNull(
                timestamp?.let(::formatTimestamp),
                status,
            ).joinToString(" · ")
            if (footer.isNotBlank()) Text(footer)
            if (message.status == "failed" && !errorMessage.isNullOrBlank()) {
                Text(errorMessage, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onRetry != null) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Retry") },
                )
            }
        }
    }
}

@Composable
private fun ConversationAvatar(chat: WearChatDto?, bytes: ByteArray?, size: Dp) {
    val image = remember(bytes) {
        bytes?.let { raw ->
            runCatching {
                BitmapFactory.decodeByteArray(raw, 0, raw.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(avatarInitials(chat), fontWeight = FontWeight.Bold)
        }
    }
}

private fun avatarInitials(chat: WearChatDto?): String {
    val name = chat?.displayName?.trim().orEmpty()
    val fromName = name.split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
    if (fromName.isNotBlank()) return fromName

    return chat?.participants
        ?.firstOrNull()
        ?.displayName
        ?.trim()
        ?.firstOrNull()
        ?.uppercaseChar()
        ?.toString()
        ?: "?"
}

private fun statusLabel(status: String?): String? = when (status) {
    "pending" -> "Sending…"
    "sent" -> "Sent"
    "delivered" -> "Delivered"
    "read" -> "Read"
    "failed" -> "Not delivered"
    else -> null
}

private fun reactionLabel(reaction: WearReactionDto): String {
    reaction.emoji?.takeIf { it.isNotBlank() }?.let { return it }
    return when (reaction.type?.lowercase()) {
        "love", "heart", "2000" -> "❤️"
        "like", "thumbsup", "2001" -> "👍"
        "dislike", "thumbsdown", "2002" -> "👎"
        "laugh", "haha", "2003" -> "😂"
        "emphasize", "!!", "2004" -> "‼️"
        "question", "?", "2005" -> "❓"
        else -> reaction.type?.takeIf { it.isNotBlank() } ?: "•"
    }
}

private fun formatTimestamp(timestampMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestampMs))

package com.bluebubbles.messaging.wear.protocol

data class WearContactDto(
    val id: String? = null,
    val displayName: String? = null,
    val address: String? = null,
)

data class WearAttachmentDto(
    val id: String? = null,
    val mimeType: String? = null,
    val filename: String? = null,
    val sizeBytes: Long? = null,
    val thumbnailKey: String? = null,
    val transferState: Int? = null,
)

data class WearReactionDto(
    val id: String? = null,
    val sender: String? = null,
    val type: String? = null,
    val emoji: String? = null,
    val timestampMs: Long? = null,
)

data class WearMessageDto(
    val id: String? = null,
    val chatId: String? = null,
    val sender: String? = null,
    val text: String? = null,
    val subject: String? = null,
    val timestampMs: Long? = null,
    val isFromMe: Boolean = false,
    val status: String? = null,
    val attachments: List<WearAttachmentDto> = emptyList(),
    val reactions: List<WearReactionDto> = emptyList(),
    val replyToId: String? = null,
    val editedAtMs: Long? = null,
    val isDeleted: Boolean = false,
    // Set only by the watch for optimistic messages. Phone snapshots leave this null.
    val clientCommandId: String? = null,
)

data class WearChatDto(
    val id: String? = null,
    val displayName: String? = null,
    val participants: List<WearContactDto> = emptyList(),
    val lastMessage: WearMessageDto? = null,
    val lastMessagePreview: String? = null,
    val lastMessageTimestampMs: Long? = null,
    val unreadCount: Int = 0,
    val isGroup: Boolean = false,
    val isMuted: Boolean = false,
    val avatarKey: String? = null,
)

data class WearInboxDto(
    val protocolVersion: Int = WearProtocol.VERSION,
    val generatedAtMs: Long = System.currentTimeMillis(),
    val chats: List<WearChatDto> = emptyList(),
)

data class WearThreadDto(
    val protocolVersion: Int = WearProtocol.VERSION,
    val generatedAtMs: Long = System.currentTimeMillis(),
    val chat: WearChatDto? = null,
    // Oldest -> newest for durable snapshots.
    val messages: List<WearMessageDto> = emptyList(),
    val hasMore: Boolean = false,
)

data class WearStateDto(
    val protocolVersion: Int = WearProtocol.VERSION,
    val generatedAtMs: Long = System.currentTimeMillis(),
    val bridgeReady: Boolean = false,
    val backendReady: Boolean = false,
    val detail: String? = null,
)

data class WearCommandDto(
    val protocolVersion: Int = WearProtocol.VERSION,
    val commandId: String? = null,
    val type: String? = null,
    val issuedAtMs: Long? = null,
    val chatId: String? = null,
    val messageId: String? = null,
    val text: String? = null,
    val reaction: String? = null,
    val partIndex: Int? = null,
)

data class WearAckDto(
    val protocolVersion: Int = WearProtocol.VERSION,
    val commandId: String? = null,
    val status: String? = null,
    val messageId: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

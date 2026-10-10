package com.bluebubbles.messaging.wear.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class WearCodecTest {
    @Test
    fun inboxRoundTrips() {
        val source = WearInboxDto(chats = listOf(WearChatDto(id = "chat-1", displayName = "Test")))
        val decoded = WearCodec.inboxFromJson(WearCodec.toJson(source))
        assertEquals(WearProtocol.VERSION, decoded.protocolVersion)
        assertEquals("chat-1", decoded.chats.first().id)
    }

    @Test
    fun commandRoundTrips() {
        val source = WearCommandDto(commandId = "cmd-1", type = WearCommandType.SEND_TEXT, chatId = "chat-1", text = "Hello")
        val decoded = WearCodec.commandFromJson(WearCodec.toJson(source))
        assertEquals("cmd-1", decoded.commandId)
        assertEquals("Hello", decoded.text)
    }

    @Test
    fun stableKeysAreDeterministic() {
        assertEquals("eaeb9111b1c6744278e80397", WearProtocol.stablePathKey("chat-1"))
        assertEquals(
            "/openbubbles/v1/thread/eaeb9111b1c6744278e80397",
            WearProtocol.threadPath("chat-1"),
        )
        assertEquals("avatar_eaeb9111b1c6744278e80397", WearProtocol.avatarAssetKey("chat-1"))
    }
}

package com.bluebubbles.messaging.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ActionBuilders.launchAction
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.layout.column
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textButton
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import androidx.wear.tiles.tile

/**
 * A deliberately small messaging Tile backed only by the watch cache.
 *
 * Rendering never waits on the phone. The normal Data Layer inbox refresh updates the cache and
 * requests a Tile refresh, so the Tile remains useful when the phone is temporarily unreachable.
 */
class OpenBubblesTileService : Material3TileService() {
    override fun onCreate() {
        super.onCreate()
        WearStore.init(applicationContext)
    }

    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val inbox = WearStore.inbox.value
        val visibleChats = inbox.chats.take(2)
        val unread = inbox.chats.sumOf { it.unreadCount }.coerceAtLeast(0)
        return tile(Timeline.fromLayoutElement(tileLayout(visibleChats, unread)))
    }

    private fun MaterialScope.tileLayout(
        chats: List<com.bluebubbles.messaging.wear.protocol.WearChatDto>,
        unread: Int,
    ): LayoutElement {
        val rows = chats.map { chat ->
            val chatId = chat.id
            val label = buildString {
                if (chat.unreadCount > 0) append("• ")
                append(chat.displayName?.takeIf { it.isNotBlank() } ?: "Conversation")
            }
            textButton(
                onClick = clickable(action = mainActivityAction(chatId)),
                labelContent = { text(label.take(22).layoutString) },
                width = expand(),
            )
        }

        return primaryLayout(
            titleSlot = {
                val title = when {
                    unread <= 0 -> "OpenBubbles"
                    unread > 99 -> "OpenBubbles · 99+ unread"
                    else -> "OpenBubbles · $unread unread"
                }
                text(title.layoutString)
            },
            mainSlot = {
                if (rows.isEmpty()) {
                    text("Open the app to sync chats".layoutString)
                } else {
                    column(*rows.toTypedArray(), width = expand())
                }
            },
            bottomSlot = {
                textEdgeButton(
                    onClick = clickable(action = mainActivityAction()),
                    labelContent = { text("Inbox".layoutString) },
                )
            },
        )
    }

    private fun mainActivityAction(chatId: String? = null): ActionBuilders.LaunchAction {
        val extras = if (chatId.isNullOrBlank()) {
            emptyMap()
        } else {
            mapOf(MainActivity.EXTRA_CHAT_ID to ActionBuilders.stringExtra(chatId))
        }
        return launchAction(
            ComponentName(this, MainActivity::class.java),
            extras,
        )
    }

    companion object {
        fun requestUpdate(context: Context) {
            runCatching {
                TileService.getUpdater(context.applicationContext)
                    .requestUpdate(OpenBubblesTileService::class.java)
            }
        }
    }
}

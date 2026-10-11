package com.bluebubbles.messaging.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/** Short-text complication showing total unread conversations/messages represented by the cache. */
class OpenBubblesUnreadComplicationService : SuspendingComplicationDataSourceService() {
    override fun onCreate() {
        super.onCreate()
        WearStore.init(applicationContext)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        if (request.complicationType != ComplicationType.SHORT_TEXT) {
            return NoDataComplicationData()
        }
        return complicationData(totalUnread(), openInboxPendingIntent())
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) complicationData(3, null) else null

    private fun totalUnread(): Int =
        WearStore.inbox.value.chats.sumOf { it.unreadCount }.coerceAtLeast(0)

    private fun complicationData(unread: Int, tapAction: PendingIntent?): ComplicationData {
        val display = when {
            unread > 99 -> "99+"
            else -> unread.toString()
        }
        val description = when (unread) {
            0 -> "OpenBubbles, no unread messages"
            1 -> "OpenBubbles, 1 unread message"
            else -> "OpenBubbles, $unread unread messages"
        }
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(display).build(),
            contentDescription = PlainComplicationText.Builder(description).build(),
        )
            .setTitle(PlainComplicationText.Builder("Unread").build())
            .setMonochromaticImage(
                MonochromaticImage.Builder(
                    Icon.createWithResource(this, R.drawable.ic_openbubbles_complication),
                ).build(),
            )
            .setTapAction(tapAction)
            .build()
    }

    private fun openInboxPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        fun requestUpdate(context: Context) {
            runCatching {
                ComplicationDataSourceUpdateRequester.create(
                    context.applicationContext,
                    ComponentName(context, OpenBubblesUnreadComplicationService::class.java),
                ).requestUpdateAll()
            }
        }
    }
}

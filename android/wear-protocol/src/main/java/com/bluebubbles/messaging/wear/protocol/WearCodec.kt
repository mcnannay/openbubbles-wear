package com.bluebubbles.messaging.wear.protocol

import com.google.gson.Gson
import com.google.gson.GsonBuilder

object WearCodec {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun toJson(value: Any): String = gson.toJson(value)
    fun inboxFromJson(json: String): WearInboxDto = gson.fromJson(json, WearInboxDto::class.java)
    fun threadFromJson(json: String): WearThreadDto = gson.fromJson(json, WearThreadDto::class.java)
    fun stateFromJson(json: String): WearStateDto = gson.fromJson(json, WearStateDto::class.java)
    fun commandFromJson(json: String): WearCommandDto = gson.fromJson(json, WearCommandDto::class.java)
    fun ackFromJson(json: String): WearAckDto = gson.fromJson(json, WearAckDto::class.java)
}

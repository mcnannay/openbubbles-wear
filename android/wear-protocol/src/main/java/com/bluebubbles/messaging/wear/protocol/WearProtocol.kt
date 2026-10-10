package com.bluebubbles.messaging.wear.protocol

object WearProtocol {
    const val VERSION = 1
    const val PREFIX = "/openbubbles/v1"
    const val PATH_INBOX = "$PREFIX/inbox"
    const val PATH_THREAD_PREFIX = "$PREFIX/thread/"
    const val PATH_STATE = "$PREFIX/state"
    const val PATH_COMMAND = "$PREFIX/command"
    const val PATH_ACK = "$PREFIX/ack"

    const val DATA_JSON = "json"
    const val DATA_UPDATED_AT = "updatedAt"
}

object WearCommandType {
    const val REQUEST_INBOX = "requestInbox"
    const val REQUEST_THREAD = "requestThread"
    const val SEND_TEXT = "sendText"
    const val MARK_READ = "markRead"
    const val REACT = "react"
}

object WearAckStatus {
    const val ACCEPTED = "accepted"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val DUPLICATE = "duplicate"
}

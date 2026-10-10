import 'package:bluebubbles/database/models.dart';

import 'wear_protocol.dart';

class WearMapper {
  static WearContactDto contact(Handle handle) => WearContactDto(
    id: handle.uniqueAddressAndService,
    displayName: handle.displayName,
    address: handle.address,
  );

  static WearAttachmentDto attachment(Attachment a) => WearAttachmentDto(
    id: a.guid ?? a.id?.toString(),
    mimeType: a.mimeType,
    filename: a.transferName,
    sizeBytes: a.totalBytes,
    thumbnailKey: a.guid,
    // OpenBubbles' current ObjectBox Attachment model does not expose transferState.
    transferState: null,
  );

  static WearReactionDto reaction(Message m) => WearReactionDto(
    id: m.guid ?? m.id?.toString(),
    sender: m.handle?.uniqueAddressAndService ?? m.handleId?.toString(),
    type: m.associatedMessageType,
    emoji: m.associatedMessageEmoji,
    timestampMs: _dateToMillis(m.dateCreated),
  );

  static WearMessageDto message(Message m, {Chat? chat}) => WearMessageDto(
    id: m.guid ?? m.id?.toString(),
    chatId: (chat ?? m.getChat())?.guid,
    sender: m.handle?.displayName ?? m.handle?.address ?? m.handleId?.toString(),
    text: m.text,
    subject: m.subject,
    timestampMs: _dateToMillis(m.dateCreated),
    isFromMe: m.isFromMe ?? false,
    status: _status(m),
    attachments: m.attachments.whereType<Attachment>().map(attachment).toList(),
    reactions: m.associatedMessages
        .where((e) => e.associatedMessageType != null || e.associatedMessageEmoji != null)
        .map(reaction)
        .toList(),
    replyToId: m.threadOriginatorGuid,
    editedAtMs: _dateToMillis(m.dateEdited),
    isDeleted: m.dateDeleted != null,
  );

  static WearChatDto chat(Chat c) {
    final latest = c.latestMessage;
    final hasRealLatest = latest.id != null ||
        (latest.dateCreated?.millisecondsSinceEpoch ?? 0) > 0;
    final participants = c.participants;
    return WearChatDto(
      id: c.guid,
      displayName: c.getTitle(),
      participants: participants.map(contact).toList(),
      lastMessage: hasRealLatest ? message(latest, chat: c) : null,
      lastMessagePreview: hasRealLatest ? latest.fullText : null,
      lastMessageTimestampMs: hasRealLatest ? _dateToMillis(latest.dateCreated) : null,
      // Chat stores unread state as a bool, not a numeric unread counter.
      unreadCount: c.hasUnreadMessage == true ? 1 : 0,
      isGroup: participants.length > 1,
      isMuted: c.muteType != null,
      avatarKey: c.customAvatarPath ??
          c.transcriptPosterPath ??
          (participants.length == 1 ? participants.first.getPoster() : null),
    );
  }

  static WearInboxDto inbox(Iterable<Chat> chats) => WearInboxDto(
    chats: chats.map(chat).toList(),
  );

  static Future<WearThreadDto> thread(Chat c, {int limit = 50, int offset = 0}) async {
    final messages = await Chat.getMessagesAsync(c, limit: limit, offset: offset);
    return WearThreadDto(
      chat: chat(c),
      // OpenBubbles returns newest -> oldest. Protocol snapshots are oldest -> newest.
      messages: messages.reversed.map((m) => message(m, chat: c)).toList(),
      hasMore: messages.length >= limit,
    );
  }

  static int? _dateToMillis(DateTime? value) => value?.millisecondsSinceEpoch;

  static String _status(Message m) {
    final guid = m.guid ?? '';
    if (m.error != 0 || guid.startsWith('error')) return 'failed';
    if (m.dateRead != null) return 'read';
    if (m.isDelivered || m.dateDelivered != null) return 'delivered';
    if (m.isFromMe == true && (guid.startsWith('temp-') || m.sendingServiceId != null)) return 'pending';
    return m.isFromMe == true ? 'sent' : 'received';
  }
}

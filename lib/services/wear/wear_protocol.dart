const int wearProtocolVersion = 1;

class WearContactDto {
  final String? id;
  final String? displayName;
  final String? address;
  const WearContactDto({this.id, this.displayName, this.address});
  Map<String, dynamic> toJson() => {'id': id, 'displayName': displayName, 'address': address};
}

class WearAttachmentDto {
  final String? id;
  final String? mimeType;
  final String? filename;
  final int? sizeBytes;
  final String? thumbnailKey;
  final int? transferState;
  const WearAttachmentDto({this.id, this.mimeType, this.filename, this.sizeBytes, this.thumbnailKey, this.transferState});
  Map<String, dynamic> toJson() => {'id': id, 'mimeType': mimeType, 'filename': filename, 'sizeBytes': sizeBytes, 'thumbnailKey': thumbnailKey, 'transferState': transferState};
}

class WearReactionDto {
  final String? id;
  final String? sender;
  final String? type;
  final String? emoji;
  final int? timestampMs;
  const WearReactionDto({this.id, this.sender, this.type, this.emoji, this.timestampMs});
  Map<String, dynamic> toJson() => {'id': id, 'sender': sender, 'type': type, 'emoji': emoji, 'timestampMs': timestampMs};
}

class WearMessageDto {
  final String? id;
  final String? chatId;
  final String? sender;
  final String? text;
  final String? subject;
  final int? timestampMs;
  final bool isFromMe;
  final String? status;
  final List<WearAttachmentDto> attachments;
  final List<WearReactionDto> reactions;
  final String? replyToId;
  final int? editedAtMs;
  final bool isDeleted;
  final String? clientCommandId;

  const WearMessageDto({
    this.id,
    this.chatId,
    this.sender,
    this.text,
    this.subject,
    this.timestampMs,
    this.isFromMe = false,
    this.status,
    this.attachments = const [],
    this.reactions = const [],
    this.replyToId,
    this.editedAtMs,
    this.isDeleted = false,
    this.clientCommandId,
  });

  Map<String, dynamic> toJson() => {
    'id': id,
    'chatId': chatId,
    'sender': sender,
    'text': text,
    'subject': subject,
    'timestampMs': timestampMs,
    'isFromMe': isFromMe,
    'status': status,
    'attachments': attachments.map((e) => e.toJson()).toList(),
    'reactions': reactions.map((e) => e.toJson()).toList(),
    'replyToId': replyToId,
    'editedAtMs': editedAtMs,
    'isDeleted': isDeleted,
    'clientCommandId': clientCommandId,
  };
}

class WearChatDto {
  final String? id;
  final String? displayName;
  final List<WearContactDto> participants;
  final WearMessageDto? lastMessage;
  final String? lastMessagePreview;
  final int? lastMessageTimestampMs;
  final int unreadCount;
  final bool isGroup;
  final bool isMuted;
  final String? avatarKey;
  const WearChatDto({this.id, this.displayName, this.participants = const [], this.lastMessage, this.lastMessagePreview, this.lastMessageTimestampMs, this.unreadCount = 0, this.isGroup = false, this.isMuted = false, this.avatarKey});
  Map<String, dynamic> toJson() => {'id': id, 'displayName': displayName, 'participants': participants.map((e) => e.toJson()).toList(), 'lastMessage': lastMessage?.toJson(), 'lastMessagePreview': lastMessagePreview, 'lastMessageTimestampMs': lastMessageTimestampMs, 'unreadCount': unreadCount, 'isGroup': isGroup, 'isMuted': isMuted, 'avatarKey': avatarKey};
}

class WearInboxDto {
  final int protocolVersion;
  final int generatedAtMs;
  final List<WearChatDto> chats;
  WearInboxDto({this.protocolVersion = wearProtocolVersion, int? generatedAtMs, this.chats = const []}) : generatedAtMs = generatedAtMs ?? DateTime.now().millisecondsSinceEpoch;
  Map<String, dynamic> toJson() => {'protocolVersion': protocolVersion, 'generatedAtMs': generatedAtMs, 'chats': chats.map((e) => e.toJson()).toList()};
}

class WearThreadDto {
  final int protocolVersion;
  final int generatedAtMs;
  final WearChatDto? chat;
  final List<WearMessageDto> messages;
  final bool hasMore;
  WearThreadDto({this.protocolVersion = wearProtocolVersion, int? generatedAtMs, this.chat, this.messages = const [], this.hasMore = false}) : generatedAtMs = generatedAtMs ?? DateTime.now().millisecondsSinceEpoch;
  Map<String, dynamic> toJson() => {'protocolVersion': protocolVersion, 'generatedAtMs': generatedAtMs, 'chat': chat?.toJson(), 'messages': messages.map((e) => e.toJson()).toList(), 'hasMore': hasMore};
}

class WearStateDto {
  final int protocolVersion;
  final int generatedAtMs;
  final bool bridgeReady;
  final bool backendReady;
  final String? detail;
  WearStateDto({this.protocolVersion = wearProtocolVersion, int? generatedAtMs, this.bridgeReady = false, this.backendReady = false, this.detail}) : generatedAtMs = generatedAtMs ?? DateTime.now().millisecondsSinceEpoch;
  Map<String, dynamic> toJson() => {'protocolVersion': protocolVersion, 'generatedAtMs': generatedAtMs, 'bridgeReady': bridgeReady, 'backendReady': backendReady, 'detail': detail};
}

class WearCommandDto {
  final int protocolVersion;
  final String commandId;
  final String type;
  final int? issuedAtMs;
  final String? chatId;
  final String? messageId;
  final String? text;
  final String? reaction;
  final int? partIndex;

  const WearCommandDto({required this.commandId, required this.type, this.protocolVersion = wearProtocolVersion, this.issuedAtMs, this.chatId, this.messageId, this.text, this.reaction, this.partIndex});

  factory WearCommandDto.fromJson(Map<String, dynamic> json) => WearCommandDto(
    protocolVersion: json['protocolVersion'] as int? ?? wearProtocolVersion,
    commandId: json['commandId'] as String? ?? '',
    type: json['type'] as String? ?? '',
    issuedAtMs: json['issuedAtMs'] as int?,
    chatId: json['chatId'] as String?,
    messageId: json['messageId'] as String?,
    text: json['text'] as String?,
    reaction: json['reaction'] as String?,
    partIndex: json['partIndex'] as int?,
  );
}

class WearCommandResult {
  final String status;
  final String? messageId;
  final String? errorCode;
  final String? errorMessage;

  const WearCommandResult._(this.status, {this.messageId, this.errorCode, this.errorMessage});
  const WearCommandResult.succeeded({String? messageId}) : this._('succeeded', messageId: messageId);
  const WearCommandResult.failed(String code, String message) : this._('failed', errorCode: code, errorMessage: message);
}

import 'dart:async';

import 'package:bluebubbles/database/models.dart';
import 'package:bluebubbles/services/backend/action_handler.dart';
import 'package:bluebubbles/services/rustpush/rustpush_service.dart';
import 'package:collection/collection.dart';

import 'wear_bridge_service.dart';
import 'wear_protocol.dart';

/// Executes Wear commands exclusively through OpenBubbles' existing database and
/// ActionHandler/backend pipeline. No RustPush/iMessage state is duplicated on the watch.
class WearCommandExecutor {
  WearCommandExecutor._();
  static final WearCommandExecutor instance = WearCommandExecutor._();

  static const String _commandMetadataKey = '_openbubblesWearCommandId';
  static const Duration _refreshInterval = Duration(seconds: 5);
  static const int _maxWatchedChats = 5;

  final WearBridgeService _bridge = WearBridgeService.instance;
  final Set<String> _watchedChats = <String>{};
  Timer? _refreshTimer;
  bool _started = false;
  String? _lastInboxSignature;
  final Map<String, String> _lastThreadSignature = <String, String>{};
  int _refreshTick = 0;

  Future<void> start({bool backendReady = true}) async {
    if (_started) return;
    _started = true;
    await _bridge.start(onCommand: handle);
    await _bridge.publishState(
      backendReady: backendReady,
      detail: backendReady ? 'OpenBubbles bridge ready' : 'OpenBubbles setup is not complete',
    );
    await _publishInbox(force: true);
    _refreshTimer?.cancel();
    _refreshTimer = Timer.periodic(_refreshInterval, (_) {
      unawaited(_refreshChangedSnapshots());
    });
  }

  Future<WearCommandResult> handle(WearCommandDto command) async {
    switch (command.type) {
      case 'requestInbox':
        await _publishInbox(force: true);
        return const WearCommandResult.succeeded();
      case 'requestThread':
        final chat = _requiredChat(command.chatId);
        _watchChat(chat.guid);
        await _publishThread(chat, force: true);
        return const WearCommandResult.succeeded();
      case 'sendText':
        return _sendText(command);
      case 'markRead':
        return _markRead(command);
      case 'react':
        return _react(command);
      default:
        return WearCommandResult.failed('UNSUPPORTED', 'Unknown command type: ${command.type}');
    }
  }

  Chat _requiredChat(String? guid) {
    if (guid == null || guid.isEmpty) throw StateError('Missing chatId');
    final chat = Chat.findOne(guid: guid);
    if (chat == null) throw StateError('Chat not found: $guid');
    return chat;
  }

  Future<void> _refreshChangedSnapshots() async {
    try {
      _refreshTick++;
      // Thread updates stay responsive while the watch is in a conversation; the more
      // expensive 30-chat inbox scan runs every third tick (~15 seconds). Explicit
      // requestInbox/requestThread commands still publish immediately.
      if (_refreshTick % 3 == 0) await _publishInbox();
      for (final guid in _watchedChats.toList()) {
        final chat = Chat.findOne(guid: guid);
        if (chat == null) {
          _watchedChats.remove(guid);
          _lastThreadSignature.remove(guid);
          continue;
        }
        await _publishThread(chat);
      }
    } catch (_) {
      // Polling is a fallback for DB changes that do not originate from the watch.
      // Individual watch commands still return their own terminal errors.
    }
  }

  Future<void> _publishInbox({bool force = false}) async {
    final chats = await Chat.getChats(limit: 30);
    final signature = chats.map((chat) {
      final latest = chat.latestMessage;
      return '${chat.guid}|${chat.getTitle()}|${chat.hasUnreadMessage}|${chat.muteType}|${latest.guid}|${latest.dateCreated?.millisecondsSinceEpoch}|${latest.error}|${latest.dateRead?.millisecondsSinceEpoch}|${latest.dateDelivered?.millisecondsSinceEpoch}';
    }).join('\n');
    if (!force && signature == _lastInboxSignature) return;
    _lastInboxSignature = signature;
    await _bridge.publishInbox(chats);
  }

  Future<void> _publishThread(Chat chat, {bool force = false}) async {
    final recent = await Chat.getMessagesAsync(chat, limit: 50);
    final signature = recent.map((message) {
      final reactions = message.associatedMessages.map((e) => '${e.guid}:${e.associatedMessageType}:${e.associatedMessageEmoji}').join(',');
      return '${message.guid}|${message.dateCreated?.millisecondsSinceEpoch}|${message.text}|${message.error}|${message.dateRead?.millisecondsSinceEpoch}|${message.dateDelivered?.millisecondsSinceEpoch}|$reactions';
    }).join('\n');
    if (!force && signature == _lastThreadSignature[chat.guid]) return;
    _lastThreadSignature[chat.guid] = signature;
    await _bridge.publishThread(chat, limit: 50);
  }

  void _watchChat(String guid) {
    // Dart's default Set preserves insertion order. Re-adding moves the most recently
    // used chat to the back and bounds fallback polling to avoid an ever-growing cost.
    _watchedChats.remove(guid);
    _watchedChats.add(guid);
    while (_watchedChats.length > _maxWatchedChats) {
      final removed = _watchedChats.first;
      _watchedChats.remove(removed);
      _lastThreadSignature.remove(removed);
    }
  }

  Future<void> _refresh(Chat chat) async {
    _watchChat(chat.guid);
    await _publishThread(chat, force: true);
    await _publishInbox(force: true);
  }

  Future<WearCommandResult> _sendText(WearCommandDto command) async {
    await pushService.initFuture;
    final chat = _requiredChat(command.chatId);
    _watchChat(chat.guid);
    final text = command.text?.trim() ?? '';
    if (text.isEmpty) return const WearCommandResult.failed('EMPTY_TEXT', 'Message is empty');

    // A command UUID is also stored on the OpenBubbles temp message. If Flutter/Android
    // restarts after the backend accepted a send but before the terminal ACK reached the
    // watch, the durable command journal can re-enter here and recognize the existing send.
    final recent = await Chat.getMessagesAsync(chat, limit: 50);
    final existingForCommand = recent
        .where((candidate) => candidate.metadata?[_commandMetadataKey] == command.commandId)
        .toList();

    if (existingForCommand.isNotEmpty) {
      // prepMessage can split a legacy URL into more than one DB message. Treat all rows
      // carrying this UUID as one logical watch command so a restart cannot create a
      // second copy of a part that already made it to the backend.
      final failed = existingForCommand.where((message) {
        final guid = message.guid ?? '';
        return message.error != 0 || guid.startsWith('error');
      }).toList();
      if (failed.isNotEmpty) {
        await _refresh(chat);
        return const WearCommandResult.failed('SEND_FAILED', 'The previous OpenBubbles send attempt failed');
      }

      final pending = existingForCommand.where((message) {
        final guid = message.guid ?? '';
        return guid.startsWith('temp-') || message.sendingServiceId != null;
      }).toList();
      if (pending.isEmpty) {
        final guid = existingForCommand
            .map((message) => message.guid)
            .whereType<String>()
            .firstWhere((value) => value.isNotEmpty, orElse: () => '');
        await _refresh(chat);
        return WearCommandResult.succeeded(messageId: guid.isEmpty ? null : guid);
      }

      // Stale executing command after a process restart: reuse only the remaining temp
      // rows instead of creating another optimistic DB row.
      try {
        for (final message in pending) {
          await ah.sendMessage(chat, message, null, null);
        }
      } catch (e) {
        await _refresh(chat);
        return WearCommandResult.failed('SEND_FAILED', e.toString());
      }
      final recoveredGuid = await _findSentGuid(
        chat,
        text,
        pending.first.dateCreated ?? DateTime.now(),
        commandId: command.commandId,
      );
      await _refresh(chat);
      return WearCommandResult.succeeded(messageId: recoveredGuid);
    }

    final started = DateTime.now();
    final outgoing = Message(
      text: text,
      dateCreated: started,
      hasAttachments: false,
      isFromMe: true,
      handleId: 0,
      metadata: <String, dynamic>{_commandMetadataKey: command.commandId},
    );

    final prepared = await ah.prepMessage(chat, outgoing, null, null);
    if (prepared.isEmpty) {
      return const WearCommandResult.failed('PREPARE_FAILED', 'OpenBubbles rejected the outgoing message');
    }

    // prepMessage can split legacy URL messages. Preserve the command marker on every part.
    for (final message in prepared) {
      message.metadata = <String, dynamic>{
        ...?message.metadata,
        _commandMetadataKey: command.commandId,
      };
      message.save(chat: chat);
    }

    try {
      for (final message in prepared) {
        await ah.sendMessage(chat, message, null, null);
      }
    } catch (e) {
      await _refresh(chat);
      return WearCommandResult.failed('SEND_FAILED', e.toString());
    }

    final actualGuid = await _findSentGuid(chat, text, started, commandId: command.commandId);
    await _refresh(chat);
    return WearCommandResult.succeeded(messageId: actualGuid);
  }

  Future<String?> _findSentGuid(
    Chat chat,
    String text,
    DateTime started, {
    String? commandId,
  }) async {
    final recent = await Chat.getMessagesAsync(chat, limit: 20);
    for (final message in recent) {
      final guid = message.guid ?? '';
      if (message.isFromMe != true || guid.startsWith('temp-') || guid.startsWith('error')) continue;
      if (commandId != null && message.metadata?[_commandMetadataKey] == commandId) return guid;
      if ((message.text ?? '').trim() != text) continue;
      final created = message.dateCreated;
      if (created == null) continue;
      if (created.difference(started).inMilliseconds.abs() <=
          const Duration(minutes: 2).inMilliseconds) {
        return guid;
      }
    }
    return null;
  }

  Future<WearCommandResult> _markRead(WearCommandDto command) async {
    await pushService.initFuture;
    final chat = _requiredChat(command.chatId);
    chat.toggleHasUnread(false, force: true, privateMark: true);
    await _refresh(chat);
    return const WearCommandResult.succeeded();
  }

  Future<WearCommandResult> _react(WearCommandDto command) async {
    await pushService.initFuture;
    final chat = _requiredChat(command.chatId);
    _watchChat(chat.guid);
    final messageId = command.messageId;
    final reaction = command.reaction;
    if (messageId == null || messageId.isEmpty || reaction == null || reaction.isEmpty) {
      return const WearCommandResult.failed('BAD_REACTION', 'messageId and reaction are required');
    }

    final selected = Message.findOne(guid: messageId);
    if (selected == null) return WearCommandResult.failed('MESSAGE_NOT_FOUND', 'Message not found: $messageId');
    if (selected.getChat()?.guid != chat.guid) {
      return const WearCommandResult.failed('CHAT_MISMATCH', 'Reaction target does not belong to the requested chat');
    }

    // Reactions are messages too. Load their associated rows and apply the same UUID
    // recovery rule used for text sends before creating a new tapback.
    selected.fetchAssociatedMessages();
    final existingReaction = selected.associatedMessages
        .where((candidate) => candidate.metadata?[_commandMetadataKey] == command.commandId)
        .firstOrNull;
    if (existingReaction != null) {
      final guid = existingReaction.guid ?? '';
      if (existingReaction.error != 0 || guid.startsWith('error')) {
        await _refresh(chat);
        return const WearCommandResult.failed('REACTION_FAILED', 'The previous reaction attempt failed');
      }
      if (!guid.startsWith('temp-') && existingReaction.sendingServiceId == null) {
        await _refresh(chat);
        return WearCommandResult.succeeded(messageId: guid.isEmpty ? null : guid);
      }
      try {
        await ah.sendMessage(chat, existingReaction, selected, reaction);
      } catch (e) {
        await _refresh(chat);
        return WearCommandResult.failed('REACTION_FAILED', e.toString());
      }
      await _refresh(chat);
      return const WearCommandResult.succeeded();
    }

    final outgoing = Message(
      dateCreated: DateTime.now(),
      hasAttachments: false,
      isFromMe: true,
      handleId: 0,
      associatedMessageGuid: selected.guid,
      associatedMessagePart: command.partIndex ?? 0,
      associatedMessageType: reaction,
      metadata: <String, dynamic>{_commandMetadataKey: command.commandId},
    );
    final prepared = await ah.prepMessage(chat, outgoing, selected, reaction);
    if (prepared.isEmpty) return const WearCommandResult.failed('PREPARE_FAILED', 'Could not prepare reaction');
    for (final message in prepared) {
      message.metadata = <String, dynamic>{
        ...?message.metadata,
        _commandMetadataKey: command.commandId,
      };
      message.save(chat: chat);
    }

    try {
      await ah.sendMessage(chat, prepared.first, selected, reaction);
    } catch (e) {
      await _refresh(chat);
      return WearCommandResult.failed('REACTION_FAILED', e.toString());
    }

    await _refresh(chat);
    return const WearCommandResult.succeeded();
  }
}

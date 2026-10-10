import 'dart:convert';

import 'package:bluebubbles/database/models.dart';
import 'package:flutter/services.dart';

import 'wear_mapper.dart';
import 'wear_protocol.dart';

class WearBridgeService {
  WearBridgeService._();
  static final WearBridgeService instance = WearBridgeService._();
  static const MethodChannel _channel = MethodChannel('com.openbubbles/wear');

  Future<void> start({required Future<WearCommandResult> Function(WearCommandDto) onCommand}) async {
    _channel.setMethodCallHandler((call) async {
      if (call.method != 'wearCommand') return;
      final args = Map<String, dynamic>.from(call.arguments as Map);
      final raw = args['json'] as String?;
      if (raw != null) await _processRaw(raw, onCommand);
    });

    // Dart is marked ready only after the handler exists. Native returns durable commands
    // that were queued while Flutter was unavailable (or a stale crashed execution).
    final pending = await _channel.invokeListMethod<String>('startBridge') ?? const <String>[];
    for (final raw in pending) {
      await _processRaw(raw, onCommand);
    }
  }

  Future<void> _processRaw(
    String raw,
    Future<WearCommandResult> Function(WearCommandDto) onCommand,
  ) async {
    WearCommandDto? command;
    WearCommandResult result;
    try {
      command = WearCommandDto.fromJson(Map<String, dynamic>.from(jsonDecode(raw) as Map));
      if (command.protocolVersion != wearProtocolVersion || command.commandId.isEmpty || command.type.isEmpty) {
        result = const WearCommandResult.failed('PROTOCOL', 'Unsupported protocol or incomplete command');
      } else {
        result = await onCommand(command);
      }
    } catch (e) {
      result = WearCommandResult.failed('EXECUTION', e.toString());
    }

    if (command == null || command.commandId.isEmpty) return;
    await _channel.invokeMethod('completeCommand', {
      'commandId': command.commandId,
      'status': result.status,
      'messageId': result.messageId,
      'errorCode': result.errorCode,
      'errorMessage': result.errorMessage,
    });
  }

  Future<void> publishInbox(Iterable<Chat> chats) async {
    final dto = WearMapper.inbox(chats);
    await _channel.invokeMethod('publishInbox', {'json': jsonEncode(dto.toJson())});
  }

  Future<void> publishThread(Chat chat, {int limit = 50, int offset = 0}) async {
    final dto = await WearMapper.thread(chat, limit: limit, offset: offset);
    await _channel.invokeMethod('publishThread', {
      'chatId': chat.guid,
      'json': jsonEncode(dto.toJson()),
    });
  }

  Future<void> publishState({required bool backendReady, String? detail}) async {
    final dto = WearStateDto(bridgeReady: true, backendReady: backendReady, detail: detail);
    await _channel.invokeMethod('publishState', {'json': jsonEncode(dto.toJson())});
  }
}

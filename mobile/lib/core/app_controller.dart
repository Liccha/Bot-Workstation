import 'package:flutter/foundation.dart';

import 'api_client.dart';
import 'mobile_update_service.dart';
import 'session_store.dart';

class AppController extends ChangeNotifier {
  AppController(this._store, {MobileUpdateService? mobileUpdates})
    : mobileUpdates = mobileUpdates ?? MobileUpdateService();
  final SessionStore _store;
  WorkstationApi? api;
  bool booting = true;
  bool busy = false;
  Map<String, dynamic> status = const {};
  Map<String, dynamic> update = const {};
  String? lastError;
  MobileRelease? mobileRelease;
  final MobileUpdateService mobileUpdates;

  bool get connected => api != null;

  Future<void> restore() async {
    checkMobileUpdate();
    try {
      final session = await _store.load();
      if (session != null) {
        api = WorkstationApi(session.server, token: session.token);
        await refresh();
      }
    } catch (error) {
      api?.close();
      api = null;
      lastError = error.toString();
    } finally {
      booting = false;
      notifyListeners();
    }
  }

  Future<void> checkMobileUpdate() async {
    try {
      mobileRelease = await mobileUpdates.check();
      notifyListeners();
    } catch (_) {
      // Update checks must never prevent local workstation use.
    }
  }

  Future<void> pair(String server, String code) async {
    busy = true;
    lastError = null;
    notifyListeners();
    WorkstationApi? next;
    try {
      next = WorkstationApi(server);
      final paired = await next.pair(code.trim());
      final remoteServer = paired['remoteServer']?.toString() ?? '';
      final remoteToken = paired['remoteToken']?.toString() ?? '';
      if (remoteServer.isNotEmpty && remoteToken.isNotEmpty) {
        final local = next;
        next = WorkstationApi(remoteServer, token: remoteToken);
        local.close();
      }
      await _store.save(next.server, next.token!);
      api?.close();
      api = next;
      await refresh();
    } catch (error) {
      next?.close();
      lastError = error.toString();
      rethrow;
    } finally {
      busy = false;
      notifyListeners();
    }
  }

  Future<void> refresh() async {
    final current = api;
    if (current == null) return;
    status = await current.status();
    try {
      update = await current.updateStatus();
    } catch (_) {
      update = const {};
    }
    notifyListeners();
  }

  Future<void> action(String value) async {
    final current = api;
    if (current == null) return;
    busy = true;
    notifyListeners();
    try {
      await current.action(value);
      await refresh();
    } finally {
      busy = false;
      notifyListeners();
    }
  }

  Future<void> disconnect() async {
    api?.close();
    api = null;
    status = const {};
    update = const {};
    await _store.clear();
    notifyListeners();
  }
}

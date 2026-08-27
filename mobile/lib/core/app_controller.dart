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
  bool get cloudIndependent => api != null && Uri.parse(api!.server).path == '/api/mobile-data';

  Future<void> restore() async {
    checkMobileUpdate();
    try {
      final session = await _store.load();
      if (session != null) {
        final saved = Uri.tryParse(session.server);
        final independentServer = saved?.path == '/api/mobile-relay'
            ? saved!.replace(path: '/api/mobile-data', query: null).toString()
            : session.server;
        api = WorkstationApi(independentServer, token: session.token);
        if (independentServer != session.server) {
          await _store.save(independentServer, session.token);
        }
        // A paired device account is durable. A slow or offline workstation must
        // not discard it or send the user back to the pairing screen.
        booting = false;
        notifyListeners();
        try {
          await refresh();
        } catch (error) {
          lastError = error.toString();
        }
      }
    } catch (error) {
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
        final relay = Uri.parse(remoteServer);
        final cloud = relay.replace(path: '/api/mobile-data', query: null);
        next = WorkstationApi(cloud.toString(), token: remoteToken);
        local.close();
      }
      await _store.save(next.server, next.token!);
      api?.close();
      api = next;
      next = null;
      try {
        await refresh();
      } catch (error) {
        // Pairing already created and saved the long-lived device account. Keep
        // it even if the first status refresh is temporarily unavailable.
        lastError = error.toString();
      }
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
    final values = await Future.wait<Map<String, dynamic>>([
      current.status(),
      current.updateStatus().catchError((_) => <String, dynamic>{}),
    ]);
    status = values[0];
    update = values[1];
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

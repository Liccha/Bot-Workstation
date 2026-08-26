import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class StoredSession {
  const StoredSession(this.server, this.token);
  final String server;
  final String token;
}

class SessionStore {
  static const _serverKey = 'workstation_server';
  static const _tokenKey = 'workstation_pair_token';
  // flutter_secure_storage 11 uses the platform's encrypted storage by
  // default. Keeping the default Android options also avoids the legacy
  // encryptedSharedPreferences migration path.
  final FlutterSecureStorage _storage = const FlutterSecureStorage();

  Future<StoredSession?> load() async {
    final values = await _storage.readAll();
    final server = values[_serverKey];
    final token = values[_tokenKey];
    if (server == null || token == null || server.isEmpty || token.isEmpty) {
      return null;
    }
    return StoredSession(server, token);
  }

  Future<void> save(String server, String token) async {
    await _storage.write(key: _serverKey, value: server);
    await _storage.write(key: _tokenKey, value: token);
  }

  Future<void> clear() => _storage.deleteAll();
}

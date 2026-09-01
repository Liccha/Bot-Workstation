import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;

const String domesticCloudHost =
    'songbotstic-api-cwpfgfkkpj.cn-beijing.fcapp.run';
const Set<String> _legacyCloudHosts = {
  'editor.teacharm.moe',
  'bot-editor.vercel.app',
};

class ApiException implements Exception {
  const ApiException(this.message, {this.statusCode, this.code});
  final String message;
  final int? statusCode;
  final String? code;
  @override
  String toString() => message;
}

class RecordPage {
  const RecordPage({
    required this.items,
    required this.total,
    required this.nextOffset,
    required this.hasMore,
  });

  final List<Map<String, dynamic>> items;
  final int total;
  final int nextOffset;
  final bool hasMore;
}

class WorkstationApi {
  WorkstationApi(String server, {this.token, http.Client? client})
    : server = normalizeServer(server),
      _client = client ?? http.Client();

  final String server;
  String? token;
  final http.Client _client;

  bool get _remoteRelay => Uri.parse(server).path == '/api/mobile-relay';
  bool get _remoteCloud => Uri.parse(server).path == '/api/mobile-data';

  static String normalizeServer(String raw) {
    var value = raw.trim();
    if (value.isEmpty) throw const ApiException('请输入电脑端显示的访问地址');
    if (!value.contains('://')) value = 'http://$value';
    final uri = Uri.tryParse(value);
    if (uri == null ||
        !{'http', 'https'}.contains(uri.scheme) ||
        uri.userInfo.isNotEmpty) {
      throw const ApiException('访问地址格式不正确');
    }
    final host = uri.host.toLowerCase();
    final trustedRelay =
        uri.scheme == 'https' &&
        {'/api/mobile-relay', '/api/mobile-data'}.contains(uri.path) &&
        !uri.hasPort &&
        ({domesticCloudHost, ..._legacyCloudHosts}.contains(host));
    if (trustedRelay) {
      final normalizedHost = _legacyCloudHosts.contains(host)
          ? domesticCloudHost
          : host;
      return Uri(
        scheme: 'https',
        host: normalizedHost,
        path: uri.path,
      ).toString();
    }
    final local =
        host == 'localhost' ||
        host == '127.0.0.1' ||
        host.startsWith('192.168.') ||
        host.startsWith('10.') ||
        _private172(host);
    if (!local) throw const ApiException('只允许连接局域网内的 Bot 工作站');
    final port = uri.hasPort ? uri.port : 8098;
    if (port != 8098) throw const ApiException('工作站端口应为 8098');
    return Uri(
      scheme: uri.scheme,
      host: host,
      port: port,
    ).toString().replaceAll(RegExp(r'/$'), '');
  }

  static bool _private172(String host) {
    final parts = host.split('.');
    if (parts.length != 4 || parts.first != '172') return false;
    final second = int.tryParse(parts[1]);
    return second != null && second >= 16 && second <= 31;
  }

  Uri _uri(String path, [Map<String, String>? query]) =>
      Uri.parse('$server$path').replace(queryParameters: query);

  Map<String, String> get _headers => {
    'Accept': 'application/json',
    'Content-Type': 'application/json; charset=utf-8',
    if (token != null && token!.isNotEmpty)
      'Authorization':
          '${_remoteRelay || _remoteCloud ? 'Device' : 'Bearer'} $token',
  };

  Future<Map<String, dynamic>> pair(String code) async {
    try {
      final ping = await _send('GET', '/api/ping');
      if (ping['ok'] != true || ping['pairing'] != true) {
        throw const ApiException('电脑端手机服务尚未完成启用');
      }
    } on ApiException catch (error) {
      if (error.statusCode == 404) {
        throw const ApiException('电脑端版本过旧，请先更新并重新打开工作站');
      }
      rethrow;
    }
    final normalizedCode = code.replaceAll(RegExp(r'\D'), '');
    if (normalizedCode.length != 6) {
      throw const ApiException('请输入电脑端显示的六位配对码');
    }
    final value = await _send(
      'POST',
      '/api/pair',
      body: {'code': normalizedCode, 'name': 'Bot 工作站手机端'},
    );
    token = value['token']?.toString();
    if (token == null || token!.isEmpty) {
      throw const ApiException('工作站没有返回配对令牌');
    }
    return value;
  }

  Future<Map<String, dynamic>> status() => _send('GET', '/api/status');
  Future<Map<String, dynamic>> serviceStatus() {
    if (!_remoteCloud) return status();
    return _direct(
      'GET',
      Uri.parse(server).replace(
        path: '/api/mobile-relay',
        queryParameters: const {'action': 'presence'},
      ),
      // Vercel cold starts and mobile carrier DNS can exceed one second even
      // when the resident Windows agent is healthy. Do not turn latency into
      // a false "offline" state.
      timeout: const Duration(seconds: 5),
    );
  }

  Future<Map<String, dynamic>> updateStatus() => _send('GET', '/api/update');
  Future<void> action(String action) async {
    if (_remoteCloud) {
      await _sendRemote(
        'POST',
        '/api/action',
        null,
        {'action': action},
        const Duration(seconds: 25),
        endpoint: Uri.parse(server).replace(path: '/api/mobile-relay'),
      );
      return;
    }
    await _send('POST', '/api/action', body: {'action': action});
  }

  Future<RecordPage> songPage(
    String query, {
    int offset = 0,
    int limit = 100,
  }) => _recordPage('/api/songs', query, offset: offset, limit: limit);

  Future<RecordPage> stablePage(
    String query, {
    int offset = 0,
    int limit = 100,
  }) => _recordPage('/api/stable', query, offset: offset, limit: limit);

  Future<RecordPage> _recordPage(
    String path,
    String query, {
    required int offset,
    required int limit,
  }) async {
    if (offset < 0 || limit < 1 || limit > 200) {
      throw const ApiException('分页参数异常');
    }
    Map<String, dynamic> data;
    try {
      data = await _send(
        'GET',
        path,
        query: {'q': query, 'offset': '$offset', 'limit': '$limit'},
      );
    } on ApiException catch (error) {
      if (!_remoteCloud || !_serverMutationFailure(error)) rethrow;
      data = await _snapshotPage(
        path == '/api/songs' ? 'songs' : 'stable',
        query,
        offset,
        limit,
      );
    }
    final items = _items(data);
    final total = int.tryParse('${data['total'] ?? ''}') ?? items.length;
    final nextOffset =
        int.tryParse('${data['nextOffset'] ?? ''}') ?? offset + items.length;
    final hasMore =
        items.isNotEmpty && (data['hasMore'] == true || nextOffset < total);
    return RecordPage(
      items: items,
      total: total,
      nextOffset: nextOffset,
      hasMore: hasMore,
    );
  }

  Future<Map<String, dynamic>> _snapshotPage(
    String dataset,
    String query,
    int offset,
    int limit,
  ) async {
    final ticket = await _direct(
      'GET',
      Uri.parse(server).replace(
        queryParameters: {'action': 'snapshot-ticket', 'dataset': dataset},
      ),
      timeout: const Duration(seconds: 15),
    );
    if (ticket['dataset'] != dataset || ticket['encoding'] != 'gzip-json') {
      throw const ApiException('云端曲库快照凭证无效');
    }
    final url = Uri.tryParse('${ticket['url'] ?? ''}');
    final expected = '/mobile-library/$dataset/current.json.gz';
    if (url == null ||
        url.scheme != 'https' ||
        !url.host.toLowerCase().endsWith('.aliyuncs.com') ||
        !url.path.endsWith(expected) ||
        url.userInfo.isNotEmpty) {
      throw const ApiException('云端曲库快照地址无效');
    }
    final response = await _client
        .get(url)
        .timeout(const Duration(seconds: 30));
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw ApiException(
        '云端曲库快照下载失败（${response.statusCode}）',
        statusCode: response.statusCode,
      );
    }
    if (response.bodyBytes.length > 8 * 1024 * 1024) {
      throw const ApiException('云端曲库压缩快照过大');
    }
    Map<String, dynamic> document;
    try {
      final decoded = gzip.decode(response.bodyBytes);
      if (decoded.length > 32 * 1024 * 1024) {
        throw const FormatException('snapshot too large');
      }
      document = Map<String, dynamic>.from(
        jsonDecode(utf8.decode(decoded)) as Map,
      );
    } catch (_) {
      throw const ApiException('云端曲库快照格式无效');
    }
    if ('${document['dataset'] ?? dataset}' != dataset) {
      throw const ApiException('云端曲库快照类型不匹配');
    }
    final needle = query.trim().toLowerCase();
    final all = _items(document);
    final filtered = needle.isEmpty
        ? all
        : all
              .where(
                (row) => row.values.any(
                  (value) => '$value'.toLowerCase().contains(needle),
                ),
              )
              .toList();
    final start = offset.clamp(0, filtered.length);
    final end = (start + limit).clamp(start, filtered.length);
    return {
      'revision': document['revision'] ?? 0,
      'items': filtered.sublist(start, end),
      'total': filtered.length,
      'nextOffset': end,
      'hasMore': end < filtered.length,
    };
  }

  Future<List<Map<String, dynamic>>> songs(String query) async {
    const pageSize = 200;
    final result = <Map<String, dynamic>>[];
    var offset = 0;
    for (var page = 0; page < 100; page++) {
      final pageData = await songPage(query, offset: offset, limit: pageSize);
      final batch = pageData.items;
      result.addAll(batch);
      if (batch.isEmpty || !pageData.hasMore) return result;
      offset = pageData.nextOffset;
    }
    throw const ApiException('歌曲分页数量异常，已停止继续读取');
  }

  Future<List<Map<String, dynamic>>> stable(String query) async {
    const pageSize = 200;
    final result = <Map<String, dynamic>>[];
    var offset = 0;
    for (var page = 0; page < 100; page++) {
      final pageData = await stablePage(query, offset: offset, limit: pageSize);
      final batch = pageData.items;
      result.addAll(batch);
      if (batch.isEmpty || !pageData.hasMore) return result;
      offset = pageData.nextOffset;
    }
    throw const ApiException('Stable 分页数量异常，已停止继续读取');
  }

  Future<void> updateSong(String id, Map<String, String> values) async {
    for (var attempt = 0; attempt < 2; attempt++) {
      try {
        await _send('POST', '/api/song', body: {'id': id, 'values': values});
        return;
      } on ApiException catch (error) {
        if (await _songWriteWasCommitted(id, values, error)) return;
        if (!_serverMutationFailure(error) || attempt > 0) rethrow;
        await Future<void>.delayed(const Duration(milliseconds: 300));
      }
    }
  }

  Future<void> deleteSong(String id) async {
    try {
      await _send('POST', '/api/song-delete', body: {'id': id});
    } on ApiException catch (error) {
      if (!await _songDeleteWasCommitted(id, error)) rethrow;
    }
  }

  Future<void> updateStable(String sid, Map<String, String> values) =>
      _send('POST', '/api/stable', body: {'sid': sid, 'values': values});

  Future<void> uploadSongAsset({
    required String id,
    required String type,
    required String filename,
    required List<int> bytes,
  }) async {
    if (!_remoteCloud) {
      throw const ApiException('资源上传需要使用长期设备账号，请在电脑旁重新配对一次');
    }
    final dot = filename.lastIndexOf('.');
    final extension = dot < 0 ? '' : filename.substring(dot).toLowerCase();
    final contentType = _contentType(extension);
    final ticket = await _direct(
      'POST',
      Uri.parse(server).replace(queryParameters: {'action': 'asset-ticket'}),
      body: {
        'type': type,
        'size': bytes.length,
        'extension': extension,
        'contentType': contentType,
      },
      timeout: const Duration(seconds: 30),
    );
    final uploadUrl = ticket['uploadUrl']?.toString() ?? '';
    final key = ticket['key']?.toString() ?? '';
    if (uploadUrl.isEmpty || key.isEmpty) {
      throw const ApiException('云端没有返回上传凭据');
    }
    final upload = await _client
        .put(
          Uri.parse(uploadUrl),
          headers: {'Content-Type': contentType},
          body: bytes,
        )
        .timeout(const Duration(minutes: 10));
    if (upload.statusCode < 200 || upload.statusCode >= 300) {
      throw ApiException(
        '资源上传失败（${upload.statusCode}）',
        statusCode: upload.statusCode,
      );
    }
    await _send(
      'POST',
      '/api/song-asset',
      body: {
        'id': id,
        'type': type,
        'key': key,
        'name': filename,
        'size': bytes.length,
      },
      timeout: const Duration(minutes: 30),
    );
  }

  static String _contentType(String extension) {
    switch (extension) {
      case '.jpg':
      case '.jpeg':
        return 'image/jpeg';
      case '.png':
        return 'image/png';
      case '.webp':
        return 'image/webp';
      case '.mp3':
        return 'audio/mpeg';
      case '.wav':
        return 'audio/wav';
      case '.flac':
        return 'audio/flac';
      case '.m4a':
        return 'audio/mp4';
      case '.ogg':
        return 'audio/ogg';
      default:
        return 'application/octet-stream';
    }
  }

  static List<Map<String, dynamic>> _items(Map<String, dynamic> data) =>
      (data['items'] as List? ?? const [])
          .whereType<Map>()
          .map((item) => Map<String, dynamic>.from(item))
          .toList();

  Future<Map<String, dynamic>> _send(
    String method,
    String path, {
    Map<String, String>? query,
    Map<String, dynamic>? body,
    Duration? timeout,
  }) async {
    try {
      if (_remoteRelay) {
        return await _sendRemote(method, path, query, body, timeout);
      }
      if (_remoteCloud) {
        return await _sendCloud(method, path, query, body, timeout);
      }
      final uri = _uri(path, query);
      return await _direct(
        method,
        uri,
        body: body,
        timeout: timeout ?? Duration(seconds: method == 'POST' ? 20 : 12),
      );
    } on ApiException {
      rethrow;
    } on TimeoutException {
      if (_remoteCloud) throw const ApiException('云端响应超时，请稍后重试');
      throw ApiException('连接 $server 超时，请确认电脑端工作站在线');
    } on SocketException {
      if (_remoteCloud) throw const ApiException('无法连接云端，请检查手机网络后重试');
      throw ApiException('无法访问 $server，请保持电脑端工作站开启，并检查网络或防火墙');
    } on http.ClientException {
      if (_remoteCloud) throw const ApiException('云端连接暂时不可用，请稍后重试');
      throw ApiException('无法访问 $server，请确认电脑端显示“手机端已启用”');
    } catch (error) {
      if (_remoteCloud) throw ApiException('云端请求失败：${error.runtimeType}');
      throw ApiException('连接电脑端失败：${error.runtimeType}');
    }
  }

  Future<Map<String, dynamic>> _sendCloud(
    String method,
    String path,
    Map<String, String>? query,
    Map<String, dynamic>? body,
    Duration? timeout,
  ) {
    const actions = <String, String>{
      'GET /api/status': 'status',
      'GET /api/update': 'status',
      'GET /api/songs': 'songs',
      'GET /api/stable': 'stable',
      'POST /api/song': 'song',
      'POST /api/song-delete': 'song-delete',
      'POST /api/stable': 'stable',
      'POST /api/song-asset': 'song-asset',
    };
    final action = actions['$method $path'];
    if (action == null) {
      throw const ApiException('此操作依赖本机进程，云端独立模式下不可用');
    }
    return _direct(
      method,
      Uri.parse(server).replace(queryParameters: {'action': action, ...?query}),
      body: body,
      timeout: timeout ?? Duration(seconds: method == 'POST' ? 30 : 15),
    );
  }

  Future<bool> _songWriteWasCommitted(
    String id,
    Map<String, String> values,
    ApiException error,
  ) async {
    if (!_uncertainMutation(error)) return false;
    final song = await _findSongAfterUncertainWrite(
      id,
      attempts: _serverMutationFailure(error) ? 1 : 3,
    );
    if (song == null) return false;
    for (final entry in values.entries) {
      final actualKey = song.keys
          .where((key) => key.toLowerCase() == entry.key.toLowerCase())
          .firstOrNull;
      if (actualKey == null || '${song[actualKey] ?? ''}' != entry.value) {
        return false;
      }
    }
    return true;
  }

  Future<bool> _songDeleteWasCommitted(String id, ApiException error) async {
    if (!_uncertainMutation(error)) return false;
    try {
      return await _findSongAfterUncertainWrite(
            id,
            attempts: _serverMutationFailure(error) ? 1 : 3,
            failClosed: true,
          ) ==
          null;
    } on ApiException {
      return false;
    }
  }

  bool _uncertainMutation(ApiException error) =>
      _serverMutationFailure(error) ||
      error.message.contains('超时') ||
      error.message.contains('连接暂时不可用') ||
      error.message.contains('无法连接云端');

  bool _serverMutationFailure(ApiException error) =>
      error.statusCode != null &&
      error.statusCode! >= 500 &&
      error.statusCode! < 600;

  Future<Map<String, dynamic>?> _findSongAfterUncertainWrite(
    String id, {
    int attempts = 3,
    bool failClosed = false,
  }) async {
    for (var attempt = 0; attempt < attempts; attempt++) {
      if (attempt > 0) {
        await Future<void>.delayed(Duration(milliseconds: 400 * attempt));
      }
      try {
        if (_remoteCloud) {
          try {
            return await _direct(
              'GET',
              Uri.parse(server)
                  .replace(queryParameters: {'action': 'song-item', 'id': id}),
              timeout: const Duration(seconds: 15),
            );
          } on ApiException catch (error) {
            if (error.statusCode == 404) return null;
            if (error.statusCode != 400 && error.statusCode != 405) rethrow;
          }
        }
        final page = await songPage(id, offset: 0, limit: 100);
        for (final item in page.items) {
          final idKey = item.keys
              .where((key) => key.toLowerCase() == 'id')
              .firstOrNull;
          if (idKey != null && '${item[idKey] ?? ''}'.trim() == id.trim()) {
            return item;
          }
        }
        return null;
      } on ApiException {
        if (attempt == attempts - 1) {
          if (failClosed) rethrow;
          return null;
        }
      }
    }
    return null;
  }

  Future<Map<String, dynamic>> _sendRemote(
    String method,
    String path,
    Map<String, String>? query,
    Map<String, dynamic>? body,
    Duration? timeout, {
    Uri? endpoint,
  }) async {
    final relay = endpoint ?? Uri.parse(server);
    final submitted = await _direct(
      'POST',
      relay.replace(queryParameters: {'action': 'submit'}),
      body: {
        'method': method,
        'path': path,
        'query': query ?? const {},
        'body': body ?? const {},
      },
      timeout: const Duration(seconds: 30),
    );
    final id = submitted['id']?.toString() ?? '';
    if (id.isEmpty) throw const ApiException('云端没有返回操作编号');
    final deadline = DateTime.now().add(timeout ?? const Duration(seconds: 45));
    while (DateTime.now().isBefore(deadline)) {
      await Future<void>.delayed(const Duration(milliseconds: 450));
      final result = await _direct(
        'GET',
        relay.replace(queryParameters: {'action': 'result', 'id': id}),
        accept: const {200, 202},
        timeout: const Duration(seconds: 20),
      );
      if (result['state'] != 'complete') continue;
      final response = Map<String, dynamic>.from(
        result['response'] as Map? ?? const {},
      );
      final status = int.tryParse('${response['status'] ?? 500}') ?? 500;
      final value = Map<String, dynamic>.from(
        response['body'] as Map? ?? const {},
      );
      if (status < 200 || status >= 300) {
        throw ApiException(
          value['error']?.toString() ?? '工作站操作失败（$status）',
          statusCode: status,
          code: value['code']?.toString(),
        );
      }
      return value;
    }
    throw const ApiException('电脑端尚未响应，请确认 Bot 工作站保持开启');
  }

  Future<Map<String, dynamic>> _direct(
    String method,
    Uri uri, {
    Map<String, dynamic>? body,
    Duration timeout = const Duration(seconds: 20),
    Set<int> accept = const {},
  }) async {
    late http.Response response;
    if (method == 'POST') {
      response = await _client
          .post(uri, headers: _headers, body: jsonEncode(body ?? const {}))
          .timeout(timeout);
    } else {
      response = await _client.get(uri, headers: _headers).timeout(timeout);
    }
    Map<String, dynamic> value = {};
    if (response.bodyBytes.isNotEmpty) {
      try {
        final decoded = jsonDecode(utf8.decode(response.bodyBytes));
        if (decoded is Map) value = Map<String, dynamic>.from(decoded);
      } on FormatException {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          throw const ApiException('服务响应格式异常');
        }
      }
    }
    final accepted = accept.isEmpty
        ? response.statusCode >= 200 && response.statusCode < 300
        : accept.contains(response.statusCode);
    if (!accepted) {
      throw ApiException(
        value['error']?.toString() ?? '请求失败（${response.statusCode}）',
        statusCode: response.statusCode,
        code: value['code']?.toString(),
      );
    }
    return value;
  }

  void close() => _client.close();
}

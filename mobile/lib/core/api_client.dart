import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;

class ApiException implements Exception {
  const ApiException(this.message, {this.statusCode});
  final String message;
  final int? statusCode;
  @override
  String toString() => message;
}

class WorkstationApi {
  WorkstationApi(String server, {this.token, http.Client? client})
    : server = normalizeServer(server),
      _client = client ?? http.Client();

  final String server;
  String? token;
  final http.Client _client;

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
    if (token != null && token!.isNotEmpty) 'Authorization': 'Bearer $token',
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
      body: {'code': normalizedCode},
    );
    token = value['token']?.toString();
    if (token == null || token!.isEmpty) {
      throw const ApiException('工作站没有返回配对令牌');
    }
    return value;
  }

  Future<Map<String, dynamic>> status() => _send('GET', '/api/status');
  Future<Map<String, dynamic>> updateStatus() => _send('GET', '/api/update');
  Future<void> action(String action) =>
      _send('POST', '/api/action', body: {'action': action});

  Future<List<Map<String, dynamic>>> songs(String query) async {
    final data = await _send(
      'GET',
      '/api/songs',
      query: {'q': query, 'limit': '100'},
    );
    return _items(data);
  }

  Future<List<Map<String, dynamic>>> stable(String query) async {
    final data = await _send(
      'GET',
      '/api/stable',
      query: {'q': query, 'limit': '120'},
    );
    return _items(data);
  }

  Future<void> updateSong(String id, Map<String, String> values) =>
      _send('POST', '/api/song', body: {'id': id, 'values': values});

  Future<void> updateStable(String sid, Map<String, String> values) =>
      _send('POST', '/api/stable', body: {'sid': sid, 'values': values});

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
  }) async {
    try {
      final uri = _uri(path, query);
      late http.Response response;
      if (method == 'POST') {
        response = await _client
            .post(uri, headers: _headers, body: jsonEncode(body ?? const {}))
            .timeout(const Duration(seconds: 20));
      } else {
        response = await _client
            .get(uri, headers: _headers)
            .timeout(const Duration(seconds: 12));
      }
      Map<String, dynamic> value = {};
      if (response.bodyBytes.isNotEmpty) {
        try {
          final decoded = jsonDecode(utf8.decode(response.bodyBytes));
          if (decoded is Map) value = Map<String, dynamic>.from(decoded);
        } on FormatException {
          if (response.statusCode >= 200 && response.statusCode < 300) {
            throw const ApiException('电脑端响应格式异常，请确认填写的是工作站地址');
          }
        }
      }
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw ApiException(
          value['error']?.toString() ?? '请求失败（${response.statusCode}）',
          statusCode: response.statusCode,
        );
      }
      return value;
    } on ApiException {
      rethrow;
    } on TimeoutException {
      throw ApiException('连接 $server 超时，请确认手机与电脑连接同一 Wi-Fi，且路由器未开启设备隔离');
    } on SocketException {
      throw ApiException('无法访问 $server，请保持电脑端工作站开启，并检查局域网或防火墙');
    } on http.ClientException {
      throw ApiException('无法访问 $server，请确认手机与电脑连接同一 Wi-Fi，且电脑端显示“手机端已启用”');
    } catch (error) {
      throw ApiException('连接电脑端失败：${error.runtimeType}');
    }
  }

  void close() => _client.close();
}

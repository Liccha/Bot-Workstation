import 'dart:convert';
import 'dart:io';

import 'package:bot_workstation_mobile/core/api_client.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  test('HTTP 500 list falls back to the signed compact snapshot', () async {
    final snapshot = gzip.encode(
      utf8.encode(
        jsonEncode({
          'dataset': 'songs',
          'revision': 71,
          'columns': ['id', 'song_name', 'author'],
          'items': [
            {'id': '1285', 'song_name': '旧记录', 'author': 'A'},
            {'id': '1286', 'song_name': '直连快照歌曲', 'author': 'B'},
          ],
        }),
      ),
    );
    final client = MockClient((request) async {
      if (request.url.host == domesticCloudHost &&
          request.url.queryParameters['action'] == 'songs') {
        return http.Response(
          jsonEncode({'error': 'internal'}),
          500,
          headers: {'content-type': 'application/json'},
        );
      }
      if (request.url.host == domesticCloudHost &&
          request.url.queryParameters['action'] == 'snapshot-ticket') {
        return http.Response(
          jsonEncode({
            'dataset': 'songs',
            'encoding': 'gzip-json',
            'url': 'https://bucket.oss-cn-beijing.aliyuncs.com/mobile-library/songs/current.json.gz?signature=test',
          }),
          200,
          headers: {'content-type': 'application/json'},
        );
      }
      if (request.url.host == 'bucket.oss-cn-beijing.aliyuncs.com') {
        return http.Response.bytes(
          snapshot,
          200,
          headers: {'content-type': 'application/gzip'},
        );
      }
      throw StateError('unexpected request: ${request.url}');
    });
    final api = WorkstationApi(
      'https://editor.teacharm.moe/api/mobile-data',
      token: 'device-token',
      client: client,
    );
    final page = await api.songPage('直连', offset: 0, limit: 100);
    expect(page.total, 1);
    expect(page.items.single['id'], '1286');
    expect(page.items.single['song_name'], '直连快照歌曲');
    expect(page.hasMore, isFalse);
  });
}

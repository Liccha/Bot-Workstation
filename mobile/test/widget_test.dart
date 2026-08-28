import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:bot_workstation_mobile/core/api_client.dart';
import 'package:bot_workstation_mobile/core/app_controller.dart';
import 'package:bot_workstation_mobile/core/mobile_update_service.dart';
import 'package:bot_workstation_mobile/core/session_store.dart';
import 'package:bot_workstation_mobile/main.dart';
import 'package:bot_workstation_mobile/screens/dashboard_screen.dart';
import 'package:bot_workstation_mobile/screens/records_screen.dart';
import 'package:bot_workstation_mobile/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:crypto/crypto.dart';

void main() {
  group('WorkstationApi.normalizeServer', () {
    test('accepts private LAN hosts and fixes the port', () {
      expect(
        WorkstationApi.normalizeServer('192.168.1.8'),
        'http://192.168.1.8:8098',
      );
      expect(
        WorkstationApi.normalizeServer('http://10.2.3.4:8098/'),
        'http://10.2.3.4:8098',
      );
      expect(
        WorkstationApi.normalizeServer('http://172.31.2.9:8098'),
        'http://172.31.2.9:8098',
      );
    });

    test('rejects public, wrong-port and credential-bearing URLs', () {
      for (final address in [
        'https://example.com:8098',
        'http://192.168.1.8:8080',
        'http://user:pass@192.168.1.8:8098',
        'http://172.32.1.1:8098',
      ]) {
        expect(
          () => WorkstationApi.normalizeServer(address),
          throwsA(isA<ApiException>()),
        );
      }
    });

    test('accepts only the two fixed HTTPS relay endpoints', () {
      expect(
        WorkstationApi.normalizeServer(
          'https://editor.teacharm.moe/api/mobile-relay',
        ),
        'https://editor.teacharm.moe/api/mobile-relay',
      );
      expect(
        () => WorkstationApi.normalizeServer(
          'https://editor.teacharm.moe/api/announcement-cloud',
        ),
        throwsA(isA<ApiException>()),
      );
    });
  });

  test('long-term device token relays a request without LAN access', () async {
    final requests = <http.Request>[];
    final api = WorkstationApi(
      'https://editor.teacharm.moe/api/mobile-relay',
      token: 'device-id.device-secret',
      client: MockClient((request) async {
        requests.add(request);
        expect(
          request.headers['authorization'],
          'Device device-id.device-secret',
        );
        if (request.url.queryParameters['action'] == 'submit') {
          expect(request.body, contains('/api/status'));
          return http.Response('{"id":"request-id","state":"pending"}', 202);
        }
        return http.Response(
          '{"id":"request-id","state":"complete","response":{"status":200,"body":{"songBot":"running"}}}',
          200,
        );
      }),
    );
    expect((await api.status())['songBot'], 'running');
    expect(requests.length, 2);
  });

  test('cloud-independent account can relay service controls to the resident agent', () async {
    final requests = <http.Request>[];
    final api = WorkstationApi(
      'https://editor.teacharm.moe/api/mobile-data',
      token: 'device-id.device-secret',
      client: MockClient((request) async {
        requests.add(request);
        expect(request.url.path, '/api/mobile-relay');
        expect(
          request.headers['authorization'],
          'Device device-id.device-secret',
        );
        if (request.url.queryParameters['action'] == 'submit') {
          expect(request.body, contains('songbot.start'));
          return http.Response('{"id":"control-id","state":"pending"}', 202);
        }
        return http.Response(
          '{"id":"control-id","state":"complete","response":{"status":200,"body":{"ok":true}}}',
          200,
        );
      }),
    );
    await api.action('songbot.start');
    expect(requests.length, 2);
  });

  test('cloud-independent refresh reads cached presence without queueing a command', () async {
    final requests = <http.Request>[];
    final controller = AppController(_EmptySessionStore())
      ..api = WorkstationApi(
        'https://editor.teacharm.moe/api/mobile-data',
        token: 'device-id.device-secret',
        client: MockClient((request) async {
          requests.add(request);
          if (request.url.path == '/api/mobile-data') {
            return http.Response(
              '{"songs":{"total":1272},"stable":{"total":142},"writeLocked":false}',
              200,
            );
          }
          if (request.url.queryParameters['action'] == 'presence') {
            return http.Response(
              '{"workstationOnline":true,"songBot":"running","napCat":"stopped","dailyAutomation":false}',
              200,
            );
          }
          return http.Response('{"error":"unexpected request"}', 500);
        }),
      );
    final stopwatch = Stopwatch()..start();
    await controller.refresh();
    stopwatch.stop();
    expect(controller.status['songBot'], 'running');
    expect(controller.status['napCat'], 'stopped');
    expect(controller.status['dailyAutomation'], isFalse);
    expect(stopwatch.elapsed, lessThan(const Duration(seconds: 1)));
    expect(
      requests.where((item) => item.url.queryParameters['action'] == 'submit'),
      isEmpty,
    );
  });

  test(
    'pairing preflights the LAN endpoint and normalizes the displayed code',
    () async {
      final paths = <String>[];
      final api = WorkstationApi(
        '192.168.1.8',
        client: MockClient((request) async {
          paths.add(request.url.path);
          if (request.url.path == '/api/ping') {
            return http.Response('{"ok":true,"pairing":true}', 200);
          }
          expect(request.body, contains('123456'));
          return http.Response('{"token":"paired-token"}', 200);
        }),
      );
      await api.pair('12 34 56');
      expect(paths, ['/api/ping', '/api/pair']);
      expect(api.token, 'paired-token');
    },
  );

  group('MobileUpdateService.compareVersions', () {
    test('detects only genuinely newer versions', () {
      expect(
        MobileUpdateService.compareVersions('1.0.2', '1.0.1'),
        greaterThan(0),
      );
      expect(MobileUpdateService.compareVersions('1.0.2', '1.0.2'), 0);
      expect(
        MobileUpdateService.compareVersions('1.0.1', '1.0.2'),
        lessThan(0),
      );
    });

    test('recognizes a previously verified APK for reuse', () async {
      final directory = await Directory.systemTemp.createTemp(
        'bot-update-test-',
      );
      addTearDown(() => directory.delete(recursive: true));
      final file = File('${directory.path}/cached.apk');
      final bytes = utf8.encode('verified mobile package');
      await file.writeAsBytes(bytes);
      final release = MobileRelease(
        version: '9.9.9',
        url: Uri.parse(
          'https://assets.teacharm.moe/bot-workstation/mobile/releases/9.9.9/test.apk',
        ),
        sha256: sha256.convert(bytes).toString(),
        notes: '',
        size: bytes.length,
      );
      expect(
        await MobileUpdateService.isValidCachedPackage(file, release),
        isTrue,
      );
    });
  });

  testWidgets('shows the mobile update prompt before pairing', (tester) async {
    final controller = AppController(
      _EmptySessionStore(),
      mobileUpdates: _NewReleaseService(),
    );
    await tester.pumpWidget(BotWorkstationApp(controller: controller));
    await controller.restore();
    await tester.pumpAndSettle();
    expect(find.text('发现新版本 9.9.9'), findsOneWidget);
    expect(find.text('一键更新'), findsOneWidget);
    expect(find.text('连接 Bot 工作站'), findsOneWidget);
  });

  testWidgets('cloud-independent dashboard keeps SongBot and NapCat controls', (
    tester,
  ) async {
    final controller = AppController(_EmptySessionStore())
      ..api = WorkstationApi(
        'https://editor.teacharm.moe/api/mobile-data',
        token: 'device-id.device-secret',
      )
      ..status = {
        'songs': {'total': 1272},
        'stable': {'total': 142},
        'songBot': 'running',
        'napCat': 'stopped',
        'workstationOnline': true,
        'dailyAutomation': false,
      };
    await tester.pumpWidget(
      MaterialApp(home: DashboardScreen(controller: controller)),
    );
    expect(find.text('云端独立运行'), findsOneWidget);
    expect(find.text('SongBot'), findsOneWidget);
    expect(find.text('NapCat'), findsOneWidget);
    expect(find.text('每日歌曲与竞猜'), findsOneWidget);
    expect(find.text('当前已关闭'), findsOneWidget);
    expect(find.text('启用'), findsNWidgets(2));
    expect(find.text('停用'), findsNWidgets(2));
    expect(find.text('已启用'), findsOneWidget);
    expect(find.text('未启用'), findsOneWidget);
    final startButtons = tester
        .widgetList<FilledButton>(find.widgetWithText(FilledButton, '启用'))
        .toList();
    final stopButtons = tester
        .widgetList<OutlinedButton>(find.widgetWithText(OutlinedButton, '停用'))
        .toList();
    expect(startButtons[0].onPressed, isNull);
    expect(startButtons[1].onPressed, isNotNull);
    expect(stopButtons[0].onPressed, isNotNull);
    expect(stopButtons[1].onPressed, isNull);
  });

  testWidgets(
    'song editor keeps raw difficulty names and first label visible',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(390, 844);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      final api = WorkstationApi(
        'http://192.168.1.8:8098',
        token: 'paired-token',
        client: MockClient(
          (_) async => http.Response(
            '{"items":[{"id":"2","song_name":"星间旅行","author":"HOYO-MiX","charter":"Furina","4k_ez":"4-436","4k_nm":"6-697","4k_hd":"8-958","4k_mx":"","4k_sp":""}],"total":1,"hasMore":false}',
            200,
            headers: const {'content-type': 'application/json; charset=utf-8'},
          ),
        ),
      );
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.light(),
          home: Scaffold(
            body: RecordsScreen(api: api, type: RecordType.song),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('星间旅行'));
      await tester.pumpAndSettle();

      final labelRect = tester.getRect(find.text('歌名'));
      final listRect = tester.getRect(find.byType(ListView).last);
      expect(labelRect.top, greaterThanOrEqualTo(listRect.top + 1));
      for (final field in const ['4k_ez', '4k_nm', '4k_hd', '4k_mx', '4k_sp']) {
        expect(find.text(field), findsOneWidget);
      }
      expect(find.text('4K 简单'), findsNothing);
    },
  );

  testWidgets('song list fetches one 100-row page and appends on scroll', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    final requests = <Uri>[];
    final api = WorkstationApi(
      'http://192.168.1.8:8098',
      token: 'paired-token',
      client: MockClient((request) async {
        requests.add(request.url);
        final offset = int.parse(request.url.queryParameters['offset']!);
        final limit = int.parse(request.url.queryParameters['limit']!);
        const total = 250;
        final count = (total - offset).clamp(0, limit);
        final items = List.generate(
          count,
          (index) => {
            'id': '${offset + index + 1}',
            'song_name': '歌曲 ${offset + index + 1}',
            'author': '作者',
            'charter': '谱师',
          },
        );
        return http.Response(
          '{"items":${jsonEncode(items)},"total":$total,'
          '"offset":$offset,"nextOffset":${offset + count},'
          '"hasMore":${offset + count < total}}',
          200,
          headers: const {'content-type': 'application/json; charset=utf-8'},
        );
      }),
    );
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.light(),
        home: Scaffold(
          body: RecordsScreen(api: api, type: RecordType.song),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(requests, hasLength(1));
    expect(requests.single.queryParameters['offset'], '0');
    expect(requests.single.queryParameters['limit'], '100');
    expect(find.text('歌曲 1'), findsOneWidget);

    await tester.drag(find.byType(ListView), const Offset(0, -12000));
    await tester.pumpAndSettle();
    expect(requests, hasLength(2));
    expect(requests.last.queryParameters['offset'], '100');
    expect(requests.last.queryParameters['limit'], '100');
  });
}

class _EmptySessionStore extends SessionStore {
  @override
  Future<StoredSession?> load() async => null;
}

class _NewReleaseService extends MobileUpdateService {
  @override
  Future<MobileRelease?> check() async => MobileRelease(
    version: '9.9.9',
    url: Uri.parse(
      'https://assets.teacharm.moe/bot-workstation/mobile/releases/9.9.9/test.apk',
    ),
    sha256: ''.padLeft(64, '0'),
    notes: '测试更新',
    size: 1024,
  );
}

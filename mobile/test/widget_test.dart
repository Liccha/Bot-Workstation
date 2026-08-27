import 'package:flutter_test/flutter_test.dart';
import 'package:bot_workstation_mobile/core/api_client.dart';
import 'package:bot_workstation_mobile/core/app_controller.dart';
import 'package:bot_workstation_mobile/core/mobile_update_service.dart';
import 'package:bot_workstation_mobile/core/session_store.dart';
import 'package:bot_workstation_mobile/main.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

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

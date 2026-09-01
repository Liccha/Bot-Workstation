import 'dart:convert';

import 'package:bot_workstation_mobile/core/api_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:bot_workstation_mobile/screens/records_screen.dart';
import 'package:bot_workstation_mobile/theme/app_theme.dart';

void main() {
  group('song cloud write recovery', () {
    test(
      'accepts a 503 write_busy response when read-back confirms commit',
      () async {
        var posts = 0;
        var exactGets = 0;
        final api = WorkstationApi(
          'https://editor.teacharm.moe/api/mobile-data',
          token: 'device-id.device-secret',
          client: MockClient((request) async {
            final action = request.url.queryParameters['action'];
            if (request.method == 'POST' && action == 'song') {
              posts++;
              return http.Response(
                '{"error":"cloud write busy","code":"write_busy"}',
                503,
                headers: const {'content-type': 'application/json'},
              );
            }
            if (request.method == 'GET' && action == 'song-item') {
              exactGets++;
              expect(request.url.queryParameters['id'], '1273');
              return http.Response(
                '{"id":"1273","album_ids":"210"}',
                200,
                headers: const {'content-type': 'application/json'},
              );
            }
            return http.Response('{"error":"unexpected"}', 500);
          }),
        );

        await api.updateSong('1273', const {'album_ids': '210'});

        expect(posts, 1);
        expect(exactGets, 1);
      },
    );

    for (final legacyStatus in const [400, 405]) {
      test(
        'falls back to songs on legacy HTTP $legacyStatus and retries once',
        () async {
          var posts = 0;
          var exactGets = 0;
          var fallbackGets = 0;
          final api = WorkstationApi(
            'https://editor.teacharm.moe/api/mobile-data',
            token: 'device-id.device-secret',
            client: MockClient((request) async {
              final action = request.url.queryParameters['action'];
              if (request.method == 'POST' && action == 'song') {
                posts++;
                if (posts == 1) {
                  return http.Response(
                    '{"error":"cloud data not initialized"}',
                    503,
                    headers: const {'content-type': 'application/json'},
                  );
                }
                return http.Response('{"ok":true}', 200);
              }
              if (request.method == 'GET' && action == 'song-item') {
                exactGets++;
                return http.Response(
                  '{"error":"unknown action"}',
                  legacyStatus,
                );
              }
              if (request.method == 'GET' && action == 'songs') {
                fallbackGets++;
                return http.Response(
                  '{"items":[{"id":"1273","album_ids":"209"}],'
                  '"total":1,"hasMore":false,"nextOffset":1}',
                  200,
                  headers: const {'content-type': 'application/json'},
                );
              }
              return http.Response('{"error":"unexpected"}', 500);
            }),
          );

          await api.updateSong('1273', const {'album_ids': '210'});

          expect(posts, 2);
          expect(exactGets, 1);
          expect(fallbackGets, 1);
        },
      );
    }

    test(
      'accepts an HTTP 500 response when exact read-back confirms commit',
      () async {
        var posts = 0;
        var exactGets = 0;
        final api = WorkstationApi(
          'https://editor.teacharm.moe/api/mobile-data',
          token: 'device-id.device-secret',
          client: MockClient((request) async {
            final action = request.url.queryParameters['action'];
            if (request.method == 'POST' && action == 'song') {
              posts++;
              return http.Response('{"error":"marker write failed"}', 500);
            }
            if (request.method == 'GET' && action == 'song-item') {
              exactGets++;
              return http.Response(
                '{"id":"1273","album_ids":"210"}',
                200,
                headers: const {'content-type': 'application/json'},
              );
            }
            return http.Response('{"error":"unexpected"}', 500);
          }),
        );

        await api.updateSong('1273', const {'album_ids': '210'});

        expect(posts, 1);
        expect(exactGets, 1);
      },
    );

    test('does not retry write_busy more than once', () async {
      var posts = 0;
      final api = WorkstationApi(
        'https://editor.teacharm.moe/api/mobile-data',
        token: 'device-id.device-secret',
        client: MockClient((request) async {
          final action = request.url.queryParameters['action'];
          if (request.method == 'POST' && action == 'song') {
            posts++;
            return http.Response(
              '{"error":"cloud write busy","code":"write_busy"}',
              503,
              headers: const {'content-type': 'application/json'},
            );
          }
          if (request.method == 'GET' && action == 'song-item') {
            return http.Response(
              '{"id":"1273","album_ids":"209"}',
              200,
              headers: const {'content-type': 'application/json'},
            );
          }
          return http.Response('{"error":"unexpected"}', 500);
        }),
      );

      await expectLater(
        api.updateSong('1273', const {'album_ids': '210'}),
        throwsA(isA<ApiException>()),
      );
      expect(posts, 2);
    });

    test('does not read back or retry a rejected HTTP 4xx mutation', () async {
      var posts = 0;
      var gets = 0;
      final api = WorkstationApi(
        'https://editor.teacharm.moe/api/mobile-data',
        token: 'device-id.device-secret',
        client: MockClient((request) async {
          if (request.method == 'POST') {
            posts++;
            return http.Response('{"error":"forbidden"}', 403);
          }
          gets++;
          return http.Response('{"error":"unexpected"}', 500);
        }),
      );

      await expectLater(
        api.updateSong('1273', const {'album_ids': '210'}),
        throwsA(
          isA<ApiException>().having(
            (error) => error.statusCode,
            'statusCode',
            403,
          ),
        ),
      );
      expect(posts, 1);
      expect(gets, 0);
    });

    test('treats exact HTTP 404 as a committed delete', () async {
      var posts = 0;
      var exactGets = 0;
      final api = WorkstationApi(
        'https://editor.teacharm.moe/api/mobile-data',
        token: 'device-id.device-secret',
        client: MockClient((request) async {
          final action = request.url.queryParameters['action'];
          if (request.method == 'POST' && action == 'song-delete') {
            posts++;
            return http.Response(
              '{"error":"cloud write busy","code":"write_busy"}',
              503,
              headers: const {'content-type': 'application/json'},
            );
          }
          if (request.method == 'GET' && action == 'song-item') {
            exactGets++;
            return http.Response('{"error":"record not found"}', 404);
          }
          return http.Response('{"error":"unexpected"}', 500);
        }),
      );

      await api.deleteSong('1273');

      expect(posts, 1);
      expect(exactGets, 1);
    });

    test('does not mistake a failed delete read-back for HTTP 404', () async {
      final api = WorkstationApi(
        'https://editor.teacharm.moe/api/mobile-data',
        token: 'device-id.device-secret',
        client: MockClient((request) async {
          final action = request.url.queryParameters['action'];
          if (request.method == 'POST' && action == 'song-delete') {
            return http.Response(
              '{"error":"cloud write busy","code":"write_busy"}',
              503,
              headers: const {'content-type': 'application/json'},
            );
          }
          if (request.method == 'GET' && action == 'song-item') {
            return http.Response('{"error":"temporary failure"}', 500);
          }
          return http.Response('{"error":"unexpected"}', 500);
        }),
      );

      await expectLater(
        api.deleteSong('1273'),
        throwsA(
          isA<ApiException>().having(
            (error) => error.statusCode,
            'statusCode',
            503,
          ),
        ),
      );
    });
  });

  testWidgets('song editor submits only fields changed by this user', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    Map<String, dynamic>? submitted;
    final api = WorkstationApi(
      'https://editor.teacharm.moe/api/mobile-data',
      token: 'device-id.device-secret',
      client: MockClient((request) async {
        final action = request.url.queryParameters['action'];
        if (request.method == 'POST' && action == 'song') {
          submitted = Map<String, dynamic>.from(
            jsonDecode(request.body) as Map,
          );
          return http.Response('{"ok":true}', 200);
        }
        if (request.method == 'GET' && action == 'songs') {
          return http.Response(
            '{"items":[{"id":"1273","song_name":"I Want You",'
            '"author":"Lin-G","charter":"Liccha","bpm":"140",'
            '"album_ids":"209"}],"total":1,"hasMore":false}',
            200,
            headers: const {'content-type': 'application/json'},
          );
        }
        return http.Response('{"error":"unexpected"}', 500);
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
    await tester.tap(find.text('I Want You'));
    await tester.pumpAndSettle();

    final albumIdField = find.byWidgetPredicate(
      (widget) =>
          widget is TextField && widget.decoration?.labelText == '专辑 ID',
    );
    expect(albumIdField, findsOneWidget);
    await tester.enterText(albumIdField, '210');
    await tester.tap(find.text('保存修改'));
    await tester.pumpAndSettle();

    expect(submitted?['id'], '1273');
    expect(submitted?['values'], const {'album_ids': '210'});
  });
}

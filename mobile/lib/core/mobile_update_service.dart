import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:convert/convert.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:path_provider/path_provider.dart';

class MobileRelease {
  const MobileRelease({
    required this.version,
    required this.url,
    required this.sha256,
    required this.notes,
    required this.size,
  });

  final String version;
  final Uri url;
  final String sha256;
  final String notes;
  final int size;
}

class MobileUpdateService {
  static final Uri _manifest = Uri.parse(
    'https://assets.teacharm.moe/bot-workstation/mobile/latest.json',
  );

  Future<MobileRelease?> check() async {
    final response = await http
        .get(_manifest, headers: const {'Accept': 'application/json'})
        .timeout(const Duration(seconds: 8));
    if (response.statusCode != 200) return null;
    final decoded = jsonDecode(utf8.decode(response.bodyBytes));
    if (decoded is! Map) return null;
    final value = Map<String, dynamic>.from(decoded);
    final version = value['version']?.toString().trim() ?? '';
    final url = Uri.tryParse(value['url']?.toString() ?? '');
    final sha256 = value['sha256']?.toString().toLowerCase() ?? '';
    final size = int.tryParse('${value['size'] ?? ''}') ?? -1;
    if (!RegExp(r'^\d+(?:\.\d+){1,3}$').hasMatch(version) ||
        url == null ||
        url.scheme != 'https' ||
        url.host != 'assets.teacharm.moe' ||
        !url.path.startsWith('/bot-workstation/mobile/') ||
        !url.path.toLowerCase().endsWith('.apk') ||
        !RegExp(r'^[0-9a-f]{64}$').hasMatch(sha256) ||
        size < 1 ||
        size > 512 * 1024 * 1024) {
      return null;
    }
    final current = (await PackageInfo.fromPlatform()).version;
    if (compareVersions(version, current) <= 0) return null;
    return MobileRelease(
      version: version,
      url: url,
      sha256: sha256,
      notes: value['notes']?.toString().trim() ?? '',
      size: size,
    );
  }

  Future<void> downloadAndInstall(
    MobileRelease release, {
    void Function(double progress)? onProgress,
  }) async {
    final request = http.Request('GET', release.url)
      ..headers['Accept'] = 'application/vnd.android.package-archive';
    final response = await request.send().timeout(const Duration(seconds: 20));
    if (response.statusCode != 200) {
      throw StateError('更新包下载失败（${response.statusCode}）');
    }
    final declared = response.contentLength;
    if (declared != null && declared != release.size) {
      throw StateError('更新包大小与版本清单不一致');
    }
    final directory = await getTemporaryDirectory();
    final output = File('${directory.path}/BotWorkstation-Mobile-${release.version}.apk');
    final sink = output.openWrite();
    final digest = AccumulatorSink<Digest>();
    final hashSink = sha256.startChunkedConversion(digest);
    var received = 0;
    try {
      await for (final chunk in response.stream.timeout(const Duration(seconds: 45))) {
        received += chunk.length;
        if (received > release.size || received > 512 * 1024 * 1024) {
          throw StateError('更新包大小异常');
        }
        sink.add(chunk);
        hashSink.add(chunk);
        onProgress?.call(received / release.size);
      }
      await sink.flush();
      await sink.close();
      hashSink.close();
      if (received != release.size || digest.events.single.toString() != release.sha256) {
        await output.delete().catchError((_) => output);
        throw StateError('更新包完整性校验失败，已拒绝安装');
      }
    } catch (_) {
      await sink.close().catchError((_) {});
      if (await output.exists()) await output.delete();
      rethrow;
    }
    await const MethodChannel('moe.teacharm.bot_workstation/update')
        .invokeMethod<void>('installApk', {'path': output.path});
  }

  static int compareVersions(String left, String right) {
    final a = left.split('.').map(int.parse).toList();
    final b = right.split('.').map((part) => int.tryParse(part) ?? 0).toList();
    for (var index = 0; index < 4; index++) {
      final av = index < a.length ? a[index] : 0;
      final bv = index < b.length ? b[index] : 0;
      if (av != bv) return av.compareTo(bv);
    }
    return 0;
  }
}

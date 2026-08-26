import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:url_launcher/url_launcher.dart';

class MobileRelease {
  const MobileRelease({
    required this.version,
    required this.url,
    required this.sha256,
    required this.notes,
  });

  final String version;
  final Uri url;
  final String sha256;
  final String notes;
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
    if (!RegExp(r'^\d+(?:\.\d+){1,3}$').hasMatch(version) ||
        url == null ||
        url.scheme != 'https' ||
        url.host != 'assets.teacharm.moe' ||
        !url.path.startsWith('/bot-workstation/mobile/') ||
        !url.path.toLowerCase().endsWith('.apk') ||
        !RegExp(r'^[0-9a-f]{64}$').hasMatch(sha256)) {
      return null;
    }
    final current = (await PackageInfo.fromPlatform()).version;
    if (compareVersions(version, current) <= 0) return null;
    return MobileRelease(
      version: version,
      url: url,
      sha256: sha256,
      notes: value['notes']?.toString().trim() ?? '',
    );
  }

  Future<void> openInstaller(MobileRelease release) async {
    if (!await launchUrl(release.url, mode: LaunchMode.externalApplication)) {
      throw StateError('无法打开安全下载地址');
    }
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

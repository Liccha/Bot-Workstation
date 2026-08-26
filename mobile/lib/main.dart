import 'dart:async';

import 'package:flutter/material.dart';

import 'core/app_controller.dart';
import 'core/session_store.dart';
import 'screens/connect_screen.dart';
import 'screens/home_shell.dart';
import 'theme/app_theme.dart';
import 'widgets/app_background.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final controller = AppController(SessionStore());
  runApp(BotWorkstationApp(controller: controller));
  unawaited(controller.restore());
}

class BotWorkstationApp extends StatefulWidget {
  const BotWorkstationApp({super.key, required this.controller});
  final AppController controller;

  @override
  State<BotWorkstationApp> createState() => _BotWorkstationAppState();
}

class _BotWorkstationAppState extends State<BotWorkstationApp> {
  String? promptedVersion;
  final navigatorKey = GlobalKey<NavigatorState>();
  final messengerKey = GlobalKey<ScaffoldMessengerState>();

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_maybePromptUpdate);
  }

  @override
  void dispose() {
    widget.controller.removeListener(_maybePromptUpdate);
    super.dispose();
  }

  void _maybePromptUpdate() {
    final release = widget.controller.mobileRelease;
    if (release == null || promptedVersion == release.version || !mounted) {
      return;
    }
    promptedVersion = release.version;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      final dialogContext = navigatorKey.currentContext;
      if (!mounted || dialogContext == null) {
        promptedVersion = null;
        return;
      }
      final install = await showDialog<bool>(
        context: dialogContext,
        builder: (context) => AlertDialog(
          title: Text('发现新版本 ${release.version}'),
          content: Text(
            release.notes.isEmpty
                ? '是否打开官方安装包下载页？'
                : '${release.notes}\n\n是否打开官方安装包下载页？',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('稍后'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('一键更新'),
            ),
          ],
        ),
      );
      if (install == true) {
        try {
          await widget.controller.mobileUpdates.openInstaller(release);
        } catch (error) {
          if (mounted) {
            messengerKey.currentState?.showSnackBar(
              SnackBar(content: Text(error.toString())),
            );
          }
        }
      }
    });
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Bot 工作站',
    navigatorKey: navigatorKey,
    scaffoldMessengerKey: messengerKey,
    debugShowCheckedModeBanner: false,
    theme: AppTheme.light(),
    home: AppBackground(
      child: ListenableBuilder(
        listenable: widget.controller,
        builder: (context, _) {
          if (widget.controller.booting) {
            return const Scaffold(
              backgroundColor: Colors.transparent,
              body: Center(child: CircularProgressIndicator()),
            );
          }
          return widget.controller.connected
              ? HomeShell(controller: widget.controller)
              : ConnectScreen(controller: widget.controller);
        },
      ),
    ),
  );
}

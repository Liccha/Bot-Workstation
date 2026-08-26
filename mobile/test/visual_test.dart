import 'package:bot_workstation_mobile/core/app_controller.dart';
import 'package:bot_workstation_mobile/core/session_store.dart';
import 'package:bot_workstation_mobile/screens/connect_screen.dart';
import 'package:bot_workstation_mobile/screens/dashboard_screen.dart';
import 'package:bot_workstation_mobile/theme/app_theme.dart';
import 'package:bot_workstation_mobile/widgets/app_background.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  Future<void> usePhoneViewport(WidgetTester tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
  }

  testWidgets('connection screen fits a compact phone', (tester) async {
    await usePhoneViewport(tester);
    final controller = AppController(SessionStore());
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.light(),
        home: AppBackground(child: ConnectScreen(controller: controller)),
      ),
    );
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await expectLater(
      find.byType(Scaffold),
      matchesGoldenFile('goldens/connect_phone.png'),
    );
  });

  testWidgets('dashboard fits a compact phone', (tester) async {
    await usePhoneViewport(tester);
    final controller = AppController(SessionStore())
      ..booting = false
      ..status = const {'songBot': 'running', 'napCat': 'stopped'};
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.light(),
        home: AppBackground(
          child: Scaffold(
            backgroundColor: Colors.transparent,
            body: DashboardScreen(controller: controller),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await expectLater(
      find.byType(Scaffold),
      matchesGoldenFile('goldens/dashboard_phone.png'),
    );
  });
}

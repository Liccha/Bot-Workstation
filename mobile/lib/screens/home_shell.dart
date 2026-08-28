import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../core/app_controller.dart';
import '../theme/app_theme.dart';
import 'dashboard_screen.dart';
import 'records_screen.dart';

class HomeShell extends StatefulWidget {
  const HomeShell({super.key, required this.controller});
  final AppController controller;

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell>
    with SingleTickerProviderStateMixin {
  int index = 0;
  late final AnimationController refreshAnimation;

  @override
  void initState() {
    super.initState();
    refreshAnimation = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 620),
    );
  }

  @override
  void dispose() {
    refreshAnimation.dispose();
    super.dispose();
  }

  Future<void> refreshWithFeedback() async {
    refreshAnimation.repeat();
    try {
      await widget.controller.refresh();
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('状态已刷新')));
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(error.toString())));
      }
    } finally {
      refreshAnimation.stop();
      refreshAnimation.value = 0;
    }
  }

  @override
  Widget build(BuildContext context) {
    final pages = [
      DashboardScreen(controller: widget.controller),
      RecordsScreen(api: widget.controller.api!, type: RecordType.song),
      RecordsScreen(api: widget.controller.api!, type: RecordType.stable),
      _MoreScreen(controller: widget.controller),
    ];
    const titles = ['运行总览', '歌曲信息', 'Stable 曲库', '更多'];
    return Scaffold(
      backgroundColor: Colors.transparent,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        surfaceTintColor: Colors.transparent,
        title: Text(
          titles[index],
          style: Theme.of(context).textTheme.titleLarge,
        ),
        actions: [
          if (index == 0)
            IconButton(
              tooltip: '刷新',
              onPressed: widget.controller.busy ? null : refreshWithFeedback,
              icon: RotationTransition(
                turns: refreshAnimation,
                child: const Icon(Icons.refresh_rounded),
              ),
            ),
          const SizedBox(width: 8),
        ],
      ),
      body: AnimatedSwitcher(
        duration: const Duration(milliseconds: 280),
        switchInCurve: Curves.easeOutCubic,
        child: KeyedSubtree(key: ValueKey(index), child: pages[index]),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: index,
        onDestinationSelected: (value) => setState(() => index = value),
        backgroundColor: Colors.white.withValues(alpha: .9),
        indicatorColor: const Color(0x1FDA3D7B),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.dashboard_outlined),
            selectedIcon: Icon(Icons.dashboard_rounded),
            label: '总览',
          ),
          NavigationDestination(
            icon: Icon(Icons.library_music_outlined),
            selectedIcon: Icon(Icons.library_music_rounded),
            label: '茶韵谱面',
          ),
          NavigationDestination(
            icon: Icon(Icons.table_chart_outlined),
            selectedIcon: Icon(Icons.table_chart_rounded),
            label: 'Stable谱面',
          ),
          NavigationDestination(
            icon: Icon(Icons.more_horiz_rounded),
            label: '更多',
          ),
        ],
      ),
    );
  }
}

class _MoreScreen extends StatelessWidget {
  const _MoreScreen({required this.controller});
  final AppController controller;

  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.fromLTRB(18, 8, 18, 28),
    children: [
      Card(
        child: Padding(
          padding: const EdgeInsets.all(18),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Icon(Icons.phone_android_rounded, color: AppTheme.accent),
              const SizedBox(height: 12),
              Text('当前连接', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 5),
              Row(
                children: [
                  Expanded(
                    child: SelectableText(
                      controller.api?.server ?? '',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ),
                  IconButton(
                    tooltip: '复制连接地址',
                    onPressed: () async {
                      await Clipboard.setData(
                        ClipboardData(text: controller.api?.server ?? ''),
                      );
                      if (context.mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(content: Text('连接地址已复制')),
                        );
                      }
                    },
                    icon: const Icon(Icons.copy_rounded, size: 19),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
      const SizedBox(height: 14),
      if (!controller.cloudIndependent &&
          controller.update['available'] == true)
        Card(
          child: Padding(
            padding: const EdgeInsets.all(18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '电脑端可更新至 ${controller.update['latest']}',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 6),
                Text(
                  controller.update['notes']?.toString() ?? '',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 14),
                FilledButton.icon(
                  onPressed: () =>
                      _run(context, controller.action('update.install')),
                  icon: const Icon(Icons.system_update_alt_rounded),
                  label: const Text('一键更新电脑端'),
                ),
              ],
            ),
          ),
        ),
      const SizedBox(height: 14),
      OutlinedButton.icon(
        onPressed: () async {
          final yes = await showDialog<bool>(
            context: context,
            builder: (context) => AlertDialog(
              title: const Text('断开工作站？'),
              content: const Text('手机上的配对令牌会被清除，下次需要重新输入六位配对码。'),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('取消'),
                ),
                FilledButton(
                  onPressed: () => Navigator.pop(context, true),
                  child: const Text('断开'),
                ),
              ],
            ),
          );
          if (yes == true) await controller.disconnect();
        },
        icon: const Icon(Icons.link_off_rounded),
        label: const Text('断开并清除配对'),
      ),
    ],
  );

  static Future<void> _run(BuildContext context, Future<void> task) async {
    try {
      await task;
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('操作已执行')));
      }
    } catch (error) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(error.toString())));
      }
    }
  }
}

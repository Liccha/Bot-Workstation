import 'package:flutter/material.dart';

import '../core/app_controller.dart';
import '../theme/app_theme.dart';

class DashboardScreen extends StatelessWidget {
  const DashboardScreen({super.key, required this.controller});
  final AppController controller;

  @override
  Widget build(BuildContext context) => RefreshIndicator(
    onRefresh: controller.refresh,
    child: ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(18, 8, 18, 28),
      children: [
        if (controller.cloudIndependent) ...[
          _CloudCard(controller: controller),
          const SizedBox(height: 14),
        ],
        _ServiceCard(
          name: 'SongBot',
          detail: controller.cloudIndependent
              ? '群消息、公告调度、猜歌与接口服务 · 由常驻后台代理控制'
              : '群消息、公告调度、猜歌与接口服务',
          state: controller.status['songBot']?.toString() ?? 'unknown',
          icon: Icons.smart_toy_outlined,
          onStart: () => _run(context, controller.action('songbot.start')),
          onStop: () => _run(context, controller.action('songbot.stop')),
        ),
        const SizedBox(height: 14),
        _ServiceCard(
          name: 'NapCat',
          detail: controller.cloudIndependent
              ? 'QQ 连接与 OneBot 消息通道 · 由常驻后台代理控制'
              : 'QQ 连接与 OneBot 消息通道',
          state: controller.status['napCat']?.toString() ?? 'unknown',
          icon: Icons.forum_outlined,
          onStart: () => _run(context, controller.action('napcat.start')),
          onStop: () => _run(context, controller.action('napcat.stop')),
        ),
        const SizedBox(height: 14),
        _AutomationCard(controller: controller),
      ],
    ),
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

class _AutomationCard extends StatelessWidget {
  const _AutomationCard({required this.controller});
  final AppController controller;

  @override
  Widget build(BuildContext context) {
    final enabled = controller.status['dailyAutomation'] == true;
    final online = controller.status['workstationOnline'] == true;
    return Card(
      child: SwitchListTile.adaptive(
        contentPadding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
        secondary: const Icon(Icons.music_note_rounded, color: AppTheme.violet),
        title: Text('每日歌曲与竞猜', style: Theme.of(context).textTheme.titleLarge),
        subtitle: Text(enabled ? '当前已开启' : '当前已关闭'),
        value: enabled,
        onChanged: online && !controller.busy
            ? (value) => DashboardScreen._run(
                context,
                controller.action(
                  value
                      ? 'daily.automation.enable'
                      : 'daily.automation.disable',
                ),
              )
            : null,
      ),
    );
  }
}

class _CloudCard extends StatelessWidget {
  const _CloudCard({required this.controller});
  final AppController controller;

  @override
  Widget build(BuildContext context) {
    final songs = controller.status['songs'] as Map?;
    final stable = controller.status['stable'] as Map?;
    final locked = controller.status['writeLocked'] == true;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.cloud_done_rounded, color: AppTheme.accent),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    '云端独立运行',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                ),
                Text(
                  locked ? '写入已阻断' : '可读写',
                  style: TextStyle(
                    color: locked
                        ? const Color(0xFFE29021)
                        : const Color(0xFF13A16D),
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 18),
            Row(
              children: [
                Expanded(
                  child: _Count(label: '歌曲', value: '${songs?['total'] ?? 0}'),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _Count(
                    label: 'Stable',
                    value: '${stable?['total'] ?? 0}',
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _Count extends StatelessWidget {
  const _Count({required this.label, required this.value});
  final String label;
  final String value;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
    decoration: BoxDecoration(
      color: const Color(0xFFF8F3F8),
      borderRadius: BorderRadius.circular(14),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(value, style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 3),
        Text(label, style: Theme.of(context).textTheme.bodySmall),
      ],
    ),
  );
}

class _ServiceCard extends StatelessWidget {
  const _ServiceCard({
    required this.name,
    required this.detail,
    required this.state,
    required this.icon,
    required this.onStart,
    required this.onStop,
  });
  final String name;
  final String detail;
  final String state;
  final IconData icon;
  final VoidCallback onStart;
  final VoidCallback onStop;

  @override
  Widget build(BuildContext context) {
    final running = state == 'running';
    final degraded = state == 'degraded';
    final offline = state == 'offline';
    final unknown = state == 'unknown';
    final label = offline
        ? '后台代理离线'
        : unknown
        ? '状态未知'
        : running
        ? '已启用'
        : degraded
        ? '连接异常'
        : '未启用';
    final color = running
        ? const Color(0xFF13A16D)
        : degraded
        ? const Color(0xFFE29021)
        : AppTheme.muted;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  width: 42,
                  height: 42,
                  decoration: BoxDecoration(
                    color: const Color(0xFFF7EEF7),
                    borderRadius: BorderRadius.circular(13),
                  ),
                  child: Icon(icon, color: AppTheme.violet),
                ),
                const SizedBox(width: 13),
                Expanded(
                  child: Text(
                    name,
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 6,
                  ),
                  decoration: BoxDecoration(
                    color: color.withValues(alpha: .1),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Row(
                    children: [
                      Container(
                        width: 7,
                        height: 7,
                        decoration: BoxDecoration(
                          color: color,
                          shape: BoxShape.circle,
                        ),
                      ),
                      const SizedBox(width: 6),
                      Text(
                        label,
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                          color: color,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text(detail, style: Theme.of(context).textTheme.bodySmall),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: FilledButton.tonal(
                    onPressed: offline || unknown || running ? null : onStart,
                    child: const Text('启用'),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: OutlinedButton(
                    onPressed: offline || unknown || (!running && !degraded)
                        ? null
                        : onStop,
                    child: const Text('停用'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

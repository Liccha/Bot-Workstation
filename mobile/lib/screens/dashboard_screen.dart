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
        _ServiceCard(
          name: 'SongBot',
          detail: '群消息、公告调度、猜歌与接口服务',
          state: controller.status['songBot']?.toString() ?? 'unknown',
          icon: Icons.smart_toy_outlined,
          onStart: () => _run(context, controller.action('songbot.start')),
          onStop: () => _run(context, controller.action('songbot.stop')),
        ),
        const SizedBox(height: 14),
        _ServiceCard(
          name: 'NapCat',
          detail: 'QQ 连接与 OneBot 消息通道',
          state: controller.status['napCat']?.toString() ?? 'unknown',
          icon: Icons.forum_outlined,
          onStart: () => _run(context, controller.action('napcat.start')),
          onStop: () => _run(context, controller.action('napcat.stop')),
        ),
        const SizedBox(height: 24),
        Text('电脑端模块', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 12),
        _ModuleTile(
          icon: Icons.graphic_eq_rounded,
          title: '谱面录入与图片设计',
          subtitle: '在电脑打开复杂制图工作区',
          action: () => _run(context, controller.action('open.mcz')),
        ),
        _ModuleTile(
          icon: Icons.campaign_outlined,
          title: '公告及网站管理',
          subtitle: '管理员验证仍在电脑端完成',
          action: () => _run(context, controller.action('open.admin')),
        ),
        _ModuleTile(
          icon: Icons.tune_rounded,
          title: '运营中心',
          subtitle: '每日推荐、竞猜与正式站检查',
          action: () => _run(context, controller.action('open.operations')),
        ),
      ],
    ),
  );

  static Future<void> _run(BuildContext context, Future<void> task) async {
    try {
      await task;
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('操作已提交')));
      }
    } catch (error) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(error.toString())));
      }
    }
  }
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
    final label = running
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
                    onPressed: running ? null : onStart,
                    child: const Text('启用'),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: OutlinedButton(
                    onPressed: running || degraded ? onStop : null,
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

class _ModuleTile extends StatelessWidget {
  const _ModuleTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.action,
  });
  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback action;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: Card(
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 7),
        leading: Container(
          width: 42,
          height: 42,
          decoration: BoxDecoration(
            color: const Color(0xFFF8EFF6),
            borderRadius: BorderRadius.circular(13),
          ),
          child: Icon(icon, color: AppTheme.accent),
        ),
        title: Text(title, style: Theme.of(context).textTheme.titleMedium),
        subtitle: Text(subtitle, style: Theme.of(context).textTheme.bodySmall),
        trailing: const Icon(Icons.arrow_forward_ios_rounded, size: 16),
        onTap: action,
      ),
    ),
  );
}

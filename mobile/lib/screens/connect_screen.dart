import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../core/app_controller.dart';
import '../theme/app_theme.dart';

class ConnectScreen extends StatefulWidget {
  const ConnectScreen({super.key, required this.controller});
  final AppController controller;

  @override
  State<ConnectScreen> createState() => _ConnectScreenState();
}

class _ConnectScreenState extends State<ConnectScreen> {
  final _server = TextEditingController();
  final _code = TextEditingController();

  @override
  void dispose() {
    _server.dispose();
    _code.dispose();
    super.dispose();
  }

  Future<void> _pair() async {
    FocusManager.instance.primaryFocus?.unfocus();
    try {
      await widget.controller.pair(_server.text, _code.text);
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(error.toString())));
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: Colors.transparent,
    body: SafeArea(
      child: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 460),
            child: TweenAnimationBuilder<double>(
              tween: Tween(begin: 0, end: 1),
              duration: const Duration(milliseconds: 520),
              curve: Curves.easeOutCubic,
              builder: (context, value, child) => Opacity(
                opacity: value,
                child: Transform.translate(
                  offset: Offset(0, 18 * (1 - value)),
                  child: child,
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    width: 58,
                    height: 58,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(18),
                      gradient: const LinearGradient(
                        colors: [AppTheme.accent, AppTheme.violet],
                      ),
                      boxShadow: const [
                        BoxShadow(
                          color: Color(0x35DA3D7B),
                          blurRadius: 24,
                          offset: Offset(0, 10),
                        ),
                      ],
                    ),
                    clipBehavior: Clip.antiAlias,
                    child: Padding(
                      padding: const EdgeInsets.all(7),
                      child: Image.asset(
                        'assets/app_icon.png',
                        fit: BoxFit.contain,
                        filterQuality: FilterQuality.high,
                      ),
                    ),
                  ),
                  const SizedBox(height: 24),
                  Text(
                    '连接 Bot 工作站',
                    style: Theme.of(context).textTheme.headlineMedium,
                  ),
                  const SizedBox(height: 8),
                  Text(
                    '手机与电脑须连接同一个可互访的 Wi-Fi。请在电脑端打开“手机端”，启用服务后复制访问地址，并输入当前六位配对码。',
                    style: Theme.of(context).textTheme.bodyMedium
                        ?.copyWith(color: AppTheme.muted),
                  ),
                  const SizedBox(height: 24),
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(18),
                      child: Column(
                        children: [
                          TextField(
                            controller: _server,
                            keyboardType: TextInputType.url,
                            autocorrect: false,
                            decoration: const InputDecoration(
                              labelText: '电脑访问地址',
                              hintText: 'http://192.168.1.8:8098/',
                              prefixIcon: Icon(Icons.lan_outlined),
                            ),
                          ),
                          const SizedBox(height: 13),
                          TextField(
                            controller: _code,
                            keyboardType: TextInputType.number,
                            maxLength: 6,
                            inputFormatters: [
                              FilteringTextInputFormatter.digitsOnly,
                            ],
                            decoration: const InputDecoration(
                              labelText: '六位配对码',
                              counterText: '',
                              prefixIcon: Icon(Icons.password_rounded),
                            ),
                          ),
                          const SizedBox(height: 18),
                          SizedBox(
                            width: double.infinity,
                            child: FilledButton.icon(
                              onPressed: widget.controller.busy ? null : _pair,
                              icon: widget.controller.busy
                                  ? const SizedBox.square(
                                      dimension: 18,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                      ),
                                    )
                                  : const Icon(Icons.link_rounded),
                              label: Text(
                                widget.controller.busy ? '正在连接' : '安全配对',
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),
                  const Row(
                    children: [
                      Icon(
                        Icons.lock_outline_rounded,
                        size: 17,
                        color: AppTheme.muted,
                      ),
                      SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          '配对令牌保存在手机系统安全存储中；App 不包含云端 AccessKey。',
                          style: TextStyle(
                            fontSize: 12,
                            height: 1.45,
                            color: AppTheme.muted,
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

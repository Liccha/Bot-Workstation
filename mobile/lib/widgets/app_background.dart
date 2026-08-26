import 'package:flutter/material.dart';

class AppBackground extends StatelessWidget {
  const AppBackground({super.key, required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: const BoxDecoration(
      gradient: LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [Color(0xFFFFF4FA), Color(0xFFF8F7FF), Color(0xFFFFFBFD)],
        stops: [0, .58, 1],
      ),
    ),
    child: Stack(
      children: [
        const Positioned(
          left: -90,
          top: -120,
          child: _Glow(size: 270, color: Color(0x28F26B9F)),
        ),
        const Positioned(
          right: -110,
          top: 180,
          child: _Glow(size: 300, color: Color(0x207B61E8)),
        ),
        child,
      ],
    ),
  );
}

class _Glow extends StatelessWidget {
  const _Glow({required this.size, required this.color});
  final double size;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
    width: size,
    height: size,
    decoration: BoxDecoration(shape: BoxShape.circle, color: color),
  );
}

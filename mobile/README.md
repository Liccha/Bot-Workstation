# Bot Workstation Mobile

Flutter companion app for the Bot Workstation. It connects to a workstation instance on the same trusted LAN, completes a short pairing flow, and exposes the supported operational modules without embedding cloud credentials in the application package.

## Local development

```bash
flutter pub get
flutter analyze
flutter test test/widget_test.dart
flutter run
```

The app accepts only private-network workstation addresses on the configured service port. Pairing secrets are stored with the platform secure-storage implementation. Android signing files, generated SDK paths, build outputs, and production endpoints are intentionally excluded from this repository.

## Release notes

- Configure release signing outside the repository.
- Keep the workstation API behind its pairing and administrator authorization layers.
- Update manifests must be delivered over HTTPS and validated before installation.

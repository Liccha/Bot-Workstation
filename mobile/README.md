# Bot Workstation Mobile

Flutter companion app for the Bot Workstation. The first connection uses a trusted LAN and one-time pairing code to create a revocable device account. The saved account then talks directly to the fixed domestic cloud data API, so song and Stable queries, edits and media uploads work across Wi-Fi/mobile networks without keeping the desktop UI open.

## Local development

```bash
flutter pub get
flutter analyze
flutter test test/widget_test.dart
flutter run
```

The pairing screen accepts only private-network workstation addresses on the configured service port. Device secrets are stored with the platform secure-storage implementation and never grant access to OSS credentials or announcement administration. SongBot/NapCat process controls are relayed only when the owner workstation's background agent is online; cloud library operations remain independent.

## Release notes

- Configure release signing outside the repository.
- Keep the workstation API behind its pairing and administrator authorization layers.
- Update manifests must be delivered over HTTPS and validated before installation.
- Large lists are paged and immutable snapshots are cached by revision to avoid repeatedly downloading the full library.

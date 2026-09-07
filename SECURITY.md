# Security Policy

## Supported scope

This repository is a sanitized portfolio snapshot and a temporary isolated demo-client distribution. Desktop and Android builds are pinned to a dedicated demo function and object prefix; they cannot select a production host. The portfolio website stays read-only, production update feeds are absent, and demo mutations require short-lived tokens plus a server-side enable/expiry gate. Security reports should focus on authentication bypasses, escaping the demo prefix, unauthorized production access, path traversal, token leakage, update integrity, and destructive data handling.

## Reporting

Do not publish credentials, production identifiers, user data, database dumps, request cookies, or signed URLs in a public issue. Contact the repository owner privately and include:

- affected module and version;
- minimal reproduction steps using demo data;
- expected and observed behavior;
- impact assessment;
- suggested mitigation, if available.

## Secret handling

- Runtime secrets belong in deployment environment variables or local ignored configuration.
- OSS credentials must use a dedicated least-privilege RAM identity.
- Android/Windows signing keys are release infrastructure and must never be committed.
- A leaked credential must be revoked and rotated; deleting it from the latest commit is not sufficient.

## Out of scope

The licensed fonts, songs, production databases, group identifiers, administrator device list and live cloud configuration are intentionally absent from this repository.

## Demo shutdown

The repository owner can close demo writes without releasing new clients:

```bash
cd web
npm run demo:access -- --env <private-env-file> --endpoint https://songbotdemo-api-hxhuxsgwar.cn-beijing.fcapp.run --enabled false
```

The control request requires the private desktop-management token. Never commit that environment file or token.

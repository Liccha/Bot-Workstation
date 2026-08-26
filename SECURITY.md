# Security Policy

## Supported scope

This repository is a sanitized public source snapshot. Security reports should focus on authentication bypasses, unauthorized cloud writes, path traversal, token leakage, update integrity, and destructive data handling.

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

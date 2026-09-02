# Security policy

## Supported versions

Security fixes are provided for the latest published VoiceBridge release. Older
development APKs and model snapshots are not supported after a replacement is
published.

## Report a vulnerability privately

Do not post vulnerabilities, credentials, private transcripts, device logs, or
other sensitive data in a public issue.

Use GitHub's private vulnerability reporting for this repository:

<https://github.com/oiv-an/VoiceBridge/security/advisories/new>

Include only what is needed to reproduce and assess the issue:

- affected VoiceBridge version and Android version;
- affected feature and the impact you observed;
- concise reproduction steps;
- whether special permissions or user interaction are required; and
- a minimal proof of concept with secrets and personal data removed.

Please allow time for the report to be reproduced and a fix to be prepared
before public disclosure. If private vulnerability reporting is temporarily
unavailable, open a public issue that asks for a private contact channel but
does not reveal the vulnerability.

## Release authenticity

Official binaries are attached to this repository's GitHub Releases. Each
release should publish a SHA-256 checksum. Verify it before sideloading an APK
obtained through another channel. Release APKs must be signed with the project
release key; debug-signed APKs are not official releases.

## Security boundaries

- Model files downloaded directly by the app are pinned to immutable revisions
  and checked against expected sizes and SHA-256 hashes before activation.
- The floating translator uses a powerful Android accessibility permission.
  Enable it only when needed and revoke it if the installed APK is no longer
  trusted.
- Online speech recognition, ML Kit, model hosting, Android text-to-speech, and
  the exchange-rate endpoint are third-party trust boundaries. Review
  [PRIVACY.md](PRIVACY.md) before enabling their related features.

# Contributing to VoiceBridge

Thanks for helping improve VoiceBridge. Keep changes focused, testable, and
transparent about privacy and third-party dependencies.

## Development setup

You need JDK 17, Android SDK Platform 36, and an `arm64-v8a` Android device or a
suitable build environment. Clone the repository, let the Gradle wrapper resolve
dependencies, and run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The current JVM suite contains 172 tests. Add or update tests for behavior
changes. Do not make a pull request depend on model weights being present: the
large ASR models are downloaded at runtime and are deliberately absent from Git.

## Pull requests

1. Describe the user-visible behavior and why the change is needed.
2. Keep unrelated formatting or dependency updates out of the same change.
3. Add tests for reducers, parsers, installers, or state transitions when
   practical.
4. Run unit tests, lint, and a debug build before requesting review.
5. For microphone, overlay, accessibility, networking, storage, or SDK changes,
   update `PRIVACY.md` and describe the changed data flow.
6. For any new library, model, font, image, or service, document its exact source,
   version/revision, license, redistribution terms, and required attribution in
   `THIRD_PARTY_NOTICES.md`.

Never commit API keys, service-account files, signing keys, keystore passwords,
device identifiers, private logs, downloaded model weights, APKs, or user data.
The repository's `.gitignore` is a backstop, not a substitute for reviewing the
staged diff.

## Model and native-runtime updates

- Use an immutable upstream revision rather than a mutable branch name.
- Record every runtime file's expected size and SHA-256.
- Activate downloaded models only after the entire manifest verifies.
- Confirm the model license permits the intended release and use case.
- When updating the vendored sherpa-onnx AAR, record its release URL and SHA-256,
  identify the embedded ONNX Runtime version, and refresh all notices.
- Never silently replace the noncommercial Vietnamese model with another model
  under unknown terms.

## Source license

Unless a contribution explicitly states otherwise and is accepted that way,
contributions submitted to this repository are licensed under Apache License
2.0, consistent with [LICENSE](LICENSE). Third-party material remains under its
own license.

## Maintainer release checklist

1. Bump `versionCode` and `versionName` together.
2. Review all staged files for credentials, personal paths, device identifiers,
   generated output, and oversized files.
3. Run `./gradlew testDebugUnitTest lintDebug assembleRelease` with the four
   `VOICEBRIDGE_*` signing environment variables set outside the repository.
4. Verify the APK with Android SDK `apksigner verify --verbose --print-certs`.
5. Name the asset `VoiceBridge-<version>.apk` and generate its SHA-256.
6. Publish release notes that identify Android/ABI requirements, data-flow or
   permission changes, and applicable third-party/model-license restrictions.
7. Install or upgrade on a physical device without clearing data and test both
   talk directions, matching-language TTS, offline translation, and the optional
   overlay before marking the release stable.

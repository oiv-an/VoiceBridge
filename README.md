# VoiceBridge

[![Android CI](https://github.com/oiv-an/VoiceBridge/actions/workflows/android-ci.yml/badge.svg)](https://github.com/oiv-an/VoiceBridge/actions/workflows/android-ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/oiv-an/VoiceBridge)](https://github.com/oiv-an/VoiceBridge/releases/latest)

VoiceBridge is a privacy-conscious Android voice and screen translator for
face-to-face Russian, English, and Vietnamese conversations. Hold the button
for the language being spoken, talk, release, correct the transcript if needed,
and show or play the translation.

The current release is **0.10.5**. Download the signed APK from
[GitHub Releases](https://github.com/oiv-an/VoiceBridge/releases/latest), and see
the [changelog](CHANGELOG.md) for version history.

## Highlights

- Two large press-and-hold talk buttons, one for each direction of the selected
  language pair. Recognition continues across ordinary pauses while the button
  remains held.
- Russian, English, and Vietnamese language selection with editable transcripts,
  automatic translation, compact Russian back-translation, and language-matched
  Android text-to-speech.
- Online speech recognition through Android's selected recognition service, or
  fully local speech recognition using separately downloaded sherpa-onnx models.
- On-device Google ML Kit translation after the required language models have
  been downloaded. VoiceBridge does not proxy translations through its own server.
- A large numeric keypad, VND amount detection, digit grouping, and estimates in
  selectable RUB, USD, EUR, and UAH. Rates come from the Bank of Russia, include
  the configured 5% exchange-cost buffer, and are cached until 06:00 the next day.
- A short offline Russian pronunciation hint for Vietnamese phrases of up to five
  words.
- An optional floating screen translator. Tap its button over another app to read
  visible English or Vietnamese accessibility text and overlay Russian labels.
  It does not take screenshots or run OCR.
- Optional offline bundle installation from Settings. ASR downloads are pinned by
  immutable revision, checked by size and SHA-256, and activated atomically.

Conversation-context translation is planned in
[CONVERSATION_MODE_PLAN.md](CONVERSATION_MODE_PLAN.md); it is not implemented in
0.10.5.

## Install

Requirements:

- Android 6.0 or newer (`minSdk 23`)
- 64-bit ARM device (`arm64-v8a`); the published APK does not include x86 or
  32-bit ARM native libraries
- roughly 750 MB plus temporary download space if the complete offline bundle is
  installed

1. Download `VoiceBridge-0.10.5.apk` from the latest GitHub release.
2. Allow installation from the browser or file manager when Android asks, then
   install the APK.
3. Grant microphone access when you first use push-to-talk.
4. For offline speech, open Settings and choose **Local · offline** or tap
   **Download all offline models**. Keep the app open until verification finishes.
5. The floating screen translator is optional. Its setup explains why Android's
   overlay and accessibility permissions are needed before opening the respective
   system settings.

Model weights are deliberately not included in the repository or APK. A new
installation starts with Android/system speech recognition available; local mode
becomes selectable only after every required model is present.

## How it works

| Area | Implementation |
| --- | --- |
| UI | Kotlin and Jetpack Compose, driven by an immutable UI state/reducer |
| Online speech | Android `SpeechRecognizer`, with an explicit locale and automatic segment rollover while the button remains held |
| Local speech | sherpa-onnx 1.13.6: NVIDIA Nemotron 3.5 streaming ASR for Russian/general paths and a dedicated Vietnamese Zipformer RNN-T |
| Translation | Google ML Kit Translation 17.0.3; inference runs on the device after model download |
| Screen translation | Accessibility text snapshot, ML Kit Language ID 17.0.6, local ML Kit translation, and a foreground overlay service |
| Speech playback | Android `TextToSpeech`, selecting a voice that matches the text language |
| Currency estimates | Daily HTTPS XML snapshot from the Central Bank of Russia with a local morning cache |
| Persistence | Android private preferences for language, currency, mode, model markers, and the rate cache; ASR weights live under the app's no-backup directory |

### Local speech models

| Purpose | Downloaded artifact | Pinned revision | Upstream license |
| --- | --- | --- | --- |
| Russian/general streaming ASR | [`csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11`](https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11) | `ab43d895f5985b1bbab8b6eac8607fcdc05343f3` | The originating [`nvidia/nemotron-3.5-asr-streaming-0.6b`](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) model is offered under [OpenMDW 1.1](https://openmdw.ai/license/1-1/) |
| Vietnamese ASR | [`csukuangfj2/sherpa-onnx-zipformer-vi-30M-int8-2026-02-09`](https://huggingface.co/csukuangfj2/sherpa-onnx-zipformer-vi-30M-int8-2026-02-09) | `83e140db6d23fbb8480fd5fb868f74ab80e7092c` | The originating [`hynt/Zipformer-30M-RNNT-6000h`](https://huggingface.co/hynt/Zipformer-30M-RNNT-6000h) model is [CC BY-NC-ND 4.0](https://creativecommons.org/licenses/by-nc-nd/4.0/) |

The Vietnamese model's license does not permit commercial use or distribution
of derivatives. The current Vietnamese local-model path and public APK are
therefore provided for **noncommercial use only**. The Apache-2.0 license on the
VoiceBridge source code does not replace any model or dependency license.

The app vendors the official sherpa-onnx 1.13.6 Android AAR at
`app/libs/sherpa-onnx-1.13.6.aar` (SHA-256
`0012d9a28f15bd6fb966b62b70a75da3990512fdccce28b83098248ce4be1698`).
That runtime is Apache-2.0 and embeds ONNX Runtime 1.27.1 under the MIT License.
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) before redistributing a
build.

## Google translation attribution

Translations displayed by VoiceBridge are powered by Google Translate through
the ML Kit on-device Translation API. VoiceBridge is not affiliated with or
endorsed by Google. Usage is subject to the
[ML Kit on-device translation guidelines](https://developers.google.com/ml-kit/language/translation/translation-terms)
and Google's
[translation attribution requirements](https://docs.cloud.google.com/translate/attribution).

> THIS SERVICE MAY CONTAIN TRANSLATIONS POWERED BY GOOGLE. GOOGLE DISCLAIMS ALL
> WARRANTIES RELATED TO THE TRANSLATIONS, EXPRESS OR IMPLIED, INCLUDING ANY
> WARRANTIES OF ACCURACY, RELIABILITY, AND ANY IMPLIED WARRANTIES OF
> MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.

Machine translation can be wrong. Confirm important medical, legal, safety, and
financial information with a qualified person.

## Privacy

VoiceBridge has no project-operated backend, account system, advertising SDK, or
custom analytics. Its own code does not intentionally persist or log transcript,
translation, microphone audio, or captured screen text. Local mode processes
microphone audio on the phone.

Some optional/system components still communicate externally:

- Android/system online speech recognition may send audio and related data to the
  recognition provider selected on the device.
- ML Kit translates text on-device after model download, while its SDK may contact
  Google for model delivery, updates, diagnostics, and usage metrics. Google
  documents collection of device/app information, operational metrics, events,
  errors, and configured or identified languages.
- Model downloads connect to Hugging Face and Google infrastructure.
- Android text-to-speech behavior depends on the installed TTS engine and voice;
  a network voice may send text to that provider.
- Currency conversion contacts the Bank of Russia over HTTPS without sending the
  recognized or translated text.

The accessibility service can access visible text while enabled. VoiceBridge
captures and translates that text only after an explicit tap on the floating
button, keeps the working snapshot in memory, and clears it when the result is
cancelled or the screen changes. Read [PRIVACY.md](PRIVACY.md) for the complete
data-flow and permission disclosure.

## Build from source

Prerequisites:

- JDK 17
- Android SDK Platform 36 and a compatible Build Tools installation
- an internet connection for the first Gradle/dependency resolution

The Gradle wrapper is pinned to 9.5.0 with its official distribution checksum.
Run the same checks as CI:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The current JVM suite contains 178 unit tests. The debug APK is written to:

```text
app/build/outputs/apk/debug/VoiceBridge-0.10.5.apk
```

### Signed release build

Release credentials are read only from environment variables and must never be
committed:

```bash
export VOICEBRIDGE_KEYSTORE_PATH=/absolute/path/to/voicebridge-release.jks
export VOICEBRIDGE_KEYSTORE_PASSWORD='...'
export VOICEBRIDGE_KEY_ALIAS='voicebridge'
export VOICEBRIDGE_KEY_PASSWORD='...'
./gradlew testDebugUnitTest lintDebug assembleRelease
```

Without all four variables, release packaging fails rather than silently
publishing a debug-signed artifact. Verify the result with Android SDK
`apksigner`, publish its SHA-256 checksum, and keep the signing key outside this
repository. See [CONTRIBUTING.md](CONTRIBUTING.md) for the maintainer checklist.

## License and security

VoiceBridge's original source code is licensed under the
[Apache License 2.0](LICENSE). Third-party libraries, services, and separately
downloaded models retain their own terms; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Please report vulnerabilities privately as described in
[SECURITY.md](SECURITY.md), not in a public issue.

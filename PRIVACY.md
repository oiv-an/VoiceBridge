# VoiceBridge privacy notice

Last updated: 2026-09-02

This notice describes the data flows in the open-source VoiceBridge Android app.
It distinguishes VoiceBridge's own code from Android services and third-party
SDKs that the user chooses to use.

## Summary

VoiceBridge has no project-operated server, user account, advertising SDK, or
custom analytics system. VoiceBridge's own code does not intentionally persist
or log the contents of microphone audio, recognized speech, translations, or
captured screen text. It stores only app settings, model-installation markers,
downloaded models, and an exchange-rate cache in Android's private app storage.
Android backup is disabled for the app.

The app can still connect to third parties for features described below. Those
providers process data under their own terms and privacy policies.

## Voice recognition

### Local mode

In **Local · offline** mode, VoiceBridge records microphone audio with Android's
`AudioRecord` API and runs speech recognition on the device through sherpa-onnx.
The raw audio is processed in memory and is not intentionally written to disk or
sent by VoiceBridge to a remote service.

The local ASR weights are downloaded on the user's request from pinned Hugging
Face URLs. That download exposes ordinary network metadata, such as the device's
IP address and request headers, to the hosting infrastructure. The model files
are stored in VoiceBridge's private no-backup directory.

### Online mode

In **Online · Google** mode, VoiceBridge passes microphone input and the selected
language to Android's `SpeechRecognizer`. The recognition service is selected
from services installed on the device. It may send audio, language, device, and
diagnostic information to Google or another configured provider. Its behavior
is controlled by that provider and the device's Android settings, not by a
VoiceBridge server.

## Translation and language identification

VoiceBridge uses Google ML Kit Translation 17.0.3 and Language Identification
17.0.6. Translation inference runs on the device after the required language
models have been downloaded. VoiceBridge does not send translation text to a
project-operated server.

The ML Kit SDK may contact Google for model delivery, compatibility or feature
updates, diagnostics, and usage analytics. Google's Android disclosure states
that ML Kit SDKs may collect device and app information, per-installation or
device identifiers depending on the feature, performance and feature metrics,
event types, and error codes. Translation may report configured source and
destination languages; Language ID may report identified languages. Consult:

- [ML Kit terms and privacy](https://developers.google.com/ml-kit/terms)
- [ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)
- [Google Privacy Policy](https://policies.google.com/privacy)

## Floating screen translation and accessibility

The optional floating translator needs Android's accessibility and display-over-
other-apps permissions. Accessibility permission is powerful: while enabled,
Android allows VoiceBridge to inspect text and bounds exposed by visible app
windows.

VoiceBridge uses this access only for the floating translator. While that
feature is active, it tracks screen changes so stale labels can be removed. It
captures visible accessibility text for translation only after the user taps the
floating button. It does not take screenshots, run OCR, read password fields, or
intentionally persist the captured text. Source text, positions, fingerprints,
and translated labels are held temporarily in memory and cleared on cancellation,
screen changes, or when the floating feature stops.

Some apps do not expose their content as accessibility text. Image-only,
canvas-rendered, protected, and some PDF content therefore cannot be translated
by this feature.

The user can stop the floating translator from VoiceBridge or its notification,
and can revoke either permission in Android settings at any time.

## Text-to-speech

VoiceBridge gives the selected text and language to an Android `TextToSpeech`
engine installed on the device. The app prefers a language-matched voice. Some
engines and voices work locally; network voices may send the text and related
metadata to their provider. Check the selected TTS engine's settings and privacy
policy if offline playback is required.

## Currency conversion

When a possible VND amount is present, VoiceBridge may request the public daily
exchange-rate XML document from the Central Bank of Russia over HTTPS:

`https://www.cbr.ru/scripts/XML_daily.asp`

The request does not include microphone audio, recognized text, translation
text, or the detected amount. As with any HTTPS request, the server and network
operators can observe ordinary connection metadata such as an IP address and
time. The parsed quote and publication date are cached locally until 06:00 the
following day; the last valid quote can be reused while offline.

## Data stored on the device

VoiceBridge stores the following in Android private app storage:

- selected source and target languages;
- selected display currencies;
- online/local recognition preference;
- ML Kit model-installation markers;
- downloaded ASR model files and their ready markers; and
- the most recently validated exchange-rate snapshot and cache timing.

Current transcript, translation, screen snapshot, and microphone samples are
working-memory state rather than durable history. Clearing VoiceBridge storage
or uninstalling the app removes its preferences, cache, and separately downloaded
ASR models. ML Kit models are managed by Google Play services and may be shared
or retained according to that component's model-management behavior.

## Permissions

| Permission or capability | Why it is requested |
| --- | --- |
| Microphone | Capture speech after the user presses a talk button |
| Internet | Download optional models, use a network speech provider, fetch rates, and support SDK model/diagnostic traffic |
| Display over other apps | Show the optional floating translator and translated labels |
| Accessibility service | Read visible accessibility text and bounds for the optional screen translator |
| Foreground service | Keep the user-visible floating translator available while other apps are open |
| Notifications | Show the foreground-service status and Stop action on Android versions that require notification permission |

## Google translation notice

Translations are powered by Google Translate through ML Kit's on-device
Translation API. VoiceBridge is not affiliated with or endorsed by Google.

> THIS SERVICE MAY CONTAIN TRANSLATIONS POWERED BY GOOGLE. GOOGLE DISCLAIMS ALL
> WARRANTIES RELATED TO THE TRANSLATIONS, EXPRESS OR IMPLIED, INCLUDING ANY
> WARRANTIES OF ACCURACY, RELIABILITY, AND ANY IMPLIED WARRANTIES OF
> MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.

See Google's
[ML Kit translation usage guidelines](https://developers.google.com/ml-kit/language/translation/translation-terms)
and [translation attribution requirements](https://docs.cloud.google.com/translate/attribution).

## Changes and questions

Changes to data flow should update this file in the same pull request. For
privacy questions, open a GitHub issue without including personal or sensitive
data. Report security vulnerabilities privately according to
[SECURITY.md](SECURITY.md).

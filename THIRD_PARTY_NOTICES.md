# Third-party notices

Last updated for VoiceBridge 0.10.5.

VoiceBridge's original source code is Apache-2.0, but that license does not
relicense third-party libraries, services, model exports, or model weights.
Their respective terms remain in force. This file identifies the material that
is bundled, resolved during a build, or downloaded at the user's request.

## Bundled native runtime

### sherpa-onnx 1.13.6

- Project: <https://github.com/k2-fsa/sherpa-onnx>
- Release: <https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.6>
- Bundled file: `app/libs/sherpa-onnx-1.13.6.aar`
- SHA-256: `0012d9a28f15bd6fb966b62b70a75da3990512fdccce28b83098248ce4be1698`
- License: Apache License 2.0; a copy is provided in [LICENSE](LICENSE)

sherpa-onnx is a trademark/name of its respective project. Its inclusion does
not imply endorsement of VoiceBridge.

### ONNX Runtime 1.27.1

The sherpa-onnx AAR embeds ONNX Runtime native code.

- Project: <https://github.com/microsoft/onnxruntime>
- Version identified in the bundled runtime: 1.27.1
- License: [MIT](https://github.com/microsoft/onnxruntime/blob/v1.27.1/LICENSE)

```text
MIT License

Copyright (c) Microsoft Corporation. All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

ONNX Runtime publishes additional notices for software incorporated into its
distribution. Redistributors should preserve the version-matched
[`ThirdPartyNotices.txt`](https://github.com/microsoft/onnxruntime/blob/v1.27.1/ThirdPartyNotices.txt)
when repackaging or replacing the native runtime.

## Android and Kotlin libraries

The Gradle build resolves the following principal runtime libraries and their
transitive dependencies:

- AndroidX Core 1.18.0, Activity Compose 1.12.4, Lifecycle 2.10.0, Compose via
  BOM 2026.04.01, and Material 3 — Android Open Source Project / AndroidX,
  generally licensed under Apache License 2.0; see
  <https://source.android.com/docs/setup/about/licenses> and each published
  module's metadata.
- Kotlin standard libraries, Compose compiler plugin 2.3.21, and
  kotlinx.coroutines 1.10.2 — JetBrains and contributors, generally licensed
  under Apache License 2.0; see <https://github.com/JetBrains/kotlin> and
  <https://github.com/Kotlin/kotlinx.coroutines>.

JUnit 4.13.2 is used by tests and is not packaged in the application runtime.
Build tools, test tools, and their licenses are not relicensed by VoiceBridge.

## Google ML Kit and Google Translate

VoiceBridge includes these Google ML Kit Android SDK artifacts and their
transitive Google components:

- `com.google.mlkit:translate:17.0.3`
- `com.google.mlkit:language-id:17.0.6`

The SDK and downloadable translation models remain subject to Google's terms,
not VoiceBridge's Apache-2.0 license:

- [ML Kit terms](https://developers.google.com/ml-kit/terms)
- [ML Kit on-device translation usage guidelines](https://developers.google.com/ml-kit/language/translation/translation-terms)
- [Google translation attribution requirements](https://docs.cloud.google.com/translate/attribution)
- [Google APIs Terms of Service](https://developers.google.com/terms)

Translations displayed by VoiceBridge are powered by Google Translate through
the ML Kit on-device Translation API. VoiceBridge is not affiliated with or
endorsed by Google.

> THIS SERVICE MAY CONTAIN TRANSLATIONS POWERED BY GOOGLE. GOOGLE DISCLAIMS ALL
> WARRANTIES RELATED TO THE TRANSLATIONS, EXPRESS OR IMPLIED, INCLUDING ANY
> WARRANTIES OF ACCURACY, RELIABILITY, AND ANY IMPLIED WARRANTIES OF
> MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.

The app must retain the required Google attribution adjacent to displayed
translation results. Branding requirements may change; check the linked current
requirements before publishing a modified build.

## Separately downloaded speech models

No speech-model weight is committed to this repository or packaged in the APK.
VoiceBridge downloads the files below only after an explicit user action. The
files are stored in Android private app storage and remain under their upstream
terms. Do not treat them as Apache-2.0 material.

### NVIDIA Nemotron 3.5 ASR Streaming 0.6B

- Originating model: `nvidia/nemotron-3.5-asr-streaming-0.6b`
- Origin and model card:
  <https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b>
- Originating developer: NVIDIA
- Upstream license: [OpenMDW License 1.1](https://openmdw.ai/license/1-1/)
- Downloaded sherpa-onnx export:
  <https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11>
- Immutable export revision:
  `ab43d895f5985b1bbab8b6eac8607fcdc05343f3`

The VoiceBridge downloader retrieves the export's encoder, decoder, joiner,
and token files and verifies the manifest before use. Anyone independently
redistributing those model materials must include the OpenMDW license and the
origin notices it requires.

### Vietnamese Zipformer-30M-RNNT-6000h

- Originating model and attribution:
  [`hynt/Zipformer-30M-RNNT-6000h`](https://huggingface.co/hynt/Zipformer-30M-RNNT-6000h)
- Originating creator shown by the model repository: `hynt`
- Upstream license:
  [Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International](https://creativecommons.org/licenses/by-nc-nd/4.0/)
- Downloaded sherpa-onnx export:
  <https://huggingface.co/csukuangfj2/sherpa-onnx-zipformer-vi-30M-int8-2026-02-09>
- Immutable export revision:
  `83e140db6d23fbb8480fd5fb868f74ab80e7092c`

The CC BY-NC-ND 4.0 terms prohibit commercial use and distribution of adapted
material. VoiceBridge does not bundle these files or claim ownership of them;
it downloads the published export unchanged. The current Vietnamese local-model
path and public APK are provided only for noncommercial use. Commercial
distribution requires replacing this model with one whose terms permit the
intended use or obtaining separate permission from the rights holder.

## Exchange-rate data

VoiceBridge reads the Central Bank of Russia's public daily exchange-rate XML:

<https://www.cbr.ru/scripts/XML_daily.asp>

No CBR logo or endorsement is claimed. The app's converted amounts are estimates
that add a 5% exchange-cost buffer and are not official cash-exchange quotations.

## Trademarks

Android, Google, Google Translate, Kotlin, NVIDIA, ONNX, Microsoft, Hugging Face,
and other names are trademarks or names of their respective owners. They are
used only to identify interoperability, origin, or third-party components. No
affiliation or endorsement is implied.

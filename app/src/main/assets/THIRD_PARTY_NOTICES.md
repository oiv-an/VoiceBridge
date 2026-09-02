# Third-party notices

VoiceBridge source code is offered under the Apache License 2.0. The complete
license is included in this application package as `assets/LICENSE.txt`.
Third-party software, services, and separately downloaded model files remain
subject to their own terms; they are not relicensed under the VoiceBridge
license.

## sherpa-onnx 1.13.6

VoiceBridge includes the sherpa-onnx Android runtime distributed in
`sherpa-onnx-1.13.6.aar`.

- Project and source: https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.6
- License: Apache License 2.0
- Upstream license: https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.6/LICENSE
- Local notice: `assets/licenses/sherpa-onnx-1.13.6-NOTICE.txt`
- Full license text: `assets/LICENSE.txt`

## ONNX Runtime 1.27.1

The sherpa-onnx Android runtime contains ONNX Runtime 1.27.1.

- Project and source: https://github.com/microsoft/onnxruntime/tree/v1.27.1
- License: MIT
- Upstream license: https://github.com/microsoft/onnxruntime/blob/v1.27.1/LICENSE
- Full license text: `assets/licenses/ONNX-Runtime-1.27.1-MIT.txt`
- Upstream third-party notices: https://github.com/microsoft/onnxruntime/blob/v1.27.1/ThirdPartyNotices.txt

## Google ML Kit

VoiceBridge uses Google ML Kit on-device translation and language-identification
components. Those Google components and downloaded Google translation models
are governed by Google's terms, not the Apache License 2.0 for VoiceBridge.

- ML Kit terms: https://developers.google.com/ml-kit/terms
- Translation-specific terms: https://developers.google.com/ml-kit/language/translation/translation-terms
- Translation attribution requirements: https://docs.cloud.google.com/translate/attribution
- Android data-disclosure guidance: https://developers.google.com/ml-kit/android-data-disclosure

Translations produced by Google ML Kit are machine-generated. Google provides
its translation results without warranties; the official disclaimer and
attribution requirements are available at the link above.

## Optional speech-recognition models downloaded separately

The following model weights are **not bundled in the VoiceBridge APK**. They are
downloaded only after the user chooses to install local speech recognition.
Their licenses are separate from the VoiceBridge source-code license and apply
to the downloaded files.

### NVIDIA Nemotron 3.5 ASR Streaming 0.6B

- Original model: https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b
- Original-model license: Open Model Development and Weight License 1.1
  (OpenMDW 1.1), https://openmdw.ai/license/1-1/
- License copy included with the app:
  `assets/licenses/OpenMDW-1.1.txt`
- ONNX conversion downloaded by VoiceBridge:
  https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11
- Pinned conversion revision:
  `ab43d895f5985b1bbab8b6eac8607fcdc05343f3`

NVIDIA and Nemotron are names of their respective owners. Their mention here
identifies the source of the optional model and does not imply endorsement.

### Vietnamese Zipformer 30M RNN-T

- Original model: https://huggingface.co/hynt/Zipformer-30M-RNNT-6000h
- Original-model license: Creative Commons Attribution-NonCommercial-
  NoDerivatives 4.0 International (CC BY-NC-ND 4.0),
  https://creativecommons.org/licenses/by-nc-nd/4.0/legalcode.en
- ONNX conversion downloaded by VoiceBridge:
  https://huggingface.co/csukuangfj2/sherpa-onnx-zipformer-vi-30M-int8-2026-02-09
- Pinned conversion revision:
  `83e140db6d23fbb8480fd5fb868f74ab80e7092c`

The Vietnamese model's NonCommercial and NoDerivatives restrictions remain in
force for the downloaded model. Confirm that the intended use complies with
its license before installing or using it.

# Conversation mode plan

Status: planned only. No conversation-mode code is enabled in the app yet.

## Goal

Add an explicit conversation switch that makes translation use the confirmed
text of earlier turns. The current Google ML Kit translator remains the fast
fallback, but contextual translation is performed by an online language model.

## User experience

- Put a compact `Conversation` pill beside the settings gear.
- Turning it on starts a new private conversation and clears any old context.
- Keep both push-to-talk buttons and their language directions unchanged.
- Add each completed turn only after recognition and translation finish.
- If the recognized text is corrected, replace that turn instead of adding a
  duplicate.
- Show a small turn counter and provide `Clear conversation` in Settings.
- Turning the mode off immediately clears the in-memory conversation history.
- If the online contextual translator is unavailable, visibly fall back to the
  current ML Kit translation without claiming that context was used.

## Translation architecture

1. Introduce a `ContextualTranslationEngine` alongside the existing
   `TranslationEngine`.
2. Keep the last 8–12 confirmed turns in both languages. Older turns can be
   summarized when the history grows.
3. Send only text, never microphone audio, to the online service:
   selected source/target languages, previous turns, and the current phrase.
4. Instruct the language model to preserve names, numbers, prices, units, and
   tone, and to return only the current translated phrase.
5. Call the model through a small server proxy. Do not embed a provider secret
   in the APK.
6. Keep online contextual translation optional. Normal recognition, local
   NVIDIA recognition, editing, numbers, and TTS continue to work when it is off.

## Privacy and failure behavior

- The switch is opt-in and clearly indicates that text is sent online.
- Conversation history is memory-only by default and is cleared on app restart,
  explicit clear, language-pair change, or when the switch is turned off.
- Do not log conversation text or provider responses.
- Apply request timeouts and cancellation so a failed contextual request never
  blocks the next push-to-talk turn.

## Decisions for the implementation stage

- Online language-model provider and model.
- Location and authentication of the server proxy.
- Maximum turn count/context budget and request timeout.
- Whether users may opt into encrypted history persistence between app launches.

## Acceptance tests

- Pronouns and ambiguous words are translated differently when prior context
  makes the intended meaning clear.
- Reverse-direction turns use the same shared conversation history.
- Correcting a transcript updates the stored turn.
- Clearing or disabling conversation mode removes all context.
- Provider errors fall back to ordinary translation and are visibly identified.
- No API key, conversation text, or audio appears in application logs.

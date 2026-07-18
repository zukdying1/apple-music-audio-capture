# Frida Difference Migration Design

## Scope

Migrate the remaining behavior differences from `original/agent-arm64.js` into the current API101 Xposed module as fully as possible while keeping risky runtime probing fail-safe.

## Approach

- Keep existing Kotlin/native boundaries: socket protocol and Apple Music hooks stay in `AppleDecryptorModule.kt`, shared protocol/log helpers stay in `AppleMusicHookCore.kt`, JNI state stays in `AppleMusicNativeBridge.kt`, and symbol/session/sample calls stay in `appledecryptor.cpp`.
- Add tests before production changes for protocol helpers, sample summaries, bridge logging/state behavior, and session reuse/reset cases that can run on JVM.
- Make Frida parity additive and fail-safe: missing proxy, method, native symbol, or aborting native calls log warnings and return empty responses instead of crashing Apple Music.

## Behaviors To Migrate

- Support multiple decrypt sessions on one `10020` socket connection, matching the Frida loop that accepts repeated session headers before sample frames.
- Log decrypt samples with compact before/after previews and per-session sample counts.
- Report native decrypt return value and protect `decryptSample` with the same abort guard pattern already used by session preparation.
- Add playback lease fallback diagnostics: use cached proxy/method first, then try a limited runtime proxy lookup when unavailable.
- Improve startup/status logs so real-device smoke tests can distinguish missing proxy, missing method, missing target library, missing symbols, failed preshare, failed target context, and failed sample decrypt.

## Testing

- JVM tests cover length-prefixed parsing, little-endian sample frames, hex preview, sample summary, native bridge decrypt logging, session reuse, and fallback helper behavior.
- Native changes are verified by `./gradlew test` and `./gradlew assembleDebug`; real Apple Music behavior is verified by install/reload/log smoke tests after build.

## Risk Controls

- Runtime proxy fallback is best-effort only and does not replace the existing requestAsset hook cache.
- Native abort guards are kept around individual Apple native calls and restored immediately after each call.
- Socket errors still close only the client connection, not the server thread.

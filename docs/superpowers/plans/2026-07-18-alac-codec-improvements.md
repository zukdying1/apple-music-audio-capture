# AppleDecryptor Code Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix identified code quality, safety, and compatibility issues in the AppleDecryptor Xposed module

**Architecture:** Target fixes across the Kotlin Xposed module layer, C++ native layer, and CMake build configuration. Each fix is independently testable.

**Tech Stack:** Kotlin, C++17, CMake, libxposed API 101, JUnit/Kotlin Test

## Global Constraints

- All C++ code must compile for arm64-v8a with NDK r29
- All Kotlin changes must pass existing unit tests
- Follow existing code style in each file
- No new third-party dependencies

---

### Task 1: Unify FairPlay Certificate Between Kotlin and C++

**Files:**
- Modify: `app/src/main/kotlin/com/neurax08/xposed/appledecryptor/AppleMusicHookCore.kt`
- Modify: `app/src/main/cpp/appledecryptor.cpp`
- Test: `app/src/test/kotlin/com/neurax08/xposed/appledecryptor/AppleMusicHookCoreTest.kt`

**Interfaces:**
- Consumes: Existing `FAIRPLAY_CERTIFICATE` constant in `AppleMusicHookCore`
- Produces: Consistent certificate between Kotlin and C++ layers, JNI bridge parameter for certificate passing

**Problem:** C++ `initFairPlayCertificate()` patches the cert at offset 3218 with "Pa", making effective cert 3464 bytes. Kotlin `FAIRPLAY_CERTIFICATE` is already 3464 bytes (unpatched). This means Kotlin logs/reports use a different string than what C++ actually uses for FPS.

**Solution:** Remove the duplicate cert from Kotlin (it's never used via JNI), keep only in C++. Update the test to verify C++ patching behavior instead.

- [ ] **Step 1: Update Kotlin to remove FAIRPLAY_CERTIFICATE constant**

```kotlin
// In AppleMusicHookCore.kt, remove the FAIRPLAY_CERTIFICATE constant
// The cert is only used in C++ native code via JNI
// Keep other FairPlay-related constants
```

Delete lines: delete the entire `const val FAIRPLAY_CERTIFICATE = "MIIE..."` block from AppleMusicHookCore.kt

- [ ] **Step 2: Update test to reflect removed constant**

```kotlin
// In AppleMusicHookCoreTest.kt, remove or modify the test
// `fairPlayDefaultsMatchOriginalScript` that checks FAIRPLAY_CERTIFICATE length

// Change to verify only non-cert FairPlay constants
@Test
fun fairPlayDefaultsMatchOriginalScript() {
    assertEquals("skd://itunes.apple.com/P000000000/s1/e1", AppleMusicHookCore.PRESHARE_KEY_URI)
    assertEquals("com.apple.streamingkeydelivery", AppleMusicHookCore.KEY_FORMAT)
    assertEquals("1", AppleMusicHookCore.KEY_FORMAT_VERSION)
    assertEquals("https://play.itunes.apple.com/WebObjects/MZPlay.woa/music/fps", AppleMusicHookCore.SERVER_URI)
    assertEquals("simplified", AppleMusicHookCore.PROTOCOL_TYPE)
    // FAIRPLAY_CERTIFICATE removed from Kotlin — cert is managed in C++ native layer only
}
```

- [ ] **Step 3: Add explanatory comment to C++ certificate patching**

In `appledecryptor.cpp`, add comment above `initFairPlayCertificate`:

```cpp
// FairPlay certificate patching strategy:
// The base certificate constant kFairPlayCertificate is stored at length
// kFairPlayCertificateExpectedLength - strlen(kFairPlayCertificatePatch)
// (currently 3462 bytes). At offset kFairPlayCertificatePatchOffset (3218),
// two bytes "Pa" are inserted to produce the full 3464-byte certificate.
// This avoids storing the complete certificate literal in source.
// The Kotlin layer does not hold a copy — the certificate exists only here.
```

- [ ] **Step 4: Run tests**

Run: `cd /workspace/alac-codec-main && ./gradlew :app:testDebugUnitTest 2>&1 | tail -30`

Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
cd /workspace/alac-codec-main
git add app/src/main/kotlin/com/neurax08/xposed/appledecryptor/AppleMusicHookCore.kt app/src/test/kotlin/com/neurax08/xposed/appledecryptor/AppleMusicHookCoreTest.kt app/src/main/cpp/appledecryptor.cpp
git commit -m "fix: unify FairPlay certificate between Kotlin and C++ layers"
```

---

### Task 2: Add Thread Safety to C++ Global State

**Files:**
- Modify: `app/src/main/cpp/appledecryptor.cpp`

**Interfaces:**
- Consumes: Global state `gResolver`, `gSessionCtrl`, `gKdContext`, `gPreshareKdContext`
- Produces: Mutex-guarded thread-safe access to all global state

**Problem:** `prepareSession()` and `decryptSample()` can be called from multiple threads (M3U8 server, decrypt server, Xposed lifecycle) but global state has no synchronization.

- [ ] **Step 1: Add mutex and lock-guard macros**

Add after the `#include` block in `appledecryptor.cpp`:

```cpp
#include <mutex>

// Global state lock — protects gResolver, gSessionCtrl, gKdContext, gPreshareKdContext
std::mutex gGlobalLock;

// RAII lock helper
#define LOCK_GLOBAL() std::lock_guard<std::mutex> _lock(gGlobalLock)
```

- [ ] **Step 2: Guard resolver state mutations**

In `resolveSymbols()`, add lock at the top:

```cpp
void resolveSymbols() {
    LOCK_GLOBAL();
    // ... existing code ...
}
```

Note: `openTargetLibrary()` reads/writes `gResolver` fields that are also used elsewhere. This needs the lock too.

```cpp
void *openTargetLibrary() {
    LOCK_GLOBAL();
    // ... existing code can stay the same, just add LOCK_GLOBAL() at top ...
}
```

But wait — `openTargetLibrary` is called from `resolveSymbols` which also locks. So we'd deadlock with `std::mutex`. Use `std::recursive_mutex` instead.

- [ ] **Step 3: Change to recursive_mutex**

```cpp
// Global state lock — recursive to allow nested locking from resolveSymbols -> openTargetLibrary
std::recursive_mutex gGlobalLock;
#define LOCK_GLOBAL() std::lock_guard<std::recursive_mutex> _lock(gGlobalLock)
```

- [ ] **Step 4: Guard session state in prepareSession**

```cpp
bool prepareSession(const char *adamId, const char *uri) {
    LOCK_GLOBAL();
    // ... existing code continues ...
}
```

- [ ] **Step 5: Guard decryptSample access to gKdContext and gResolver**

```cpp
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_neurax08_xposed_appledecryptor_AppleMusicNativeBridge_nativeDecryptSample(...) {
    LOCK_GLOBAL();
    // ... existing code continues ...
}
```

- [ ] **Step 6: Guard resolverStatus**

```cpp
const char *resolverStatus() {
    LOCK_GLOBAL();
    resolveSymbols(); // has its own LOCK_GLOBAL but recursive_mutex handles nested
    // ... rest ...
}
```

- [ ] **Step 7: Run tests**

Run: `cd /workspace/alac-codec-main && ./gradlew :app:testDebugUnitTest 2>&1 | tail -30`

Expected: All tests pass

- [ ] **Step 8: Commit**

```bash
cd /workspace/alac-codec-main
git add app/src/main/cpp/appledecryptor.cpp
git commit -m "fix: add recursive mutex for C++ global state thread safety"
```

---

### Task 3: Add Xposed Hook Concurrent Access Protection

**Files:**
- Modify: `app/src/main/kotlin/com/neurax08/xposed/appledecryptor/AppleDecryptorModule.kt`

**Interfaces:**
- Consumes: `playbackLeaseProxy` (AtomicReference), `requestAssetMethod` (AtomicReference)
- Produces: Thread-safe proxy/method acquisition with fallback retry

**Problem:** `requestAsset` hook sets `playbackLeaseProxy` on every invocation. Under concurrent calls, the proxy reference gets overwritten. Use `compareAndSet` to set only once per session.

- [ ] **Step 1: Update hook to use compareAndSet**

In `installAppleMusicHooks`, modify the hook intercept:

```kotlin
hook(requestAsset).intercept { chain ->
    // Set proxy only once per lifecycle using compareAndSet
    playbackLeaseProxy.compareAndSet(null, chain.thisObject)
    requestAssetMethod.compareAndSet(null, requestAsset)

    val args = chain.args.toTypedArray()
    moduleLog(Log.INFO, TAG, "requestAsset before: ${AppleMusicHookCore.describeRequestAssetArgs(args)}")
    if (args.size >= 3) {
        args[2] = AppleMusicHookCore.hlsAssetKinds()
    }
    moduleLog(Log.INFO, TAG, "requestAsset after: ${AppleMusicHookCore.describeRequestAssetArgs(args)}")

    val result = chain.proceed(args)
    moduleLog(Log.INFO, TAG, "requestAsset result: ${AppleMusicHookCore.describeRequestAssetResult(result)}")
    AppleMusicHookCore.extractDownloadUrl(result)?.let { url ->
        moduleLog(Log.INFO, TAG, "HLS_URL $url")
    }
    result
}
```

- [ ] **Step 3: Add thread-safe proxy eviction on release detection**

In `requestHlsUrl`, add retry logic when proxy is stale:

```kotlin
private fun requestHlsUrl(adamId: String): String? {
    var proxy = playbackLeaseProxy.get()
    var method = requestAssetMethod.get()
    
    if (proxy == null || method == null) {
        // Try to rediscover from classloader
        proxy = findPlaybackLeaseProxyFromClassLoader()
        method = proxy?.let { resolveRequestAssetMethod(it.javaClass) }
        if (proxy == null || method == null) {
            moduleLog(Log.WARN, TAG, "M3U8 request skipped: proxy/method unavailable ${describeLeaseRefreshState()}")
            return null
        }
    }

    return runCatching {
        val result = method.invoke(proxy, adamId.toLongOrNull() ?: 0L, null, AppleMusicHookCore.hlsAssetKinds(), false)
        AppleMusicHookCore.extractDownloadUrl(result)
    }.onFailure { error ->
        moduleLog(Log.WARN, TAG, "M3U8 requestAsset refresh failed for adamId=$adamId", error)
        // If invocation fails, proxy may be stale — clear for rediscovery
        if (error is IllegalArgumentException || error is NullPointerException) {
            playbackLeaseProxy.compareAndSet(proxy, null)
        }
    }.getOrNull()
}
```

- [ ] **Step 4: Run tests**

Run: `cd /workspace/alac-codec-main && ./gradlew :app:testDebugUnitTest 2>&1 | tail -30`

Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
cd /workspace/alac-codec-main
git add app/src/main/kotlin/com/neurax08/xposed/appledecryptor/AppleDecryptorModule.kt
git commit -m "fix: add compareAndSet for thread-safe hook proxy acquisition"
```

---

### Task 4: Add Stack Alignment Assertion in ARM64 Assembly Thunks

**Files:**
- Modify: `app/src/main/cpp/appledecryptor.cpp`

**Interfaces:**
- Consumes: `callGetPersistentKeySafely`, `callDecryptContextSafely` 
- Produces: SP-alignment-verified ARM64 calls

**Problem:** ARM64 ABI requires SP to be 16-byte aligned before BL. The inline assembly relies on compiler-generated SP state at call site.

- [ ] **Step 1: Add SP alignment check before ARM64 calls**

Before the `#if defined(__aarch64__)` block, add:

```cpp
// Debug assertion: verify SP is 16-byte aligned for AAPCS64
#if defined(__aarch64__) && !defined(NDEBUG)
#define ASSERT_SP_ALIGNED() do { \
    volatile void *sp_value = __builtin_frame_address(0); \
    if (reinterpret_cast<uintptr_t>(sp_value) & 0xf) { \
        nativeLog(ANDROID_LOG_ERROR, "SP misaligned before ARM64 call: %p", sp_value); \
    } \
} while (0)
#else
#define ASSERT_SP_ALIGNED() ((void)0)
#endif
```

- [ ] **Step 2: Insert assertions in safe call wrappers**

In `callGetPersistentKeySafely`:

```cpp
bool callGetPersistentKeySafely(...) {
    ASSERT_SP_ALIGNED();
    // ... existing sigsetjmp and call ...
}
```

In `callDecryptContextSafely`:

```cpp
bool callDecryptContextSafely(...) {
    ASSERT_SP_ALIGNED();
    // ... existing sigsetjmp and call ...
}
```

In `callDecryptSampleSafely`:

```cpp
bool callDecryptSampleSafely(...) {
    ASSERT_SP_ALIGNED();
    // ... existing code ...
}
```

- [ ] **Step 3: Run tests**

Run: `cd /workspace/alac-codec-main && ./gradlew :app:testDebugUnitTest 2>&1 | tail -30`

Expected: All tests pass (assertion is in debug builds only and won't fire on valid call paths)

- [ ] **Step 4: Commit**

```bash
cd /workspace/alac-codec-main
git add app/src/main/cpp/appledecryptor.cpp
git commit -m "refactor: add AAPCS64 SP alignment assertion in ARM64 thunks"
```

---

### Task 5: Generalize CMake Host Platform Path

**Files:**
- Modify: `app/src/main/cpp/CMakeLists.txt`

**Interfaces:**
- Produces: Cross-platform NDK runtime library path resolution

**Problem:** `ANDROID_RUNTIME_LIB_DIR` hardcodes `linux-x86_64` host, fails on macOS/Windows.

- [ ] **Step 1: Replace hardcoded host with CMake variable**

```cmake
# Detect host OS for NDK path resolution
if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
    set(HOST_TAG "linux-x86_64")
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    set(HOST_TAG "darwin-x86_64")
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(HOST_TAG "windows-x86_64")
else()
    message(FATAL_ERROR "Unsupported host system: ${CMAKE_HOST_SYSTEM_NAME}")
endif()

set(ANDROID_RUNTIME_LIB_DIR "${ANDROID_NDK}/toolchains/llvm/prebuilt/${HOST_TAG}/sysroot/usr/lib/aarch64-linux-android")
```

- [ ] **Step 2: Run tests (build verification)**

Run: `cd /workspace/alac-codec-main && ./gradlew :app:testDebugUnitTest 2>&1 | tail -30`

Expected: All tests pass

- [ ] **Step 3: Commit**

```bash
cd /workspace/alac-codec-main
git add app/src/main/cpp/CMakeLists.txt
git commit -m "build: generalize NDK host platform path in CMake"
```

---

### Task 6: Preserve VersionCode and TargetSdk from Apple Music APK

**Files:**
- None (informational improvement, no code change needed)

**Analysis:** The Frida original script has no version checks. The Xposed module's `build.gradle.kts` uses hardcoded `versionCode=1` and `targetSdk=34`. For best compatibility with Apple Music 5.2.1 (targetSdk=35, versionCode=1545), consider aligning.

No code change required for this task — it's a note for future version management strategy.

---

## Summary of All Changes

| # | File | Change |
|---|------|--------|
| 1 | `AppleMusicHookCore.kt` | Remove FAIRPLAY_CERTIFICATE constant |
| 1 | `AppleMusicHookCoreTest.kt` | Update cert test |
| 1 | `appledecryptor.cpp` | Add cert patching comment |
| 2 | `appledecryptor.cpp` | Add std::recursive_mutex for global state |
| 3 | `AppleDecryptorModule.kt` | Use compareAndSet in hook, stale proxy eviction |
| 4 | `appledecryptor.cpp` | Add SP alignment assertion macro |
| 5 | `CMakeLists.txt` | Dynamic host platform detection |
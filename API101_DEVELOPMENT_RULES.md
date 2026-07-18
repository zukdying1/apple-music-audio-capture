# libxposed API 101 Development Rules

This project targets libxposed API 101. Keep Xposed module code compatible with the API 101 model unless a newer API is explicitly selected later.

## Dependency Rule

- Use `io.github.libxposed:api:101.0.1` for the Xposed API dependency.
- Treat the libxposed API as compile-time/module API, not as an app UI dependency.
- Do not mix legacy `de.robv.android.xposed` entrypoints with libxposed API 101 entrypoints.

## Module Entrypoints

- Java/Kotlin entry classes are declared in `src/main/resources/META-INF/xposed/java_init.list`.
- Native entry libraries are declared in `src/main/resources/META-INF/xposed/native_init.list` only when a native libxposed entrypoint is needed.
- Keep one fully qualified class name per line in `java_init.list`.
- The module implementation should extend `io.github.libxposed.api.XposedModule`.
- The module constructor must match API 101 expectations and accept the framework base interface and module parameters.

## Module Metadata

- Provide `src/main/resources/META-INF/xposed/module.prop`.
- Set `minApiVersion=101`.
- Set `targetApiVersion=101` unless intentionally migrating to a newer API.
- Use Android app resources for user-facing module name and description where possible.
- Use `staticScope=true` when this module is intentionally limited to known target packages.

## Lifecycle Rules

- Use `onModuleLoaded` for one-time module initialization inside the target process.
- Use `onPackageLoaded` when the default classloader is ready and hooks must be installed early.
- Use `onPackageReady` when the app classloader and app context-dependent state are needed.
- Use `onSystemServerStarting` only for system server work; do not put app package hooks there.
- Keep target package checks close to lifecycle entrypoints so hooks never run in unrelated processes.

## Hooking Rules

- Use libxposed `XposedInterface` APIs for Java/Kotlin method hooks.
- Prefer explicit classloader usage from lifecycle params over global class lookup.
- Log hook failures with enough package, process, class, and method context to debug remotely.
- Treat `HookFailedError` and other framework errors as fatal integration signals, not normal control flow.
- Keep hook callbacks small; move complex work into project-owned helpers.

## Native Integration Rules

- Java/Kotlin Xposed code is responsible for loading project native libraries at the correct process/lifecycle point.
- Native hook implementation should be isolated under the app native source tree and loaded only in the target process.
- Prefer ShadowHook for Android inline hooks in this project; add ByteHook only if PLT/GOT hook requirements appear.
- Do not introduce Dobby, xHook, or additional native hook frameworks without a concrete compatibility reason.
- Native code must be arm64-first for the current target; add other ABIs only when tested.

## Scope And Safety Rules

- Default target scope is Apple Music only unless the user expands scope.
- Do not install hooks in zygote or unrelated apps.
- Do not perform expensive scanning or symbol lookup repeatedly; cache per process after the target library is loaded.
- Fail closed: if target package, target library, or required symbol is missing, log and skip hooks.

## Testing Rules

- All builds must be executed in the configured remote SSH environment, not directly on the local workspace.
- Sync local source to the remote build workspace before running Gradle.
- Run at least `./gradlew :app:assembleDebug` remotely before claiming build success.
- If native code is present, verify the APK contains expected `.so` files for the configured ABI.

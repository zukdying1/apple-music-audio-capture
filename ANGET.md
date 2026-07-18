# Agent Instructions

## Remote Build Requirement

All builds for this project must run inside the remote SSH environment:

```sh
ssh -p 8022 10645@localhost
```

Do not run Gradle builds directly in the local workspace. The local workspace is the editing source of truth; the remote SSH workspace is the only build execution environment.

The build sequence is mandatory:

1. Edit files locally under `/root/AndroidIDEProjects/AppleDecryptor/`.
2. Sync the full project to `10645@localhost:~/AppleDecryptor/` with `rsync`.
3. Run Gradle only through `ssh -p 8022 10645@localhost`.
4. If the build fails, fix locally, sync again, then rerun the remote Gradle command.

Do not claim tests or builds pass unless the passing command was executed on the remote host after a successful sync.

## Source Sync Rule

Before every remote build, sync the local project to the remote host with `rsync`.

Recommended command from the local workspace parent:

```sh
rsync -az --delete \
  --exclude '.gradle/' \
  --exclude 'build/' \
  --exclude 'app/build/' \
  --exclude '.idea/' \
  --exclude '.kotlin/' \
  /root/AndroidIDEProjects/AppleDecryptor/ \
  -e 'ssh -p 8022' \
  10645@localhost:~/AppleDecryptor/
```

Use the remote path `~/AppleDecryptor/` unless the user explicitly changes it.

## Remote Build Commands

After syncing, execute Gradle on the remote host:

```sh
ssh -p 8022 10645@localhost 'cd ~/AppleDecryptor && sh ./gradlew :app:assembleDebug'
```

For a clean build:

```sh
ssh -p 8022 10645@localhost 'cd ~/AppleDecryptor && sh ./gradlew clean :app:assembleDebug'
```

For unit tests:

```sh
ssh -p 8022 10645@localhost 'cd ~/AppleDecryptor && sh ./gradlew :app:testDebugUnitTest'
```

For diagnostics:

```sh
ssh -p 8022 10645@localhost 'cd ~/AppleDecryptor && sh ./gradlew :app:tasks'
```

Use `sh ./gradlew` on the remote host because wrapper execute bits may not be preserved consistently across syncs and Android shell environments.

## Remote Install And Reload

After `:app:assembleDebug` succeeds, install the generated APK from the remote build output through root. Copy it to `/data/local/tmp` first so `pm install` can read it reliably:

```sh
ssh -p 8022 10645@localhost 'su -c '\''cp /data/data/com.tom.rv2ide/files/home/AppleDecryptor/app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/appledecryptor-debug.apk && chmod 644 /data/local/tmp/appledecryptor-debug.apk && pm install -r /data/local/tmp/appledecryptor-debug.apk'\'''
```

Reload the LSPosed module inside Apple Music by force-stopping and relaunching the target package:

```sh
ssh -p 8022 10645@localhost 'su -c '\''am force-stop com.apple.android.music; sleep 1; monkey -p com.apple.android.music 1 >/dev/null 2>&1; sleep 4; pidof com.apple.android.music'\'''
```

Do not treat a newly installed APK as active until Apple Music has been restarted and a fresh `pidof com.apple.android.music` value is visible.

## Automated Deploy Smoke Test

For an end-to-end remote validation after local edits, run this sequence:

```sh
rsync -az --delete \
  --exclude '.gradle/' \
  --exclude 'build/' \
  --exclude 'app/build/' \
  --exclude '.idea/' \
  --exclude '.kotlin/' \
  /root/AndroidIDEProjects/AppleDecryptor/ \
  -e 'ssh -p 8022' \
  10645@localhost:~/AppleDecryptor/

ssh -p 8022 10645@localhost 'cd ~/AppleDecryptor && sh ./gradlew :app:testDebugUnitTest'
ssh -p 8022 10645@localhost 'cd ~/AppleDecryptor && sh ./gradlew :app:assembleDebug'
ssh -p 8022 10645@localhost 'su -c '\''cp /data/data/com.tom.rv2ide/files/home/AppleDecryptor/app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/appledecryptor-debug.apk && chmod 644 /data/local/tmp/appledecryptor-debug.apk && pm install -r /data/local/tmp/appledecryptor-debug.apk'\'''
ssh -p 8022 10645@localhost 'su -c '\''am force-stop com.apple.android.music; sleep 1; monkey -p com.apple.android.music 1 >/dev/null 2>&1; sleep 4; pidof com.apple.android.music'\'''
```

Then hit the decrypt socket with a known Apple Music key URI. Prefer a fresh `skd://` URI from the current M3U8 when testing real decryption setup. This URI was used successfully as a session-preparation smoke test:

```sh
ssh -p 8022 10645@localhost '(/system/bin/toybox printf "\0121821585531\045skd://itunes.apple.com/p1345011921/c6" | /system/bin/toybox timeout 8 /system/bin/toybox nc 127.0.0.1 10020 | /system/bin/toybox xxd -p); code=$?; /system/bin/toybox printf "exit=%s\n" "$code"'
```

If only the socket/error path needs testing, a dummy URI is acceptable and should not crash Apple Music:

```sh
ssh -p 8022 10645@localhost '(/system/bin/toybox printf "\0121821585531\041skd://itunes.apple.com/P000000001" | /system/bin/toybox timeout 8 /system/bin/toybox nc 127.0.0.1 10020 | /system/bin/toybox xxd -p); code=$?; /system/bin/toybox printf "exit=%s\n" "$code"'
```

After the socket request, inspect logs and process state:

```sh
ssh -p 8022 10645@localhost 'su -c '\''grep -n -i -E "AppleDecryptor|native:|native bridge|prepareSession|resolver|ready|persistent key|kdContext|decryptSample|Fatal|crash|signal" /data/adb/lspd/log/*.log | tail -300'\'''
ssh -p 8022 10645@localhost 'su -c '\''pid=$(pidof com.apple.android.music); echo pid=$pid; /system/bin/toybox egrep "libappledecryptor|libandroidappmusic" /proc/$pid/maps | head -25'\'''
```

Expected healthy native session logs include `native bridge available=true status=ready`, `resolver symbols`, `prepareSession adamId=0 kdContext=...`, and for a valid real key URI, `prepareSession target result=true` / `prepared=true`. A dummy or stale key URI may log `getPersistentKey aborted` or `persistent key is null`, but Apple Music must remain alive.

## Build Reporting

- Report the exact remote command used.
- Report whether rsync completed before the build.
- Report the Gradle task result from the remote SSH session.
- If the build fails, fix locally, rsync again, and rerun remotely.
- Never substitute a local Gradle result for remote validation.

## LSPosed Logs

Framework logs are available on the remote device under:

```sh
/data/adb/lspd/log
```

Reading LSPosed logs requires root. Always wrap log inspection with `su -c`:

```sh
ssh -p 8022 10645@localhost 'su -c '\''ls -lt /data/adb/lspd/log'\'''
```

To inspect AppleDecryptor hook activity:

```sh
ssh -p 8022 10645@localhost 'su -c '\''grep -n -i -E "AppleDecryptor|requestAsset|SVPlaybackLease|MediaAssetInfo|Exception|Error" /data/adb/lspd/log/modules_*.log | tail -120'\'''
```

Do not use `/data/lspd/log/modules`; that path is not valid for this device.

## Project Development Rules

- Follow `API101_DEVELOPMENT_RULES.md` for libxposed API 101 module work.
- Prefer small, focused changes.
- Do not add native hook frameworks casually; ShadowHook is the preferred first native hook framework for this project.
- Keep source edits local, then sync to remote for validation.

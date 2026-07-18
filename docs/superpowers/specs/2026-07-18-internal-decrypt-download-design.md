# Internal Decrypt & Download Design

## Scope

Eliminate the external-socket dependency by integrating audio decrypt and M4A export directly into the AppleDecryptor Xposed module. Decrypted ALAC frames are muxed into M4A files via Android MediaMuxer, managed through a download queue with three triggers: auto-detect on playback, manual adamId input via the module UI, and a notification action button.

## Architecture

```
Apple Music playback
  ↓ requestAsset hook
AppleDecryptorModule (Kotlin)
  ├─ AutoTracker: records adamId on each requestAsset intercept
  ├─ HlsDownloader: downloads M3U8 → fetches encrypted fMP4 segments
  ├─ JNI bridge → appledecryptor.cpp: decryptSample (existing) + batch decrypt
  ├─ AlacFrameExtractor: extracts raw ALAC frames from decrypted fMP4
  ├─ M4aWriter: Android MediaMuxer → writes M4A to /sdcard/Music/AppleDecryptor/
  ├─ DownloadManager: queue persistence (SQLite via Room), concurrency control
  ├─ NotificationController: foreground notification + "Download this track" action
  └─ MainActivity: Compose UI — download queue list + manual input + status
```

**Socket removal:** The existing socket servers (ports 20020, 10020) are **kept but deprecated** — they remain for backward compatibility and debugging, but the primary path goes through the internal pipeline above.

## Tech Stack

- Kotlin, Android MediaMuxer (API 26+), Room/SQLite, Compose UI
- C++17 (existing native layer) — no new native dependencies
- AndroidX Media3 for M4A metadata (optional, API 30+)

## Data Flow

### Auto-Detection Flow

```
requestAsset(id=long, adamId=String, assetKinds=[], force=bool)
  ↓ hook intercept
AutoTracker.record(adamId)
  ↓
HlsDownloader.fetchM3U8(adamId)
  → socket 20020 or direct requestAsset() call
  ↓
HlsDownloader.parseSegments(m3u8)
  → list of encrypted fMP4 segment URLs + key URI
  ↓
For each segment:
  HTTP GET encrypted segment
  ↓
  C++ decryptSample (reuse existing native code)
  ↓
  AlacFrameExtractor.parse(decryptedBytes)
  → raw ALAC AudioSample
  ↓
  M4aWriter.addSample(sample)
  ↓
On all segments complete:
  M4aWriter.finish() → /sdcard/Music/AppleDecryptor/{title}.m4a
  DownloadManager.markComplete(adamId)
  NotificationController.notifyComplete(title)
```

### Manual Input Flow

MainActivity → text field + "Download" button
  → DownloadManager.enqueue(adamId)
  → same pipeline as auto-detection

### Notification Flow

Foreground service notification while Music is playing
  Shows: current track title, artist, album art
  Action button: "Download"
  → DownloadManager.enqueue(currentAdamId)

## Components

### 1. AutoTracker

- Location: `AppleDecryptorModule.kt` (within the existing hook intercept)
- Listens to `requestAsset` calls in real-time
- Extracts `adamId` from arguments on every call
- Deduplicates: if already in queue or already downloaded, skip
- Auto-adds to `DownloadManager` queue

### 2. HlsDownloader

- New file: `HlsDownloader.kt`
- Fetches M3U8 playlist using the existing `requestAsset` hook result (no socket needed)
- Parses `#EXTINF` segments and `#EXT-X-KEY` key URIs
- Downloads encrypted fMP4 segments via `HttpURLConnection` or `OkHttp`
- Reports progress per segment
- Supports cancellation

### 3. AlacFrameExtractor

- New file: `AlacFrameExtractor.kt`
- Input: decrypted fMP4 bytes (moof + mdat boxes)
- Parses ISO Base Media File Format (ISOBMFF) to locate ALAC `mdat` samples
- Uses `MediaExtractor` if available (Android API 30+) for clean sample extraction
- Fallback: manual `moof`/`mdat` box parsing
- Output: `List<AudioSample>` (byte[] + timestamps + duration)

### 4. M4aWriter

- New file: `M4aWriter.kt`
- Uses `android.media.MediaMuxer` (API 26+)
- Creates a new M4A track with ALAC format
- Writes each ALAC sample in presentation order
- Sets correct sample durations using `MediaFormat`
- Output path: `/sdcard/Music/AppleDecryptor/{title}.m4a`
- Sets proper MIME type for ALAC: `audio/alac`

### 5. DownloadManager

- New file: `DownloadManager.kt`
- Queue persistence: Room database with `DownloadQueueItem` entity
- Fields: `id`, `adamId`, `title`, `artist`, `status` (QUEUED/DOWNLOADING/COMPLETED/FAILED), `progress`, `filePath`, `createdAt`
- Singleton with coroutine-based concurrency
- Supports: enqueue, cancel, retry, clear completed

### 6. NotificationController

- New file: `NotificationController.kt`
- Creates a foreground service notification while Apple Music is active
- Shows current playback metadata (title, artist) via `MediaMetadata` listener
- Action button: "Download this track"
- Calls `DownloadManager.enqueue(adamId)` on action tap
- Notification channel: "AppleDecryptor Downloads"

### 7. MainActivity (Rewrite)

- Current no-op Compose UI → full download manager UI
- Tabs or sections:
  - **Download Queue** — list of items with progress bars, status badges, cancel buttons
  - **Manual Input** — text field for adamId + "Add" button
  - **Settings** — download path, auto-download toggle, keep socket toggle
- Uses ViewModel + Room Flow for reactive UI updates
- Material3 theme (already configured)

### 8. C++ Batch Decrypt

- New JNI function in `appledecryptor.cpp`:
  - `nativeDecryptSamples(jobjectArray samples)` → `byte[][]`
  - Takes multiple encrypted frames, returns decrypted frames
  - Reuses existing `gKdContext`, `gSessionCtrl`, `decryptSample` logic
  - Avoids repeated JNI overhead per frame

## File Changes Summary

| File | Action | Purpose |
|------|--------|---------|
| `MainActivity.kt` | Rewrite | Download manager UI with queue + manual input |
| `AppleDecryptorModule.kt` | Modify | Add AutoTracker logic to hook handler |
| `AppleMusicNativeBridge.kt` | Modify | Add batch decrypt interface |
| `appledecryptor.cpp` | Modify | Add batch decrypt JNI function |
| `HlsDownloader.kt` | Create | HLS stream downloader |
| `AlacFrameExtractor.kt` | Create | ISOBMFF ALAC frame parser |
| `M4aWriter.kt` | Create | MediaMuxer M4A writer |
| `DownloadManager.kt` | Create | Queue persistence + concurrency |
| `DownloadDatabase.kt` | Create | Room database |
| `NotificationController.kt` | Create | Foreground notification |
| `AndroidManifest.xml` | Modify | Permissions + foreground service |
| `build.gradle.kts` | Modify | Room + OkHttp dependencies |
| `app/src/main/res/values/strings.xml` | Modify | UI strings |

## Permissions

- `android.permission.WRITE_EXTERNAL_STORAGE` (legacy, for scoped storage fallback)
- `android.permission.FOREGROUND_SERVICE_DATA_SYNC` (already declared)
- `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK` (already declared)
- `android.permission.POST_NOTIFICATIONS` (already declared)
- `android.permission.INTERNET` (already declared)

## Constraints

- Minimum API: 26 (MediaMuxer requirement)
- Storage path: `/sdcard/Music/AppleDecryptor/` (compatible with MediaStore on API 30+)
- Download concurrency: max 2 simultaneous downloads
- No ffmpeg or external native libraries — use only Android system APIs
- Socket servers are preserved but deprecated (can be disabled via settings toggle)

## Risk Controls

- **Download failure:** Individual segment failures do not abort entire download; retry up to 3 times
- **Storage full:** Check available space before writing; fail gracefully with user notification
- **Module unload:** In-progress downloads are saved to Room queue; resume on next module load
- **Concurrent playback:** Multiple tracks in queue download sequentially per track, segments per track stream
- **ALAC format mismatch:** If MediaMuxer does not support ALAC track format on device, fall back to PCM WAV
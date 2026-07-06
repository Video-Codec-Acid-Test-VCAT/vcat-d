# DIFF_SUMMARY

Running summary of notable change sets on `main`, newest first. Each entry preserves the
description of what shipped so the history is not lost when the working tree moves on.

---

## 2026-07-05 — Rename telemetry logs to `vcatd_log_`, version `0.3.2`

**Theme:** Change the telemetry CSV filename prefix from `logs_` to `vcatd_log_`.

### `video/FullScreenPlayerActivity.java`
- Telemetry file now written as `vcatd_log_<unixtime>.csv` (was `logs_<unixtime>.csv`).

### `ui/FragmentTestLogs.java`, `tools/StorageManager.java`
- Log-file list filter and `findLatestLogFile()` scan now match the `vcatd_log_` prefix.

### `models/TestResultsItem.java`
- Updated the filename-format doc comment. Fixed `getTimeStamp()` to parse the timestamp
  from the **last** `_` (the new prefix contains two underscores; the old `indexOf('_')`
  would have failed to parse). `lastIndexOf('_')` works for both old and new names.

**Note:** the list/scan filters require the `vcatd_log_` prefix, so pre-existing
`logs_*.csv` files on device no longer appear in the Logs tab or as "latest". Clean break —
new runs only.

---

## 2026-07-05 — adb-usable root path in `ACTION_LOG_ROOT`, version `0.3.1`

**Theme:** After the SAF migration, `ACTION_LOG_ROOT` logged only the `content://` tree URI
(e.g. `…/tree/primary%3Avcat-d`), which `adb pull` / `adb shell ls` cannot use. Restore a
real filesystem path in the log output.

### `tools/StorageManager.java`
- Added `getRootFsPath()`: best-effort conversion of the SAF root tree URI to an absolute
  filesystem path. Parses the tree document id (`<volume>:<relative/path>` from the
  `externalstorage` provider) — `primary` maps to `/sdcard`, other volumes to
  `/storage/<volumeId>`. Returns `null` if no root is set or the volume can't be resolved.

### `service/CommandReceiver.java`
- `ACTION_LOG_ROOT` now logs `root_folder=<fs path> (uri=<tree uri>)` so the path is
  directly usable by adb, with the raw URI kept for reference.

### Version (`app/build.gradle`)
- `versionCode` 3000 → 3001; `versionName` `0.3.0` → `0.3.1`.

---

## 2026-07-02 — Rebrand to `vcat-d`, version `0.3.0`

**Theme:** Rename the app to `vcat-d` everywhere it is externally visible, and rev the
version to `0.3.0` / code `3000`. Internal identifiers (the `com.roncatech.vcat`
applicationId + Java package, the `Theme.VCAT.Splash` style, logcat tags, code comments)
were intentionally left unchanged.

### Version & artifact naming (`app/build.gradle`)
- `versionCode` 76 → `3000`; `versionName` `0.2.0.46` → `0.3.0`.
- `archivesBaseName` and release `outputFileName` prefixes `VCAT-` → `vcat-d-`
  (release APK is now `vcat-d-0.3.0-v3000.apk`).
- `cleanCustomRelease` fileTree include patterns `VCAT-*` → `vcat-d-*`.

### Gradle project (`settings.gradle`)
- `rootProject.name` `vcat` → `vcat-d`.

### External / user-facing text
- Display name (`app_name`), manifest `android:label`, and About strings were already
  `vcat-d` (no change needed).
- `README.md`: logo `alt` text → `vcat-d Logo`; H1 banner →
  `vcat-d™ (formerly VCAT™) — Video Codec Acid Test™`. GitHub org/repo URLs
  (`Video-Codec-Acid-Test-VCAT`) left intact — they are live links.

### License text
- License headers in 118 source files: every header `VCAT` → `vcat-d` (program-name line,
  SPDX copyright, GPL clauses, artwork notice). Only header phrasing was touched; logcat
  tags, comments, and the `Theme.VCAT.Splash` style id were left alone.
- `LICENSE-PLUGIN-EXCEPTION.md`: now reads "The `vcat-d` project (formerly VCAT) …".
- The verbatim GPL `LICENSE` file was left unmodified (must stay verbatim; it does not name
  the app).

### Left as-is (internal, per design)
- `applicationId` / Java package `com.roncatech.vcat` — changing would create a new app
  identity (breaks upgrades/signing).
- `Theme.VCAT.Splash` style, `Log.x("VCAT", …)` logcat tags, and in-code comments.

---

## SAF storage migration + nav-bar fix (version `0.2.0.46`)

**Theme:** Migration from `MANAGE_EXTERNAL_STORAGE` + raw `File` I/O to the Storage
Access Framework (SAF) with `DocumentFile` / `ContentResolver`, plus a fix for the system
navigation bar obscuring VCAT's bottom menu, plus a version/dependency bump. See
`STORAGE_MIGRATION.md` (this folder) for the design rationale.

**Scope:** 26 files changed (~712 insertions / ~767 deletions).

---

## Build & manifest

### `app/build.gradle`
- `versionCode` 74 → 75, `versionName` `0.2.0.44` → `0.2.0.45`.
- `libvcat` dependency bumped `0.0.3.10` → `0.0.3.11`.
- Added `packagingOptions { jniLibs { useLegacyPackaging false } }`.

### `app/src/main/AndroidManifest.xml`
- Removed three storage permissions: `MANAGE_EXTERNAL_STORAGE`,
  `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`. SAF tree URIs need none of them.

---

## Core storage layer

### `tools/StorageManager.java` (full rewrite)
- Dropped the `File` / `Environment.getExternalStorageDirectory()` model.
- Removed the `ROOT` enum value and the `playListFolder()`/`resultsFolder()`/`mediaFolder()`
  path helpers; `VCATFolder` is now just `PLAYLIST`, `MEDIA`, `TEST_RESULTS`.
- New static `sRootTreeUri` plus:
  - `init(Context, Uri rootTreeUri)` — stores the root URI, logs `root_folder=…`, and
    creates the three sub-folders via `DocumentFile.createDirectory()`.
  - `getRoot(Context)` / `getRootUri()` — accessors for the SAF root.
  - `getFolder(Context, VCATFolder)` — returns (creating if absent) the sub-folder
    `DocumentFile`. **Signature changed** — now takes a `Context`.
  - `findLatestLogFile(Context)` — scans `DocumentFile.listFiles()` for `logs_*.csv` and
    picks the largest timestamp.
  - `readLastTimestamp(Context, DocumentFile)` — replaced the `RandomAccessFile`
    backward-seek with a forward `ContentResolver.openInputStream()` line scan (RandomAccessFile
    doesn't work on SAF).
- Deleted `getFullPathFromUri()` and `createVcatFolder()`.

### `tools/UriUtils.java`
- Added `resolveMediaUri(Context, Uri)`: converts a legacy `file://` media URI into a current
  `content://` SAF URI by locating the file under the SAF media tree (relative path after
  `/media/` first, then filename-only fallback). `content://` / `http(s)://` pass through
  unchanged. Enables old playlists to keep working after the migration.

### `models/SharedViewModel.java`
- Removed the `LOG_FOLDER = "/vcat/test_results"` constant.
- Added `KEY_ROOT_TREE_URI` pref plus `getRootUri()` / `setRootUri()`.
- Simplified `setFolderUri()` (folded in the old private `saveFolderUri()`); the stored
  folder URI is now a SAF tree URI instead of a `file://` URI.

---

## Telemetry & result models

### `telemetry/TelemetryLogger.java`
- Constructor now `TelemetryLogger(Context, String csvFileName)` instead of a path string.
- Writes rows via `ContentResolver.openOutputStream(uri, "wa")` (append) to a lazily
  created `DocumentFile` in the `TEST_RESULTS` folder, replacing `FileWriter`.

### `models/SessionHeader.java`
- Added `fromLogFile(Context, Uri)` overload that reads through
  `ContentResolver.openInputStream()`; existing `File` overload kept.

### `models/TestResult.java`
- Added `fromLogFile(Context, Uri)`; refactored the shared parsing logic into a private
  `fromReader(BufferedReader)` used by both the `Uri` and `File` overloads. Tidied
  resource cleanup.

### `models/TestResultsItem.java`
- `getTimeStamp()` now handles `content://` URI strings, not just file paths, via a new
  `getFileName()` helper that decodes the last path segment. Added bounds guards.

### `models/TestVectorMediaAsset.java`
- Field `File localPath` → `Uri localUri`. New `Uri` constructor; the `File` constructor is
  kept as a backward-compat shim (`Uri.fromFile()`).

---

## Test-vector import / export / download

### `test_vectors/ExportTestVectors.java` (largest single change)
- `ExportCallback.onSuccess(File)` → `onSuccess(Uri)`.
- `exportPlaylist(...)` now takes `Uri playlistUri` and `Uri stagingUri` instead of
  `File`/`String`.
- Export folder tree built with `DocumentFile.createDirectory()`; media/manifest/catalog
  files created with `DocumentFile.createFile(mime, name)`.
- `copyFile` (FileChannel) → `copyUri()` via ContentResolver streams; `calculateChecksum`
  and `writeJsonFile` → `Context`/`Uri` versions via ContentResolver.

### `test_vectors/SetupLocalVectors.java`
- `relocateMediaAssets()` now takes a `Context` and works entirely in `DocumentFile`:
  resolves/creates sub-dirs under `media/`, creates the dest file, copies via
  `copyUri()`, checksum-verifies, deletes on mismatch.
- Added `openInputStream()` (handles `file://` temp sources + `content://`) and
  `getMimeType()`.

### `test_vectors/DownloadTestVectors.java`
- Added `verifyChecksum(Context, Uri, String)` that hashes via
  `ContentResolver.openInputStream()`. Existing `File` overload retained.

### `test_vectors/XspfBuilder.java`
- `<location>` now emitted from `tvAsset.localUri.toString()` instead of building a
  `file://` string from `localPath.getAbsolutePath()`.

---

## UI

### `ui/MainActivity.java`
- **Permission flow replaced:** `hasAllPermissions()` now checks the persisted root URI
  against `getPersistedUriPermissions()` (read+write) instead of
  `Environment.isExternalStorageManager()` / `Settings.System.canWrite()`.
- `requestAllPermissions()` shows an explanatory dialog, then `launchRootFolderPicker()`
  fires `ACTION_OPEN_DOCUMENT_TREE` with an `EXTRA_INITIAL_URI` hint at `primary:vcat-d`.
- New `onActivityResult()` takes the persistable permission, stores the root URI, and
  loads the UI. `loadUI()` calls `StorageManager.init()` and re-prompts if the folder is
  inaccessible.
- Registers the `ACTION_LOG_ROOT` broadcast action on the receiver.
- **System nav-bar fix:** new `hideSystemNavBar()` (sticky-immersive, hides
  `navigationBars()`) called from `loadUI()` and re-applied in `onWindowFocusChanged()` so
  the Android nav bar stops covering VCAT's bottom menu (notably on Samsung 3-button nav).

### `ui/FragmentMain.java`
- Removed the manual folder picker, `safUriToFile()`, and `displayNameFromFileUri()`.
- Playlist list is now `List<DocumentFile>` sourced from
  `StorageManager.getFolder(PLAYLIST).listFiles()` filtered to `.xspf`.
- `deletePlaylist()` calls `DocumentFile.delete()`; browse button hidden (`View.GONE`).
- Resume detection and row/menu wiring updated to pass `DocumentFile` / `content://` URIs.

### `ui/FragmentTestLogs.java`
- `loadTestResults()` reads from `StorageManager.getFolder(TEST_RESULTS).listFiles()`
  instead of `new File(Environment.getExternalStorageDirectory(), LOG_FOLDER)`; items keyed
  by `content://` URI string. Removed verbose `File.list()` diagnostics.

### `ui/FragmentVectorExport.java`
- `selectedPlaylist` is now a `DocumentFile`; playlist scan uses `DocumentFile.listFiles()`.
- `onExportConfirmed(...)` / `onSuccess(...)` updated to the new `Uri`-based signatures.

### `ui/FragmentVectorImport.java`
- `relocateMediaAssets()` called with `Context`; XSPF file located/created via
  `DocumentFile.findFile()` / `createFile("application/xspf+xml", …)`; playlist entries use
  `localUri.toString()`.

### `ui/ExportTestVectorsDialog.java`
- `Listener.onExportConfirmed(...)` first arg `String stagingFolder` → `Uri stagingUri`.
- Picker result stores the tree `Uri` directly (`selectedFolderUri`) instead of converting
  to a path via `StorageManager.getFullPathFromUri()`.

### `ui/PlaylistDialog.java`
- `addNewEntry()` stores `fileUri.toString()` instead of resolving a real path via
  `getRealPathFromUri()`.
- `createFileInSelectedFolder()` rewritten with `DocumentFile.fromTreeUri()` +
  `createFile("application/xspf+xml", …)`; removed the media-scanner broadcast.

### `ui/TestResultsDetailDialog.java`
- Loads the `TestResult` lazily in `onCreateDialog()` via
  `TestResult.fromLogFile(Context, Uri)`.
- Display-name derivation handles `content://` URIs; log-file label uses
  `Uri.getLastPathSegment()`.

### `video/FullScreenPlayerActivity.java`
- `TelemetryLogger` constructed with `(this, fileName)`.
- After parsing the playlist, each clip URI is passed through
  `UriUtils.resolveMediaUri()` so legacy `file://` entries resolve to current SAF URIs.

### `res/layout/activity_fullscreen_player.xml`
- `videoOverlay` sized `wrap_content` (was `match_parent`) and background darkened
  `#66000000` → `#99000000` — fixes the status-overlay text being clipped on vertical video.

---

## Known outstanding item

- `tools/XSPFPlaylistCreator.java`: still contains `getRealPathFromUri()` /
  `getDataColumn()` (MediaStore path resolution). Per §7 of `STORAGE_MIGRATION.md` these
  should be deleted and callers moved to `Uri` directly. The only diff so far is accepting
  `content://` in the `file://` prefix check. **Not yet migrated.**

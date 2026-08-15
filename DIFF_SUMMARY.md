# DIFF_SUMMARY

Running summary of notable change sets on `main`, newest first. Each entry preserves the
description of what shipped so the history is not lost when the working tree moves on.

---

## 2026-08-15 — IVF playlist support + playlist save/refresh ANR & folder fixes (branch `codec_plugin_refactor`)

**Theme:** Make IVF usable end-to-end from the UI, and fix playlist save regressions surfaced
along the way. **Confirmed on-device: AV1-from-IVF plays back end-to-end** (the dav1d renderer
accepts IVF-delivered frames with no extra wiring — the earlier "playback deferred" caveat is
resolved).

- **IVF files are now selectable** when building a playlist. `PlaylistDialog`'s picker used
  `setType("video/*")`, but Android's `MimeTypeMap` has no `.ivf → video/*` mapping so SAF hid
  them. Now `setType("*/*")` + `EXTRA_MIME_TYPES {"video/*", "application/octet-stream"}` (the
  octet-stream fallback surfaces unregistered extensions like `.ivf`).
- **Playlist save no longer ANRs.** The save path (`deleteDocument`/`createFile`/`openOutputStream`/
  `MediaScanner`) and the post-save refresh (`getPlaylistFiles`→`populatePlaylistTable`, where every
  `DocumentFile.getName()` is a SAF IPC — called in the filter loop, the sort comparator, and per
  row) all ran on the UI thread. Both are now off-thread: `PlaylistDialog` does its I/O on a worker
  and only touches the dialog on the UI thread; `FragmentMain` resolves each name **once** into a
  `PlaylistEntry {doc,name,uriStr}` on a worker, then renders the table (no SAF on the UI thread).
- **Playlist save now actually persists to the right folder.** `createFileInSelectedFolder` used
  `DocumentFile.fromTreeUri(folderUri)`, which always resolves to the tree **root**, so playlists
  were written to `<root>/` while the list only scans `<root>/playlist` — saved playlists never
  appeared and piled up as `name (N).xspf`. It now resolves the target via the same
  `StorageManager.getFolder(PLAYLIST)` the list uses. Also fixed save-as creating the file before
  the null-folder check.
- `.ivf → "video/ivf"` added to the extension→MIME maps in `SetupLocalVectors` / `ExportTestVectors`.

---

## 2026-08-14 — IVF extractor (Phase 1) + `parsers` package split (branch `codec_plugin_refactor`)

**Theme:** Roadmap Step 4, host side. Add the IVF container extractor + the plugin SPI for IVF
parsers, and reorganize the `parsers` package. IVF container framing + AV1 sequence-header parsing
are implemented and unit-validated. _(End-to-end IVF playback was expected to need further renderer
work; it turned out to work on-device — see the 2026-08-15 entry.)_

### decoder-plugin-api (`1.0.3 → 1.0.4`)
- `IvfDecoderPlugin` → **`IvfParserExtension`** (pairs with `Mp4ParserExtension`; both extend
  `ContainerParser`). Signature `parseIvfStream(IvfFileHeader, byte[])` →
  `parseHeader(ExtractorInput, int)` — the plugin peeks only the sequence header, no full-frame
  buffering.
- `IvfFileHeader` **removed from the api and moved into the host** (it's container metadata, never
  passed to plugins).
- `DecoderPluginApiTest` updated accordingly.

### Host
- New **`com.roncatech.vcat.parsers.ivf`**: `VcatIvfExtractor` (ExoPlayer `Extractor` — `DKIF`
  sniff, per-frame samples, correct IVF timebase `ptsUs = pts × scale / rate` with
  rate=bytes16-19 / scale=bytes20-23, `SeekMap.Unseekable`) + `IvfFileHeader` (no width/height;
  `timeBaseRate`/`timeBaseScale`). **12 unit tests.**
- `VcatDecoderManager.findIvfPlugin(fourCc)`; `FullScreenPlayerActivity` extractor lambda now
  `{ VcatIvfExtractor, VcatMp4Extractor }` (IVF first — `DKIF` never false-positives on MP4).
- **`parsers` package split:** MP4 parser → `com.roncatech.vcat.parsers.mp4` (12 files); IVF →
  `com.roncatech.vcat.parsers.ivf`.
- **`copyDecoderPluginsToAssets` is now a `Sync` task** — `assets/decoder-plugins/` mirrors
  `decoder-plugins/` exactly and prunes stale `.aar`s, removing build ambiguity.

### Verification
- `decoder-plugin-api` tests + `VcatIvfExtractorTest` (12/12) pass; `:app:assembleDebug` builds;
  on-device `vcat.dav1d` + `vcat.vvdec` register (damo266d removed for now).

### Companion / deferred
- dav1d's `Av1IvfParser` (AV1 OBU sequence-header parse) is committed in `../vcatd-dav1d-plugin`.
  End-to-end IVF playback is deferred pending dav1d renderer IVF-delivery support.

---

## 2026-08-07 — Rename `Mp4DecoderPlugin` → `Mp4ParserExtension`; api `1.0.3` (branch `codec_plugin_refactor`)

**Theme:** Rename the non-deprecated MP4 `stsd` container-parser interface to a clearer name.
Pure rename — no behavior change.

- `decoder-plugin-api`: `Mp4DecoderPlugin` → **`Mp4ParserExtension`** (file renamed). Updated in
  `NonStdDecoderStsdParser` (`extends Mp4ParserExtension`), the `VcatDecoderPlugin`
  `getSupportedContainerParsers()` bridge (`instanceof Mp4ParserExtension`), `ContainerParser`
  javadoc, and the unit test.
- Host: `VcatDecoderManager.getNonStandardDecoders()` and `AtomParsers` `stsd` routing updated to
  `Mp4ParserExtension`.
- api version bumped **`1.0.2 → 1.0.3`** (Maven Local) so plugins can compile against the renamed
  symbol.
- Verified: api unit tests 5/5, host builds. The vvdec plugin is updated to implement
  `Mp4ParserExtension` in its own repo (dep → `1.0.3`). The dav1d plugin is unaffected (it doesn't
  reference the interface; stays on api `1.0.2`).

---

## 2026-08-05 — vvdec on `VcatDecoder`; MP4 `stsd` routing via `Mp4DecoderPlugin`; overlay fix (branch `codec_plugin_refactor`)

**Theme:** Migrate the vvdec plugin (a codec that needs **non-standard MP4 `stsd` parsing**) to
the new SPI — the path dav1d didn't exercise — and route the host's `stsd` lookup through the
non-deprecated `Mp4DecoderPlugin`.

### Host
- `VcatDecoderManager.getNonStandardDecoders()` and `AtomParsers` now key on **`Mp4DecoderPlugin`**
  instead of the deprecated `NonStdDecoderStsdParser`. This matches the migrated vvdec **and** the
  still-legacy `damo266d` (a `Mp4DecoderPlugin` via the deprecated interface's inheritance), so
  VVC-in-MP4 parsing keeps working for both.
- Codec MIME for a non-standard track now comes from the decoder — `((VcatDecoder) parser)
  .getMimeType()` (the parser is always a `VcatDecoder` from the registry) — since the legacy
  `mimeType()` accessor is gone.
- Info-overlay `pluginApiForMime()` now **honors the selected decoder id** (mirrors
  `StrictRenderersFactoryV2`) instead of blindly taking the first plugin for the MIME. Fixes the
  `Plugin API` line showing `0.0.1` for vvdec when `damo266d` (also `video/vvc`) was returned
  first — it now reflects the decoder actually selected/in use.

### Result (on-device)
- `vcat.dav1d` and `vcat.vvdec` report **`0.1.0`** (new `VcatDecoder` SPI); `vcat.damo266d` reports
  **`0.0.1`** (legacy `VcatDecoderPlugin`, intentional backward-compat control). All register and
  load. `NonStdDecoderStsdParser` is no longer referenced by any first-party code (kept in the api
  only for legacy plugins like damo266d).

### Companion repo
- vvdec's migration to `VcatDecoder` + `Mp4DecoderPlugin` (+ its `DIFF_SUMMARY.md`) is committed in
  `../vcatd-vvdec-plugin`.

---

## 2026-08-04 — Host + dav1d on `VcatDecoder`; plugin-API-version overlay (branch `codec_plugin_refactor`)

**Theme:** Put a decoder onto the new `VcatDecoder` SPI end-to-end (dav1d), retype the host to
the base SPI so it can load such decoders, and surface each decoder's SPI version in the info
overlay so new vs. legacy decoders are visible at runtime.

### Host retyped to the `VcatDecoder` base type
- `VcatDecoderManager`, `DecoderPluginLoader` (the load-time cast), `StrictRenderersFactoryV2`,
  and `VideoDecoderEnumerator` changed `VcatDecoderPlugin` → `VcatDecoder`. Since
  `VcatDecoderPlugin extends VcatDecoder`, legacy plugins (vvdec, damo266d) still load unchanged,
  and decoders on the new SPI (dav1d) now load too. `getNonStandardDecoders()` still uses
  `NonStdDecoderStsdParser` for MP4 `stsd` routing.

### Plugin-API version reporting
- `decoder-plugin-api`: added `VcatDecoder.getPluginApiVersion()` (default `"0.1.0"`);
  `VcatDecoderPlugin` overrides it to `"0.0.1"`. Resolves at runtime from the host's api module
  (plugins share the host's interfaces via the parent classloader), so no api republish or plugin
  rebuild is required for the value to take effect.
- `FullScreenPlayerActivity` info overlay: new **`Plugin API`** line under `Decoder`, showing the
  API version of the plugin registered for the clip's MIME (`n/a (hardware)` when none). Lets you
  see new-SPI decoders (`0.1.0`, e.g. dav1d) vs. legacy ones (`0.0.1`, e.g. vvdec/damo266d).

### Verification
- Host builds; on-device all three plugins register and load: `vcat.dav1d` (now via `VcatDecoder`,
  reports `0.1.0`), `vcat.vvdec` + `vcat.damo266d` (legacy `VcatDecoderPlugin`, report `0.0.1`).
  No `ClassCast`/`UnsatisfiedLinkError`.

### Companion repo
- The dav1d plugin's migration to `VcatDecoder` (+ its own `CHANGE_SUMMARY.md`) is committed in
  `../vcatd-dav1d-plugin`.

---

## 2026-08-04 — decoder-plugin-api refactor: `VcatDecoder` SPI + container parsers (branch `codec_plugin_refactor`)

**Theme:** Introduce a container-agnostic decoder SPI (roadmap Step 3), decoupling codec from
delivery container (MP4/IVF). Existing plugins keep compiling and running unchanged. API-only —
no host loader/registry wiring yet (that is a later step). `decoder-plugin-api` bumped
`1.0.1 → 1.0.2` (published to Maven Local; not Central).

### New interfaces (`decoder-plugin-api`)
- **`VcatDecoder`** — new single-codec SPI superseding `VcatDecoderPlugin`. Declares
  `getSupportedContainerParsers()` (the containers a decoder can be driven from); no
  `getSupportedProfiles()` (deferred).
- **`ContainerParser`** — common supertype for the parser list; one method `getContainerMimeType()`
  (defaulted by the sub-interfaces). Codec MIME is the decoder's `getMimeType()`, not duplicated.
- **`Mp4DecoderPlugin`** — non-deprecated MP4 `stsd` parser (`sampleEntry4ccCode`,
  `codecConfiguration4ccCode`, `parseStsd`); defaults `getContainerMimeType()="video/mp4"`.
- **`IvfDecoderPlugin`** — IVF parser (`ivfFourCc`, `parseIvfStream`); defaults
  `getContainerMimeType()="video/ivf"`. Color is parsed from the bitstream sequence header;
  "unspecified" → left `Format.NO_VALUE` (no BT.709 mandate).
- **`IvfFileHeader`** — parses the 32-byte IVF header (container metadata only; never passed to
  the decoder).

### Deprecations / bridges (backward-compatible)
- `VcatDecoderPlugin` → `@Deprecated`, now `extends VcatDecoder`, with a default
  `getSupportedContainerParsers()` that exposes a legacy plugin (if it is a `Mp4DecoderPlugin`)
  as a non-deprecated `ContainerParser`.
- `NonStdDecoderStsdParser` → `@Deprecated`, now `extends Mp4DecoderPlugin` (keeps only the legacy
  `mimeType()` accessor). Existing plugins implementing it need **zero** new methods —
  `getContainerMimeType()` is inherited as a default.
- `VideoConfiguration` — unchanged functionally (no BT.709 constants; Builder color defaults stay
  `Format.NO_VALUE`); header normalized to `vcat-d`.

### Tests
- New `DecoderPluginApiTest` (5 tests, all passing): legacy plugin needs no new methods; the
  legacy bridge returns a non-deprecated `Mp4DecoderPlugin`; a new `VcatDecoder + IvfDecoderPlugin`
  compiles and touches no deprecated type; `IvfFileHeader.parse` extracts fields correctly. Added
  JUnit test deps to the module.

### Verification
- Module unit tests pass; host `:app:assembleDebug` compiles against the modified module (via
  `project(':decoder-plugin-api')`). On-device: all three plugins (`vcat.dav1d`, `vcat.damo266d`,
  `vcat.vvdec`) load/register, no `UnsatisfiedLinkError`. The dav1d plugin, rebuilt against api
  `1.0.2`, now compiles with a deprecation notice on `VcatDecoderPlugin` (still functional).

### Deferred (not in this commit)
- Migrate plugins + host loader/registry from `VcatDecoderPlugin` to `VcatDecoder`, and add the
  IVF `Extractor` (Step 4). Until then the new SPI is inert in the host.

---

## 2026-07-31 — Codec plugin refactor: externalize dav1d, migrate MP4 parser into host, drop libvcat (branch `codec_plugin_refactor`)

**Theme:** Begin dismantling the `libvcat` binary dependency. Make all decoders external `.aar`
plugins and move container parsing + the decoder registry into the vcat-d host, so new vcat-d no
longer depends on `libvcat` at all. (Roadmap Steps 1–2; see `analysis/devplan.md`.)

**Scope in this repo (host):** this commit is the host-side change. The companion decoder plugin
lives in the sibling repo `../vcatd-dav1d-plugin` (committed separately); `../libvcat` is being
emptied locally and is never republished — legacy vcat-d keeps pulling the frozen `libvcat:0.0.3.11`
from artifactory.

### Step 1 — dav1d externalized as a plugin
- dav1d is no longer bundled/registered inside libvcat. It now ships as an external decoder
  `.aar` (`vcatd-dav1d-plugin`, package `com.roncatech.vcatd_dav1d_decoder`, native lib
  `libvcat_dav1d_jni.so`, MIME `video/av01`, plugin id `vcat.dav1d`), dropped into
  `decoder-plugins/` and bundled by the existing `copyDecoderPluginsToAssets` task. No host code
  change was needed for it to load/register.

### Step 2 — MP4 parser + decoder registry migrated into the host
- **New package `com.roncatech.vcat.parsers`** — the MP4 parser (12 classes: `VcatMp4Extractor`,
  `AtomParsers`, `Atom`, `Sniffer`, `TrackSampleTable`, `FragmentedMp4Extractor`, `MetadataUtil`,
  `PsshAtomUtil`, `SefReader`, `TrackFragment`, `DefaultSampleValues`, `FixedSampleSizeRechunker`)
  migrated from `libvcat`'s `com.roncatech.libvcat.extractor.mp4`.
- **New package `com.roncatech.vcat.decoder_plugin`** — `VcatDecoderManager` migrated from
  `com.roncatech.libvcat.decoder`, with the `InternalDecoderLoader.loadOnce()` call removed
  (`InternalDecoderLoader` not migrated — there are no built-in decoders anymore; all decoders are
  external plugins).
- **Host imports repointed** in `FullScreenPlayerActivity` (`parsers.VcatMp4Extractor`),
  `DecoderPluginLoader`, `StrictRenderersFactoryV2`, `VideoDecoderEnumerator`
  (`decoder_plugin.VcatDecoderManager`).
- **`libvcat` dependency removed** from `app/build.gradle`. The migrated code compiles against the
  host's existing deps (ExoPlayer 2.19.1 full, Guava, checker-compat-qual) — no new dependency.

### Result / verification
- Host builds with **no `libvcat` dependency**; `libvcat` is not on the runtime classpath and no
  `com.roncatech.libvcat.*` library class appears in the installed APK's dex.
- On-device: `DecoderPluginLoader` registers `vcat.dav1d` exactly once (no more double
  registration from libvcat's internal dav1d), native `.so` loads cleanly, and AV1 playback works.
- Note: `decoder-plugins/*.aar` are git-ignored, so the plugin binary is not part of this commit.

### Docs
- `README.md`: removed all `libvcat`/`libvcatd` references; the Components table now lists the
  external plugins (`vcatd-dav1d-plugin`, `vcatd-vvdec-plugin`) instead of libvcatd, AV1 is
  described as provided by the dav1d plugin, and the build section ("Building vcat-d with decoder
  plugins") covers building + dropping in the dav1d (prebuilt) and vvdec (from-source) `.aar`s.

### Companion repo (separate git repo, not in this commit)
- `../vcatd-dav1d-plugin` — the external AV1 plugin: repackaged out of the dead
  `com.roncatech.libvcat.*` namespace into `com.roncatech.vcatd_dav1d_decoder` (JNI symbols
  re-mangled to match), plus repo setup (dav1d `LICENSE` + BSD-2-Clause notice, `.gitignore`,
  README, `vcat-d` license headers on all files).

### Deferred follow-up
- Play Protect / OEM scanners flag the plugin loader's runtime dynamic-code loading
  (`DexClassLoader` + `System.load()` from the app data dir). Planned mitigation (not yet done):
  load plugin dex via `InMemoryDexClassLoader` and ship plugin `.so`s in the host APK's
  `jniLibs` so nothing executes from a writable data-dir path.

---

## 2026-07-09 — Fix `cpu.usage.total` (compute from `/proc/stat`), version `0.3.3`

**Theme:** `cpu.usage.total` always logged ~0. Replace the per-app CPU-vs-wallclock metric
with a system-wide utilization computed from `/proc/stat`, identical to vcat-web's
`get_cpu_stats()`.

### `telemetry/TelemetryLogger.java`
- Removed the old `CpuStats` singleton (based on `Process.getElapsedCpuTime()` with a baseline
  primed at construction — a root cause of the ~0 readings).
- Added `CpuUsageSampler`: reads the aggregate `cpu` line of `/proc/stat`, retains the
  previous read as an instance field, and computes usage as a delta across one telemetry
  interval:
  - `deltaTotal = sum(all columns now) − sum(all columns prev)`
  - `deltaIdle = idle_now − idle_prev` (idle = the 4th value column; **iowait excluded**)
  - `usage = 100 * (1 − deltaIdle / deltaTotal)`, rounded to 1 decimal, guarded on
    `deltaTotal > 0`.
  - 0–100 as a fraction of total capacity across all cores (the `cpu` line already sums the
    cores — no division by core count). First sample emits 0.0.
- Sampled once per row in `logTelemetryRow()`; `cpu.usage.total` now formatted `%.1f`.

**Per-core intentionally omitted:** an unprivileged app can't reliably read the per-core
`cpuN` lines without root, so only the aggregate is reported.

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

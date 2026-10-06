# DIFF_SUMMARY

Running summary of notable change sets on `main`, newest first. Each entry preserves the
description of what shipped so the history is not lost when the working tree moves on.

---

## 2026-10-05 — Three durations, build type in the header, a completion estimate, and native-rate playback everywhere

**Theme:** Making a run self-describing. A telemetry file now states how far into the test a row
is, how far into the current playlist pass and file, and which build produced it — all of which
were needed to interpret a set of battery results that turned out not to be comparable.

### Why: battery results that could not be compared

Three CPH2625 battery runs looked like clear outcomes and were not:

| decoder | battery | loops/hr | realtime | reading |
|---|---|---|---|---|
| vvdec 3.0.0 | 8.00 h | 3.8 | 5.95 | **0.64×** — slow motion |
| vvdec 3.2.0 | 6.57 h | 5.8 | 5.95 | 0.97× — native rate |
| dav2d `13d21d5` | 9.20 h | 266 | 277 | 0.96× |
| dav2d `ee033a4` st-arm | 12.18 h | 268 | 277 | 0.97× |

vvdec 3.0.0 appeared to beat 3.2.0 on battery life. It was playing the 604.8 s / 29.97 fps VVC
stream at 64% of realtime and reporting `frames_dropped = 0`, so the older build was being
rewarded for not doing the job. The dav2d pair, both at native rate, is the only directly
comparable result in the set: **st-arm lasts 32% longer on identical content at identical
throughput.**

The lesson is that battery hours only means something when the workload is fixed, and the check
for that is `loops/hour × clip_duration ÷ 3600`. Nothing in the telemetry stated the clip's
nominal duration, so this had to be reconstructed after the fact — and getting it wrong once
produced a confidently wrong conclusion.

### Three durations

`test.duration_ms` previously restarted on every playlist pass, which lost the only answer to
"how far into the run is this row". Now:

| column | resets |
|---|---|
| `test.duration_ms` | **never** — measured from test start |
| `test.playlist.duration_ms` | every playlist pass |
| `test.file_duration_ms` | every file |

With a single-file playlist `advanceToNextClip()` wraps on every file, so an iteration is a file
and the latter two are equal — expected, not a bug. The cumulative one is measured from
`curTestDetails.getStartTimeAsEpoch()`, the same source the original `test.duration` used, so it
keeps Resume's semantics of continuing a prior run's elapsed clock.

### `vcat_build_type` in the session header

Emitted immediately after `vcat_version`, from `BuildConfig.DEBUG`, so it reads `debug` or
`release` with no manual bookkeeping.

Needed because the two variants are not performance-equivalent and `vcat_version` is the same
string in both. Release sets `minifyEnabled false`, so the bytecode is identical and the only
difference is the debug variant's implicit `debuggable true` — which makes ART compile with
`--debuggable` and restrict optimisations such as inlining. That slows host Java, and the whole
ExoPlayer pipeline is host Java: the renderer's earliness check and drop decision, the sample
queues, the extractors, the media clock, `doSomeWork` every 10 ms. Only decode is native and
unaffected. The effect is probably small, but over 9–12 hours at 30 fps it is not obviously
negligible, and until now a log could not say which variant produced it.

`SessionInfo` has a hand-written `TYPE_ADAPTER`, so the field is emitted explicitly rather than
reflectively; the read path tolerates its absence so older logs still parse, where `null` means
"written before the field existed".

### Native-rate playback across all decoders (plugin repos)

Not a change in this repository, but it determines whether anything measured here is meaningful.
`shouldForceRenderOutputBuffer` returning `earlyUs <= 0` made every late frame render and left
the drop logic unreachable, so a decoder that could not keep up stretched the timeline instead of
dropping frames. That was fixed in the dav2d plugins earlier and is now fixed in **dav1d and
vvdec** as well, so all five decoders hold the content's frame rate and shed frames when they
fall behind — what a player does, and what makes `frames_dropped` meaningful.

`decoder-plugins/` now carries five AARs: dav1d, dav2d (`13d21d5`, unpatched upstream),
dav2d-st-arm (`ee033a4` + 2 patches), vvdec 3.2.0, and a new **vvdec 3.0.0** plugin
(`vcat.vvdec.30`, package `…libvcat.vvdec30`, soname `libvcat_vvdec30_jni.so`) forked so the two
vvdec versions can be installed together and the frame-rate fix sanity-checked directly.

Plugin AARs are always release-built. A debug variant would compile the native decoder at `-O0`
via `buildCMakeDebug`, and `dist`'s `classes.dex` injection — which the host's `DexClassLoader`
requires — only exists on the release path. The host's build type does not constrain the
plugin's.

### Completion estimate on the overlay

The run-duration line now reads `Run Dur: hh:mm:ss (est hh:mm)` — `Duration` was trimmed to `Dur`
to make room. A run can last twelve hours, and until now the overlay said how long it had been
going but nothing about when it would end, so checking on a device meant doing the arithmetic.

The estimate is per run mode: `N/A` for a single pass, where completion depends on playlist length
rather than elapsed time; the remaining wall clock for a timed run; and for a battery run, the
drain rate extrapolated to the configured stop level. The battery figure uses a whole-run average
rather than a sliding window — level is only reported to the nearest percent, so over a nine-hour
discharge one percent lasts about six minutes and any short window is mostly quantisation noise.
Discharge under a fixed playback workload is close to linear, which is what makes the average
usable. It reads `--:--` until the level has moved a whole percent and there is a rate to
extrapolate from.

### Known gaps

**Nominal duration is not recorded.** The telemetry still does not state the clip's duration or
frame rate, so the native-rate check has to be computed from external knowledge of the content.

**Drop accounting is inconsistent at file wraps.** On both vvdec runs, summed
`video.dropped_to_keyframe` exceeds summed `video.frames_dropped` (24,260 vs 22,577 on
3.2.0; 172,431 vs 158,588 on 3.0.0). That is impossible on its face: ExoPlayer folds the
keyframe drops into the same counter (`DecoderVideoRenderer:535-538`), so the former is a
strict subset of the latter.

The anomaly is **confined to the first telemetry row after a file wrap** — 2 rows of 177 on
3.2.0, 5 of 94 on 3.0.0. Those rows read `frames_dropped = 0` alongside spikes of 6,047 and
1,223 keyframe-dropped frames at `file_duration_ms` ≈ 1.6 s, which is not plausible as genuine
in-file catch-up less than two seconds into a clip. Excluding wrap rows the invariant holds
everywhere. The dav2d runs are unaffected entirely: they never drop to a keyframe at all
(`dropped_to_keyframe = 0` on every row), so their `frames_dropped` figures stand as measured.

**The mechanism is not yet established**, and three plausible ones have been ruled out:

- *`onStarted()` zeroing `droppedFrames` without flushing* — `onStopped()` calls
  `maybeNotifyDroppedFrames()` first (`DecoderVideoRenderer:311`), so a stop/start cycle
  loses nothing.
- *`sampleDrops()` delta arithmetic* — correct; it accumulates `delta` and advances
  `lastDroppedToKeyframe` to `total` on every tick.
- *Counter replacement at re-enable* — handled; `onVideoEnabled` repoints `videoCounters` and
  resets `lastDroppedToKeyframe` to 0, and the guard in `sampleDrops()` catches a counter that
  restarts lower.

One real defect was found while ruling those out, though it biases the other way:
`onVideoDisabled` nulls `videoCounters` without taking a final sample, so whatever the old
counter accrued between the last 2 s tick and teardown is never read. That *loses*
keyframe drops, so it cannot explain a count that is too high.

**Impact on results is bounded and changes no conclusion.** The wrap rows hold 7,270 of
3.2.0's 24,260 keyframe drops and 39,766 of 3.0.0's 172,431. Taken as a worst case — assuming
every wrap-row frame is real and absent from `frames_dropped` — 3.2.0's drop rate moves from
3.3% to 4.3% and 3.0.0's from 48.6% to 54.6%. Neither disturbs the ranking, and the
native-rate and battery findings do not rest on drop counts at all: they come from
`file_duration_ms` against nominal clip duration, and from battery hours.

---

## 2026-10-01 — Remove the silent audio clock; force frame drops; telemetry for looping runs (branch `initial_av2`)

**Theme:** A benchmark has to measure the decoder, not its own scaffolding, and it has to fail
visibly rather than quietly change what it is measuring. Three of the changes here came from
discovering that VCAT was doing neither.

### The silent audio clock cost 38% of battery life

`SilentAudioTrack` existed so an audio renderer would supply the media clock, because
`DefaultMediaClock` adopts the clock of an enabled renderer and a video-only input enables none.
Two matched CPH2625 runs, identical but for `RunConfig.audioClock`, discharged 100%→14% in
**17.72 h** without it and **11.02 h** with it.

The cost is not decode work. With audio on the big cluster averaged *lower* (1605 vs 1784 MHz),
spent less time at its 2500 MHz peak (11.7% vs 26.6%), and `cpu.usage.total` was lower (12.63% vs
14.09%) — yet the battery ran 1.3 °C warmer. The visible fingerprint is the big cluster never
dropping below 1200 MHz across 30,224 samples, against a 400 MHz floor without audio: a periodic
callback holding cores out of deep idle. For a tool whose purpose is measuring decoder battery
cost, scaffolding consuming 38% of the battery invalidates the measurement.

Removed: `parsers/SilentAudioTrack.java`, the `injectSilentAudio` flag in both extractors,
`RunConfig.audioClock` with its equals/hashCode/comparator/copy/default sites, the conditions-tab
checkbox and its layout entry, and the flag plumbing in `FullScreenPlayerActivity`.
`StrictRenderersFactoryV2.buildAudioRenderers` now builds **nothing** and deliberately does not
call `super`, so no `AudioTrack` can be opened even by a stream carrying real audio. The MP4
extractor still declines to publish the container's audio tracks, now unconditionally — audio is
**suppressed**, never **generated**. Six silent-audio tests are replaced by one pinning the new
invariant.

With no audio renderer, `DefaultMediaClock` falls back to `StandaloneMediaClock`, which advances
at wall-clock rate. Confirmed on device: the player reached `STATE_ENDED` at `pos=17026` ms on a
13,000 ms clip.

### Frames now actually drop

The clock being right was not enough. Every software decoder plugin overrode
`shouldForceRenderOutputBuffer` to `earlyUs <= 0`, and
`DecoderVideoRenderer.processOutputBuffer` runs the force-render check *before* the drop checks —
so every frame that was due or late was rendered and the `shouldDropOutputBuffer` override below
it was unreachable. **No frame could ever be dropped.**

Measured at 1080p AV2, threads=1: four loops each took **17.6 s** of wall time for **13.0 s** of
media (0.74×) with `frames_dropped = 0`. After removing the override: **13.5–13.7 s** per loop
with drops reported.

So `video.frames_dropped` had been structurally zero for every software decoder, which is why max
drops was 0 or 1 across all 20 historical logs. Every software-decoder run to date measured *time
to decode N frames*, not realtime playback with drops; relative comparisons hold, the absolute
framing does not. Fixed in the dav2d plugins; **the identical override is still present in the
dav1d and vvdec plugins**, so AV1 and VVC results are not yet comparable with AV2 ones.

### Telemetry: looping runs are now readable

- `test.duration` → **`test.duration_ms`**, measured from the start of the current playlist pass
  so it restarts each loop rather than growing for the length of the run
- **`test.file_duration_ms`** — position within the current file
- **`test.total_iterations`** — playlist passes completed

With a single-file playlist `advanceToNextClip()` wraps on every file, so an iteration is a file
and the two durations track each other. Nothing read the old column name —
`TestResultsDetailDialog` derives duration from timestamps — so the rename breaks no reader.

- **`video.dropped_to_keyframe`** — frames discarded from the source to reach a keyframe, from
  `DecoderCounters.droppedInputBufferCount`, which ExoPlayer increments *only* there. Frames, not
  events: `droppedToKeyframeCount` reads a flat 1 however large the block. Measured 70/64/63 on
  successive playthroughs, matching one 65-frame GOP and `decoded ≈ 320` of 390. These frames are
  never decoded, so a non-zero value marks an interval where the run did **less work** than the
  clip demanded and its throughput is not comparable with a clean one.

Both drop columns are per-interval, so a stall reads as a spike rather than a step.

### Telemetry: three collection bugs

- **Drop counters are sampled on their own 2 s timer** into local accumulators, which the
  telemetry row reports and clears. The cadences must differ: rows ramp 30 s → 5 min because
  polling battery, thermal, eight CPU frequencies and memory every 2 s for a 17 h run would make
  the log unusable, but `startClipWithFreshPlayer()` builds a **new ExoPlayer** per clip, so a
  fresh `DecoderCounters` starts at zero and anything not read before the next clip began was
  lost. With 13 s clips and minutes between rows, most of it was.
- **`stopTestAndCleanup()` wrote no row.** Of five paths into teardown only `STATE_ENDED` logged
  first, so the stop button, the ADB stop command and both error dialogs discarded everything
  since the last row — including, on an error, the state at failure. It now flushes first, guarded
  against duplicating the row `STATE_ENDED` just wrote.
- **The telemetry timer leaked.** `startTelemetryTimer()` posted without `removeCallbacks`, and
  `onPlaybackStarted()` calls it at every clip start, so each clip left another self-rescheduling
  timer behind: rows got denser as a run progressed and a looping playlist accumulated one timer
  per playthrough. Row cadence in every previous log is therefore not what it appears.

### Frame-number overlay, host-side

New "Show frame number (diagnostic)" checkbox under Refresh Rate, backed by
`RunConfig.showFrameNumber`. Driven by `VideoFrameMetadataListener`, which fires immediately
before each frame is released to the surface, so the number names the frame being presented. The
index comes from the presentation timestamp rather than a render count, which would stay
contiguous across dropped frames and misname every frame after the first drop.

This replaces an equivalent overlay burned into the luma plane inside the dav2d plugins: that
needed a copy in each of four plugins plus a SharedPreferences contract duplicated as untyped
strings, because an app cannot set a `debug.*` system property. The host version also covers
hardware decoders, which an in-plugin overlay could not. What is lost is that the burned-in
number was composited with the image and so could not disagree with a screen capture.

### AV2 decoder selection

- `VideoDecoderEnumerator` — `AV2("video/av02")` added to the `MimeType` enum. One line, and the
  one that unblocks everything: `FragmentTestConditions` builds its spinners from
  `MimeType.values()`, so AV2 gets a row listing every registered `video/av02` plugin and both
  dav2d builds appear side by side, selectable without a rebuild.
- `VcatDecoderManager.registerDecoder` uses `putIfAbsent` and logs at **error** level on a
  duplicate id, naming both the kept and discarded build. With two builds of one decoder
  installed, a silently dropped duplicate was the failure that produces a clean-looking wrong
  number: the discarded plugin is unreachable but the run still reports the id that was asked for.
- `FragmentTestConditions` — stale saved-id fallback. `getPosition()` returns −1 for an id no
  longer registered and that went straight to `setSelection(-1, false)`; the config then kept a
  dead id, `StrictRenderersFactoryV2` found nothing, added **no renderer**, and AV2 silently
  stopped decoding.

### Logs tab

Rows show the file name with `.csv` stripped, matching vcat-ai, plus a right-aligned size column
(`x.x MB`, rounded up so a non-empty log never reads `0.0`). Per-item `PopupMenu` with
Details/Delete and confirmation; edit mode with checkboxes, Select All/Unselect All, Cancel, a
trashcan dimmed until something is selected, and batch delete reporting partial success.
Selection is keyed by document URI rather than display name: two logs can render the same
timestamp string, and the URI is what a delete needs.

### Also in this change set

- **Display refresh control** — new `tools/DisplayInfo.java`, `RunConfig.displayRefreshHz`,
  `DEFAULT_REFRESH_HZ`, `DeviceInfo.displayRefresh`, the conditions dropdown. Adds
  `display_refresh` to the telemetry header.
- **Run environment capture** — new `tools/RunEnvironment.java` and `SessionHeader.run_environment`
  (airplane mode, charging, power save, brightness, wifi/cellular). This is what made it possible
  to confirm which battery runs were actually on battery — two earlier runs were not.
- Player Options spacing tightened to match Run Options, which carries no inter-control margins.

**Telemetry schema:** `test_conditions` is serialised reflectively from `RunConfig`, so removing
`audioClock` and adding `showFrameNumber` change the header with no version bump. Deliberate while
on this branch; `headerVersion` is the app `versionCode`, not a schema version, so there is no
independent number to bump.

Verified: builds clean, 35 unit tests pass, installed and exercised on CPH2629 — forced frame
dropping, the keyframe-frame counts, and the new duration/iteration columns all confirmed against
live runs. The final-row flush and the timer-leak fix are not separately exercised.

---

## 2026-08-30 — Remove error-resilient decode; decoder-plugin-api back to `1.0.4`

**Theme:** Error resilience was scaffolding for an immature dav2d. dav2d now decodes AV2 cleanly on
both test devices, so the option is removed: for a benchmarking tool, failing on a decode error is
the correct behavior. A run that dies is a result; a run that silently skips frames and still emits
telemetry is a bad number that looks like a good one.

- `RunConfig.errorResilient` removed, with its `equals` / `hashCode` / comparator / copy-ctor /
  default sites, the conditions-tab checkbox (`FragmentTestConditions`,
  `fragment_conditions.xml`), and the flag argument in `StrictRenderersFactoryV2`.
- **`decoder-plugin-api` `1.1.0` → `1.0.4`.** The 6-arg `VcatDecoder.createVideoRenderer` overload
  is gone, and since that overload plus the version bump were the *entirety* of 1.1.0, the version
  reverts rather than advancing. Safe because 1.1.0 never left Maven Local — every build here logs
  `GitHubPackages repo skipped (missing creds)` — and VCAT is the only consumer of this API.
- The dav2d plugin drops its implementation of the overload and its four error-resilient branches,
  which revert to failing the run; it moves back to api `1.0.4`, matching dav1d.

**Side effect worth noting:** this closes the plugin inconsistency recorded in the previous entry —
`errorResilient` being inert for AV1 — by deleting the feature rather than porting it to dav1d. The
remaining dav1d/dav2d asymmetry is the segment-splice flush, which is still open (and is a dav2d
0.0.x workaround whose relevance to mature dav1d is unproven).

Verified: host compiles, unit tests pass, dav2d plugin builds and its 8 tests pass against api
1.0.4. Not installed on-device — no device was connected at the time.

---

## 2026-08-30 — Test Duration shows hours; AV2 freeze root-caused to dav2d (host exonerated)

**Theme:** A small results-UI change, plus the host-side conclusion of an AV2 playback
investigation that turned out not to be a host bug.

### `ui/TestResultsDetailDialog.java`

- Test Duration now reads `18171s (5.05h)` instead of `18171 secs`. Seconds stay the primary
  figure — they are what the telemetry records — with hours alongside so a long run is legible at
  a glance. `Locale.US` so the decimal separator matches the `%.2f` usage already in the file.

### AV2-from-IVF freeze — not a host defect

AV2 playback showed the first frame and then froze with the UI still responsive. The host was the
prime suspect (IVF timebase math, `VcatIvfExtractor`, the sample pipeline) and was cleared:

- `VcatIvfExtractor.toMicros` is correct: `raw × 1e6 × scale / rate`, reading rate at bytes 16–19
  and scale at 20–23.
- The test file's header was parsed by hand to confirm the inputs — `DKIF`/`AV02`, 1920×1080 (and
  a 640×360 sibling), rate 30 / scale 1, 390 frames, first frame pts 0 — so `ptsUs = N × 33333`.
- On-device tracing showed the host queueing exactly those values to the decoder
  (`queued_pts=1000000000000, …033333, …066666`; the 1e12 offset is ExoPlayer's normal sample
  offset).

**The fault is in dav2d**, which has timestamp propagation compiled out (`#if 0` around both
`dav2d_data_props_copy()` calls in its `src/picture.c`), so every output picture carries
`INT64_MIN`. ExoPlayer's earliness check `bufferPresentationTimeUs - positionUs` then *overflows*
into a large positive value, so frames are held rather than dropped — hence
`video.frames_dropped = 0`, `cpu.usage.total` at 3–9%, and a frozen first frame. Worked around in
the dav2d plugin by carrying the PTS there; see that repo's DIFF_SUMMARY.

Two host-side notes from the investigation:

- `video.frames_dropped = 0` alongside frozen video is not contradictory — it distinguishes "held
  by the renderer" from "dropped as late", which is what made the diagnosis tractable. Worth
  remembering when reading telemetry from a stalled run.
- The AV2 path had never actually worked end-to-end. The dav2d plugin's "AV1-from-IVF confirmed
  operational" entry was copied text from the dav1d plugin, so there was no real baseline to
  regress from.

### Decoder plugin consistency

The dav1d and dav2d plugins were audited file-by-file (identifiers normalized) since the decoders
are architecturally identical outside codec tools. Two inconsistencies found sit in the **dav1d**
plugin and affect the host's behavior, so they are recorded here too:

1. **`RunConfig.errorResilient` is inert for AV1.** The host threads the flag through
   `StrictRenderersFactoryV2` to the api-1.1.0 6-arg `VcatDecoder.createVideoRenderer`, and dav2d
   honors it, but the dav1d plugin never implements that overload — so the conditions-tab checkbox
   silently affects AV2 only.
2. **dav1d lacks dav2d's segment-splice flush.** dav2d detects a subsequent sequence header and
   flushes so frame-parallel workers quiesce, without which feeding the new-sequence keyframe races
   the decoder's reference-buffer reset and crashes at `threads > 1`. Playlists splice
   independently-encoded clips, so AV1 playlists are exposed to the same race.

Neither is fixed yet.

---

## 2026-08-30 — Playlist UUID identity + telemetry `execution_id`; Resume; CPU/thermal telemetry; error-resilient decode (branch `initial_av2`)

**Theme:** Remove the ambiguity of identifying a playlist by its file name, and identify each
telemetry file. Six independent change sets that accumulated in one working tree.

### Playlist identity (`<identifier>`) and `execution_id`

The file name was doing the job of an identity: it is not unique across folders, it changes on
rename, and a telemetry log recording only `"playlist": "av2-1080p30.xspf"` could not say which
playlist produced it.

- **Playlists carry a UUID.** New `tools/PlaylistIdentity` owns the id format: the XSPF-standard
  playlist-scope `<identifier>`, whose value the spec requires to be a URI — hence
  `<identifier>urn:uuid:3f2504e0-…</identifier>`, written as the first child of `<playlist>`,
  above `<trackList>`. `canonicalize()` rejects values `UUID.fromString` accepts but that would
  not compare equal later (`1-2-3-4-5`); `canonicalizeOrNew()` is used on every write path so VCAT
  can never emit a playlist its own reader would reject.
- **All writers stamp it:** both `XSPFPlaylistCreator` write paths (new `writePlaylistFile`
  overload taking an explicit id) and `XspfBuilder`. `XspfParser.parseIdentifier` reads it,
  stopping at `<trackList>` — XSPF allows a per-`<track>` `<identifier>` too, and a track's id is
  not the playlist's.
- **Identity is preserved, not re-minted.** Editing a playlist re-reads its id before the
  delete/recreate save (`PlaylistDialog`), so telemetry from before and after an edit still refers
  to one playlist. An imported playlist takes its id from the manifest uuid, and
  `ExportTestVectors` now writes the source playlist's id into the exported manifest header (new
  4-arg `TestVectorManifests.Header` ctor) — so a playlist exported from one device and imported on
  another keeps one identity, and telemetry from both devices can be grouped by playlist.
- **Telemetry header.** `execution_id` (uuid) added immediately after `header_version` at the same
  scope, minted in `TelemetryLogger.writeHeaderRows`. That placement is what makes it per-file:
  Resume appends to an existing CSV and skips header writing entirely, so resumed rows stay under
  the execution_id the file was created with. `session_info.playlist` changed from a bare string to
  `{"id", "name"}`.
- **Backward compatible reads.** `SessionInfo` accepts both playlist shapes (legacy string → nil
  id, name preserved) and `SessionHeader` defaults a missing `execution_id` to the nil uuid, so
  pre-existing logs still open in the Results tab. The nil UUID
  (`00000000-0000-0000-0000-000000000000`) is used rather than a non-UUID placeholder so consumers
  can parse it instead of special-casing it.
- **Conformance is verified on every launch.** New `tools/PlaylistConformance` partitions the
  playlist folder by whether each `.xspf` carries a usable id. Non-conformant playlists are
  **omitted from the UI entirely** — enforced at the listing level in both `FragmentMain` and
  `FragmentVectorExport`, not by the dialog, so declining the prompt cannot leave a hidden playlist
  reachable. `MainActivity` prompts with the offending names and offers one-tap repair
  (`XSPFPlaylistCreator.stampIdentifier`, in-place, `"wt"` to truncate). Repair is per-file, so a
  partial run is safe to re-attempt next launch. The HTTP/command surface has no playlist-start
  path, so there is no bypass there.
- **Resume now matches on identity**, in `FragmentMain` and `FullScreenPlayerActivity`, replacing a
  display-name comparison that could neither survive a rename nor distinguish same-named playlists
  in different folders. `ResumeInfo` gains `playlistId`; `TestStatus` gains `playlist_id` (also
  exposed in the status JSON) and `startTest` takes the id.
- **Tests:** `PlaylistIdentityTest` (URN round-trip, case/whitespace normalization, rejection of
  malformed and non-`urn:uuid:` values, write-path guarantees) and `SessionInfoPlaylistTest`
  (object form, round-trip, legacy bare-string log, missing fields).

### Resume

- `TestStatus.startTest(uri, id, startEpochMs)` takes an explicit effective start —
  `originalStart + stoppedGap` — so elapsed/run-limit time continues from where the prior run left
  off with the stopped gap excluded.
- `TelemetryLogger` takes a `timestampOffsetMs` and subtracts it from logged timestamps, so
  1h run + 5h stopped + 1h run reads as 2h of test time, not 7h. Duration is measured from the raw
  clock against the already-shifted effective start, so both reduce to
  (logged timestamp − original start) — the offset must not be subtracted twice.
- `FullScreenPlayerActivity` appends to the prior CSV with no second header block, and the resume
  request is consumed so a later normal Play does not re-resume.
- Deliberately **no staleness or completion gating** on eligibility: Resume is for continuing a
  test a short time after stopping it, the offset math is gap-size-agnostic, and the single most
  recent log is offered regardless of age.

### `cpu.usage.total` — supersedes the 2026-07-09 entry

That fix computed utilization from the system-wide `/proc/stat`. **It never worked:** SELinux
denies `untrusted_app` read access to `/proc/stat` on modern Android, so every read failed and the
column was silently pinned at 0.0 — the same symptom the 2026-07-09 entry set out to fix.

- `CpuUsageSampler` now reads **`/proc/self/stat`**. A process's own `/proc/self` entries carry its
  own domain label and AOSP policy grants `allow domain self:file r_file_perms`, so this is
  readable on every AOSP-derived device with no kernel-config dependency.
- Fields 14/15 (`utime`/`stime`) are cumulative for the whole thread group including exited
  threads, so in-process software decoders (dav1d, vvdec) are fully accounted for. **Hardware
  decode runs in a separate vendor process and is NOT included.**
- `usage = 100 × (Δticks / TICKS_PER_SEC) / (Δwall × numCpus)`, wall time from
  `SystemClock.elapsedRealtimeNanos()` (monotonic — unaffected by clock adjustments during a long
  run), `TICKS_PER_SEC` from `sysconf(_SC_CLK_TCK)` falling back to 100. Same 0–100 scale as
  before. Parsing starts after the **last** `)` because field 2 (`comm`) may itself contain spaces
  or `)`.
- Primed in the constructor so the first row reports a real value, capped at 100 for tick
  quantization, and a 250 ms minimum interval returns the previous value — rows are also logged
  out-of-band (end of file, resume), which can land two samples close together.
- **Note for comparisons:** logs written before this change have an all-zero `cpu.usage.total` and
  cannot serve as a baseline.

### Error-resilient decoding

- New `RunConfig.errorResilient`, **default off** so a decode error still fails the run and
  surfaces bad content. Included in `equals`/`hashCode`/comparator/copy-ctor.
- Checkbox in the conditions tab (`fragment_conditions.xml`, `FragmentTestConditions`).
- Threaded through `StrictRenderersFactoryV2` to a new **default 6-arg**
  `VcatDecoder.createVideoRenderer` overload that delegates to the 5-arg form, so existing decoders
  need no change; decoders that honor the flag override it.
- **`decoder-plugin-api` `1.0.4 → 1.1.0`** — the one change here with an external consumer.

### Thermal / battery overlay

- `BatteryInfo.getBatteryTemperatureC` (from the `ACTION_BATTERY_CHANGED` sticky intent,
  `EXTRA_TEMPERATURE` is tenths °C, −1 when unavailable).
- Player overlay tracks battery temp and `PowerManager.getCurrentThermalStatus()` (0–6, the only
  system thermal metric available to an app) as session cur:max, so the reading reflects the whole
  run regardless of when the overlay is opened.

### URI handling

- `UriUtils.fileNameFromURI` decodes before splitting: without it, `split("/")` on a `content://`
  document URI returned the whole encoded document id
  (`primary%3Avcat-d%2Fmedia%2Fav2%2Fclip.ivf`) — the long string that filled the overlay. Also
  strips a query and trailing slashes, and handles null/empty.
- `UriUtils.resolveMediaUri` re-maps standalone `content://` document URIs as well as legacy
  `file://` paths onto the current MEDIA tree, so playback relies on the app's **persisted tree
  grant** rather than a per-file grant. Picker-added `content://` entries otherwise carry only a
  transient grant and fail to open after a restart or reinstall. Other schemes pass through.

### Player controller UX

- `setControllerAutoShow(false)` + start hidden, so the controller and its synced overlay appear
  only on a screen touch rather than at playback start and at each playlist item.

### Verification

- `./gradlew compileDebugJavaWithJavac` clean; full unit suite passes.
- **On-device (CPH2629, Android 15, debug build):** all 35 previously-unstamped playlists were
  hidden, the launch prompt listed them, and one tap stamped all 35 with **35 distinct** ids,
  correctly placed above `<trackList>` with track locations preserved. The run logged during that
  session shows `execution_id` after `header_version` and `playlist: {id, name}`, with the logged
  `playlist.id` matching the stamped file's id exactly.
- Release build (unsigned by config — no release `signingConfig`; signed with the debug keystore
  for testing) installed on SM-S938U1, Android 16.
- **Not yet verified:** Resume end-to-end — stopping a run and resuming it, then confirming the
  appended rows stay under the original `execution_id` with no second header block.

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

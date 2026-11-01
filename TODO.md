# TODO

Priority order. P0 blocks reliability or can lose user data; P1 is expected product polish; P2 is future scope.

## P0 — robustness

- [ ] Stream HLS segments on Android instead of buffering each segment into a `ByteArray` in `HlsDataSource`; add Range/partial reads and a size cap.
- [ ] Add structured logging + a crash/error log file on Android and desktop. Replace remaining silent `runCatching` paths with contextual logs.
- [ ] Split play cache into long-lived capabilities (languages/resolutions) and short-lived URLs; validate URLs on use and keep stale entries only as fallback.
- [ ] Single-flight every duplicated resolve path: prewarm, PlaybackService refresh, player revalidation, 23-season AniWorld structure scan.
- [ ] Add cache schema/version + migration and a cache reset path; persist AniWorld season structure.
- [ ] Add unit tests for parsers, cache eviction, version comparison, absolute episode mapping, and sync merge (when added).
- [ ] Verify downloaded update APK signature/hash before handing it to the installer; sign release assets with a dedicated key (documented upgrade path).
- [ ] Android: persist player session across process death; handle audio becoming noisy and focus loss explicitly.
- [ ] Desktop: bundle libmpv with the tar (or detect and explain missing libmpv); replace fixed proxy port with a free port.
- [ ] Remove fixed localhost HLS proxy if Media3/mpv can consume the custom source directly; otherwise authenticate/lock it down.
- [ ] Make update checks tolerant of GitHub rate limits and keep a manual “Check for updates” action.

## P1 — product polish

- [ ] Settings screen: default audio language, title language, cache size/clear, providers, update channel, sync account.
- [ ] Subtitle track selection (including AniWorld GerSub) and subtitle styling.
- [ ] Next episode / autoplay after end; “up next” card.
- [ ] Buffering indicator and seek-preview thumbnails.
- [ ] Player volume/brightness gestures, playback speed, PiP button on Android.
- [ ] Recents: remove/hide entries, episode title in the row, “new episode” count, fully-watched filter.
- [ ] Search: recent searches, clear button, result count, keyboard/DPAD focus order.
- [ ] Accessibility: content descriptions/roles for all Canvas icons, focus states, screen-reader labels, minimum touch targets.
- [ ] Localization/i18n for UI strings (German first), date/number formatting.
- [ ] Desktop image cache: disk-backed LRU and byte cap; Android: explicit Coil memory/disk sizes.
- [ ] Update UI: show release notes and download size; add “skip this version”.
- [ ] Add LICENSE, CONTRIBUTING, issue templates, and a changelog.
- [ ] Replace hardcoded build versions/dependencies with a version catalog.

## P2 — future

- [ ] Google Drive sync (no server):
  - [ ] OAuth: Android Credential Manager; desktop installed-app PKCE + loopback; tokens in Keystore/libsecret.
  - [ ] `drive.appdata` hidden folder, single versioned JSON document, ETag/If-Match writes.
  - [ ] Per-episode merge with `(updatedAt, deviceId)` tie-break, tombstones, finished state.
  - [ ] Pull on launch/resume/60 s while foreground; debounced push on pause/exit; offline queue.
  - [ ] Sync settings screen, last-sync status, manual sync, disconnect, cloud-data deletion.
  - [ ] Optional E2E encryption passphrase.
- [ ] Downloads/offline playback with expiry and storage management.
- [ ] New-episode notifications (requires a cheap background refresh mechanism).
- [ ] AniList write actions: rate, progress, watch status.
- [ ] Desktop mini-player/PiP.
- [ ] Profiles, watchlist, recommendations.
- [ ] Consider real KMP source sets (commonMain/expect-actual) before adding web/iOS.

## Known issues / notes

- Android releases are debug-signed; changing the keystore breaks in-place updates.
- Provider scrapers (AniWorld/VOE/Vidmoly, Re:ANIME/FlixCloud) can break when sites change; no parser telemetry yet.
- Absolute episode mapping skips unnumbered specials, so rare special-numbering cases can still be off.
- Current play cache is intentionally permanent; refresh happens in the background.
- The old 8080 dev server and website were removed from the repo.

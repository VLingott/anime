# TODO

Priority order. P0 blocks reliability or can lose user data; P1 is expected product polish; P2 is future scope.

## P0 — robustness

- [x] Read cached HLS segments from disk streams on Android instead of retaining whole byte arrays in `HlsDataSource` (upstream fetch still buffers for decode). Range/partial reads remain.
- [x] Verify the downloaded APK is signed by the installed app before offering install.
- [x] Android audio focus handling.
- [x] Remove the dev server/web module.
- [x] Terminal error screen with reason + retry; update error dialog.
- [x] Structured log file + uncaught-exception logging on Android and desktop (`anime.log`, 2 MB rotation). Add an in-app log viewer later.
- [ ] Replace remaining silent `runCatching` paths with contextual logs.
- [ ] Split play cache into long-lived capabilities (languages/resolutions) and short-lived URLs; validate URLs on use and keep stale entries only as fallback.
- [x] Single-flight series loads.
- [x] Single-flight play/prewarm/revalidation (per-key mutex + 5 s dedupe).
- [x] Persist AniWorld season structure (24 h disk cache).
- [ ] Add cache schema/version + migration and a cache reset path; persist AniWorld season structure.
- [x] Unit tests for LRU/disk cache eviction and access ordering.
- [x] Match TVmaze shows by base title, tighten name matching (no unmatched fallback), and select the matching season(s) by episode count/year.
- [ ] Add unit tests for parsers, version comparison, absolute episode mapping, and sync merge (when added).
- [ ] Verify downloaded update APK signature/hash before handing it to the installer; sign release assets with a dedicated key (documented upgrade path).
- [ ] Android: persist player session across process death; handle audio becoming noisy and focus loss explicitly.
- [x] Desktop HLS proxy uses a free localhost port.
- [ ] Desktop: bundle libmpv with the tar (or detect and explain missing libmpv).
- [ ] Remove fixed localhost HLS proxy if Media3/mpv can consume the custom source directly; otherwise authenticate/lock it down.
- [ ] Make update checks tolerant of GitHub rate limits and keep a manual “Check for updates” action.

## P1 — product polish

- [ ] Settings screen: default audio language, title language, cache size/clear, providers, update channel, sync account.
- [ ] Subtitle track selection (including AniWorld GerSub) and subtitle styling.
- [ ] Next episode / autoplay after end; “up next” card.
- [ ] Buffering indicator and seek-preview thumbnails.
- [x] Android PiP (automatic when leaving while playing).
- [ ] Player volume/brightness gestures, playback speed, explicit PiP button.
- [x] Recents: new-episode marker, sorting, fully-watched entries sorted last.
- [x] Recents: long-press removal with confirmation (deletes recents + progress + audio preference).
- [ ] Recents: hide without deleting, episode title in the row.
- [ ] Search: recent searches, clear button, result count, keyboard/DPAD focus order.
- [ ] Accessibility: content descriptions/roles for all Canvas icons, focus states, screen-reader labels, minimum touch targets.
- [ ] Localization/i18n for UI strings (German first), date/number formatting.
- [x] Desktop image cache capped at 256 bitmaps (LRU).
- [ ] Desktop image disk cache; Android Coil memory/disk sizes.
- [x] Update dialog with download progress and install/restart.
- [ ] Show release notes/download size; add “skip this version”.
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

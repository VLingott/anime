# TODO

Priority order. P0 blocks reliability or can lose user data; P1 is expected product polish; P2 is future scope.

## P0 — robustness

- [x] HLS upstream segments stream into `.part` files in 64 KB chunks with a 40 MB hard cap; masked segments are decoded to disk without whole-segment buffers.
- [x] HLS cache serves files to Android/desktop; `HlsDataSource` honours `DataSpec.position`/length for Range and bounded partial reads.
- [x] Tests cover upstream streaming, cached reads without refetch, the segment size cap, and Android source compilation.
- [x] Verify the downloaded APK is signed by the installed app before offering install.
- [x] Android audio focus handling.
- [x] Remove the dev server/web module.
- [x] Terminal error screen with reason + retry; update error dialog.
- [x] Structured log file + uncaught-exception logging on Android and desktop (`anime.log`, 2 MB rotation). Add an in-app log viewer later.
- [x] Reviewed silent `runCatching` paths; added contextual warnings for AniWorld/Re:ANIME/TVmaze parsing, season caches, series/search/history/cache I/O, HLS fetches, resolution probes, image loads and update/install failures. Benign fallbacks (URI/entity parsing, best-effort cleanup) stay silent.
- [x] Play cache stores provider URLs untransformed; proxy/device URLs are applied on every read.
- [x] URL entries are validated on use (90 min TTL) with synchronous refresh and stale fallback; capabilities (languages/resolutions) persist separately in `capabilities-v1`/`resolutions`.
- [x] Single-flight series loads.
- [x] Single-flight play/prewarm/revalidation (per-key mutex + 5 s dedupe).
- [x] Persist AniWorld season structure (24 h disk cache).
- [x] Cache schema marker + reset; Settings dialog can clear search/series/stream/capability caches.
- [x] Unit tests for LRU/disk cache eviction and access ordering.
- [x] Match TVmaze shows by base title, tighten name matching (no unmatched fallback), and select the matching season(s) by episode count/year.
- [x] Search shows each AniList entry independently; the old season-combination grouping was removed because it broke pagination.
- [x] Never cache empty TV season lists; retry failed lookups and expose an episodes Retry state.
- [x] Cache directories versioned for metadata migrations (`series-v5`, `play-v6`).
- [x] Unit tests for version comparison, title/season parsing, absolute episode mapping, cache eviction and disk trim.
- [x] Parser tests use captured AniWorld/VOE HTML: hoster language mapping, episode titles/entities, season/episode lists, title markers, VOE payload decode and direct m3u8 fallback.

- [x] Android: player screen state survives process death via saved state; audio focus + becoming-noisy handling enabled.
- [x] Desktop HLS proxy uses a free localhost port.
- [x] Missing libmpv now produces a clear install message in the player error screen (bundling remains packaging work).
- [x] Desktop HLS proxy requires a per-process random token in addition to binding localhost.


## P1 — product polish

- [x] Android player hides system bars and the gesture handle; immersive mode restores on exit.
- [x] Settings: manual “Check for updates” with a one-minute client gate and GitHub rate-limit reset handling.
- [x] Settings: default title language, default audio language, adult-content filtering, cache size and clear.
- [x] Settings: provider toggles (per-provider enable/disable).
- [ ] Settings: update channel, sync account.
- [x] Subtitle track selection, per-series on/off switch and language picker in the player CC menu, size presets, and an online fallback (OpenSubtitles v3).
- [x] Next episode / autoplay after end with a cancellable “up next” countdown card.
- [ ] Buffering indicator and seek-preview thumbnails.
- [x] Android PiP (automatic when leaving while playing; in-app controls hidden, system PiP controls stay).
- [ ] Player volume/brightness gestures, playback speed, explicit PiP button.
- [x] Recents: new-episode marker, sorting, fully-watched entries sorted last.
- [x] Recents: long-press removal with confirmation (deletes recents + progress + audio preference).
- [x] Recents: background refresh of airing shows so new-episode markers stay current.
- [ ] Recents: hide without deleting, episode title in the row.
- [x] Search: tags selectable like genres, searchable full AniList tag catalog, multi-row overlay filter dialog; chips cycle include → exclude (red) → neutral; adult tags hidden with the adult filter.
- [x] Search: tag catalog uses a lazy grid and local overlay state, so typing in tag search does not recompose the results screen.
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

## Deferred by request

- Dedicated release signing key (debug signing accepted for now).

## Known issues / notes

- Android releases are debug-signed; changing the keystore breaks in-place updates.
- Provider scrapers (AniWorld/VOE/Vidmoly, Re:ANIME/FlixCloud, hanime.tv, AllAnime/mkissa) can break when sites change; parser failures are logged locally in `anime.log`.
- hanime.tv search returns the whole catalog (~4 MB) once per 10 min per session and matches AniList hentai titles by series name; weakly related titles may not map. Only engages when the AniList entry is flagged adult.
- Subtitle menu lists stream tracks first; when none exist the app tries OpenSubtitles (coverage depends on that database — e.g. the Saga of Tanya the Evil movie only has JP/other subs there). Some hosters (VOE) ship no tracks at all.
- AllAnime rotates its client crypto (build id + AES mask + header templates). Baked-in constants are latest-at-integration; when the site rotates, playback fails until the remote config (`AllAnimeProvider.Cfg.REMOTE_CONFIG_URL`) is updated — set `ALLANIME_CONFIG_URL` to point elsewhere. Known hosters for the current API are Ok.ru embeds (HLS master) and legacy `--` clock.json refs; other hosts (mp4upload/filemoon/vidnest) are deliberately not extracted yet.
- Absolute episode mapping skips unnumbered specials, so rare special-numbering cases can still be off.
- Current play cache is intentionally permanent; refresh happens in the background.
- The old 8080 dev server and website were removed from the repo.

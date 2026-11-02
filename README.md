# Anime

Native anime app with one shared Kotlin/Compose UI for Android and Linux desktop.

- Providers: AniWorld (German), Re:ANIME (Japanese/English), hanime.tv (adult only), AllAnime (rotating crypto; self-heals from a remote config when the site rotates)
- HLS playback: Media3 on Android, libmpv on desktop
- Persistent caches: search page 1, series views, play sources, HLS segments, images
- Unified language + resolution menus with automatic stream switching
- Home screen with continue-watching
- In-app update check with download/install screen (Android) and auto-download/restart (Linux), re-checked when the app regains focus
- Android picture-in-picture
- In-app update screens with automatic download (Android install prompt, Linux restart)
- Recents sorting, new-episode markers (refreshed for airing shows) and fully-watched ordering
- Episode lists use the aired count for ongoing shows (AniList `nextAiringEpisode`), not the planned total
- Autoplay next episode with a cancellable “up next” card (thumbnail + title, bottom-right)
- Subtitle tracks from HLS manifests and external AllAnime subtitle files, auto-selected by default, with a track menu and size presets
- Per-provider toggles in Settings
- Adult-content filter (hidden by default) for search results, genre/tag catalogs; hanime.tv is hidden and disabled entirely while it is on (hanime host fetches need a `Referer: https://hanime.tv/`, which the HLS proxy sends; some networks block hanime DNS)

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :desktop:run
./gradlew :desktop:createDistributable
```

## Release (local only)

No GitHub-hosted CI is used. Releases are built and uploaded from this machine:

```bash
./ci/release.sh          # build current version.properties and publish
./ci/release.sh --bump   # bump patch version, build, commit, tag and publish
```

The script uploads:
- `anime-<version>.apk` (debug-signed, stable on this machine)
- `anime-linux-<version>.tar.gz` (Compose Desktop distributable)

Version lives in `version.properties`; tags are `v<version>`.

## Auto update

Both apps check `https://api.github.com/repos/VLingott/anime/releases/latest` at launch and from
Settings → Check for updates.

- Checks are limited to one per minute per install.
- A GitHub `403`/`429` rate limit blocks further checks until `X-RateLimit-Reset`.
- Android: downloads the newest APK and opens the system installer.
- Linux: downloads the tar, unpacks it and restarts into the new build.

The APK must be signed with the same key every release for Android to update in place.

## Signing

GitHub has no built-in keystore hosting. Do not commit a signing key to a public repo — anyone could sign an APK that your updater would install.

Current releases are debug-signed with the local Android debug keystore:

- File: `~/.android/debug.keystore`
- Alias: `androiddebugkey`
- Certificate SHA-256: `11:BC:AA:2A:08:67:0F:15:F3:72:B7:FD:EB:5F:15:CE:64:55:67:30:DD:93:41:38:D3:DA:35:D6:8A:12:B8:B4`

Keep this file backed up. Deleting or regenerating it breaks in-place updates, because Android only installs an update signed with the same key.

GitHub Actions secrets can hold a keystore for hosted builds, but this project builds locally only, so the key stays on this machine. If a dedicated release key is added later, keep it out of the repo and expect one reinstall when switching from the debug key.

# Anime

Native anime app with one shared Kotlin/Compose UI for Android and Linux desktop.

- Providers: AniWorld (German), Re:ANIME (Japanese/English)
- HLS playback: Media3 on Android, libmpv on desktop
- Persistent caches: search page 1, series views, play sources, HLS segments, images
- Unified language + resolution menus with automatic stream switching
- Home screen with continue-watching
- In-app update check against GitHub Releases

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

Both apps check `https://api.github.com/repos/VLingott/anime/releases/latest` once at launch.

- Android: downloads the newest APK and opens the system installer.
- Linux: opens the release page when a newer version exists.

The APK must be signed with the same key every release for Android to update in place.

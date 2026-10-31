# Reel Saver

Reel Saver is a small Android app that saves an Instagram reel to your phone as a normal
video file. In Instagram, share a reel to **Reel Saver** and it downloads the video to
**Movies/Reel Saver**, where it shows up in your gallery like any other video.

- No Instagram login. Reel Saver never asks for your password or cookies.
- Because of that, reels from private accounts can't be saved. The app says so plainly
  when that happens.
- You can also open the app and paste a reel link yourself.

## Install

Download the APK from the [latest release](../../releases/latest) on your phone and open it.
Android will ask you to allow installs from your browser or file manager the first time.

Every release is signed with the same key, so a new version installs over the old one.

## Use it

**From Instagram:** open a reel, tap the share (paper plane) button, tap **Share** if
Instagram shows its own share screen first, and pick **Reel Saver**. You'll get a
notification when the video is saved. Tap it to watch.

**From the app:** open Reel Saver, paste a reel link, and tap **Download reel**.

## How it works

Instagram serves link-preview crawlers a public page for each public reel that contains
the direct video file. Reel Saver asks for that page (the embed page first, then the
normal one), pulls out the MP4 link, and downloads it into `Movies/Reel Saver` using
Android's MediaStore, so no storage permission is needed. If Instagram changes those
pages, this can break; the app tries a few page variants before giving up.

## Build

Needs JDK 17 or newer and the Android SDK (platform 36).

```
./gradlew assembleRelease
```

Release signing is read from the environment (`REELSAVER_KEYSTORE_FILE`,
`REELSAVER_KEYSTORE_PASSWORD`, `REELSAVER_KEY_ALIAS`, `REELSAVER_KEY_PASSWORD`) or from an
untracked `keystore.properties` in the project root (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`). Without either, the release APK is left unsigned.

## Release

Push a tag like `v1.0.0` matching `appVersion` in `app/build.gradle.kts`, with a matching
section in `CHANGELOG.md`. GitHub Actions builds the signed APK and publishes the release.

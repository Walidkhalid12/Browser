<p align="center">
  <img src="docs/logo.png" width="112" alt="Browser logo">
</p>

<p align="center">
  <img src="docs/banner.png" alt="Browser: a clean, black-and-white Android browser">
</p>

<h1 align="center">Browser</h1>

<p align="center"><b>A clean, black-and-white Android browser with smooth motion and downloads done right.</b></p>

<p align="center">
  <a href="https://github.com/Walidkhalid12/Browser/releases/download/v1.0.5/Browser-1.0.5.apk">
    <img src="https://img.shields.io/badge/Download%20APK-v1.0.5-000000?style=for-the-badge&logo=android&logoColor=white" alt="Download APK" height="48">
  </a>
</p>

<p align="center">
  <a href="https://github.com/Walidkhalid12/Browser/releases/latest"><img src="https://img.shields.io/github/v/release/Walidkhalid12/Browser?color=000000&label=release" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-000000?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-000000?logo=kotlin&logoColor=white" alt="Kotlin and Jetpack Compose">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-000000" alt="MIT license"></a>
</p>

---

Most browsers feel crowded, and their downloads are an afterthought. **Browser** keeps only what you need on screen, moves smoothly, and treats downloads like a real feature: you see the speed, the time left, a preview of every file, and you can open it right inside the app.

It's free, open source, and only about 2 MB.

## Screenshots

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/02_home.png" width="230"><br><sub>Home</sub></td>
    <td align="center"><img src="docs/screenshots/05_downloads.png" width="230"><br><sub>Downloads</sub></td>
    <td align="center"><img src="docs/screenshots/06_quick_settings.png" width="230"><br><sub>Quick settings</sub></td>
    <td align="center"><img src="docs/screenshots/08_home_dark.png" width="230"><br><sub>Dark mode</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/03_search_shortcuts.png" width="230"><br><sub>Search shortcuts</sub></td>
    <td align="center"><img src="docs/screenshots/04_tabs.png" width="230"><br><sub>Tabs</sub></td>
    <td align="center"><img src="docs/screenshots/07_settings.png" width="230"><br><sub>Settings</sub></td>
    <td align="center"><img src="docs/screenshots/01_sign_in.png" width="230"><br><sub>First launch</sub></td>
  </tr>
</table>

<sub>These screens are drawn from the app's own code at 1080 × 2340. The history, tabs and downloads in them are sample data.</sub>

## Features

**A calm design**
- Pure black and white with the Inter font, in light and dark mode. Dark mode follows your phone the first time.
- **Dark pages** darkens websites too.
- One bar at the bottom: tabs, search, and a new-tab button. It slides away while you read and comes back when you scroll up. Swipe on it to switch tabs.

**A home screen that's useful**
- The dot in the middle shows small widgets you can swipe through: time, date, weather, battery, open tabs, downloads and ads blocked. Hold the dot to choose them.
- Your most visited sites sit right under the dot.

**Downloads done right**
- Browser downloads files itself, so they keep going when you leave the app or lock the screen, with a notification that shows progress and has Pause and Cancel.
- Pause and continue any time. A paused or interrupted download picks up from the exact byte, even after you close the app or restart your phone.
- Fast and steady: big files download over up to 6 connections at once, connections are reused, and every file's size is checked when it finishes.
- When the network drops, downloads wait and continue by themselves when it's back.
- Live speed and time left, plus the number of files and their total size.
- Filters for videos, images, audio and documents, grouped by day, with thumbnails for photos and videos.
- A menu on every file: open, open with, share, copy link, retry, remove or delete.
- Clear reasons when a download is paused or fails, better file names, and no duplicate downloads of the same link.
- A built-in viewer for images (pinch to zoom), video, audio, PDF and text files. APK files install in one tap.

**Fast search**
- Autocomplete from the sites you visit, and paste and go.
- Shortcuts: type `@yt`, `@w`, `@gh`, `@maps` or `@x` to search YouTube, Wikipedia, GitHub, Maps or X.
- Voice search with its own animated screen.
- **AI mode:** turn it on and questions are answered by Google AI Mode in a clean screen, with images, sources and follow-up questions.

**Ad blocker**
- Blocks ads and trackers with the same filter lists as uBlock Origin and AdGuard: EasyList, EasyPrivacy, AdGuard Mobile Ads, Peter Lowe's list and the EasyList Cookie List. They download on first launch and update about once a week on Wi-Fi.
- Hides the empty ad boxes and banners left behind, from the start of the page, plus cookie banners.
- Stops pop-ups and pop-unders, "tap anywhere opens an ad" tricks, invisible full-page links and forced redirects to ad or scam pages.
- An on/off switch in Settings, "allow ads on this site" from the page menu, and a blocked count for each page and in total.

**Privacy**
- Private tabs that lock with your fingerprint or PIN, and delete what those sites stored when you close them.
- Camera, microphone and location are asked for per site, and you can remove them later.
- Websites with bad security certificates are refused.

**Tabs and tools**
- Undo close, recently closed tabs, and tabs that come back after the app is closed.
- Open links in the background or in a private tab.
- Every link stays in Browser: pop-ups and links that open a new window open as a new tab, never in another browser. Pop-ups a page opens on its own are blocked.
- Reader mode, translate, site info, QR code for any page, add to home screen, save as PDF, desktop mode and text size per site.
- Background audio and video, picture-in-picture, and full-screen video that turns to landscape.
- Home-screen widgets: search bar, quick actions, top sites, ads blocked, and the dot.
- Updates inside the app: Browser checks GitHub each time you open it, shows a card on the home screen and an "App updates" notification when a new version is out, then one tap downloads it, checks it and installs it.

## Why it's different

- **Less on screen.** No clutter, no outlined buttons, just solid shapes in black and white.
- **Downloads are a real feature,** not a hidden list.
- **Tuned for everyday phones.** Version 1.0.0 is a performance release built and tuned for the Redmi Note 9. It runs at your screen's highest refresh rate and ships with a baseline profile so it starts fast.
- **Small and open.** About 2 MB, MIT licensed, and every line of code is here.

## Requirements

- Android 8.0 (API 26) or newer
- A Google account on the phone for the first-launch sign-in (it signs Google, YouTube and Gmail in on the web for you)

## Install

1. On your phone, open the [latest release](https://github.com/Walidkhalid12/Browser/releases/latest) and download **Browser-1.0.5.apk**.
2. Open the file. If Android asks, allow your browser or file manager to **install unknown apps**.
3. Tap **Install**.
4. **Play Protect** may warn you because the app isn't from the Play Store. Tap **More details**, then **Install anyway**.

You can check the file with its SHA-256, listed on the release page.

On 1.0.4 or older? Install 1.0.5 this way once. From 1.0.5 on, new versions show up inside the app.

## Build from source

You need **JDK 17 or newer** and the **Android SDK** (platform 36, build tools 35.0.0). Android Studio includes both.

```bash
git clone https://github.com/Walidkhalid12/Browser.git
cd Browser
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

For a release build, run `./gradlew assembleRelease`. To sign it with your own key, add a `keystore.properties` file in the project folder (it's ignored by git):

```properties
storeFile=path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without that file, release builds are signed with the debug key.

Run the tests with `./gradlew testDebugUnitTest`.

## Tech stack

- **Kotlin** and **Jetpack Compose** (foundation only, no Material), with custom animations
- **Android WebView** with AndroidX WebKit for pages
- Its own download engine: a foreground service with ranged, multi-connection downloads that pause and resume
- Its own ad-blocking engine: ad servers as a sorted table of 64-bit fingerprints, every other rule filed under one word of its address, and element hiding injected at document start
- **Robolectric** for UI tests
- R8 shrinking and a baseline profile for release builds

## Privacy notes

- The weather widget finds your rough location from your internet address (ipwho.is or ipapi.co) and gets the weather from Open-Meteo, or wttr.in as a backup.
- AI mode loads Google AI Mode in the background to get the answer.
- The ad blocker downloads its filter lists straight from their makers (easylist.to, filters.adtidy.org, pgl.yoyo.org, secure.fanboy.co.nz). Pages and your browsing are never sent anywhere. Until the lists arrive, a small built-in list in `app/src/main/assets/blocklist.txt` is used.

## Not in 1.0 yet

- Tab groups with drag to reorder
- Link preview on long-press
- Actions for selected text
- Scanning QR codes with the camera
- A separate cookie jar for private tabs
- Downloads from `blob:` and `data:` links

## Contributing

Ideas and fixes are welcome.

1. Open an [issue](https://github.com/Walidkhalid12/Browser/issues) to report a bug or suggest something.
2. For code, fork the repo, make your change on a new branch, and open a pull request.
3. Please keep the look the same: black and white, solid shapes, smooth motion.

## License

Released under the [MIT License](LICENSE). © 2026 Walidkhalid12

### Filter list credits

The ad blocker uses these lists. They are not part of this repo or the APK: the app downloads them from their authors, and each keeps its own license.

- [EasyList](https://easylist.to/), [EasyPrivacy](https://easylist.to/) and the [EasyList Cookie List](https://secure.fanboy.co.nz/fanboy-cookiemonster.txt), by The EasyList authors, under [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) / [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/)
- [AdGuard Mobile Ads filter](https://github.com/AdguardTeam/AdguardFilters), by AdGuard, under [GPLv3](https://github.com/AdguardTeam/AdguardFilters/blob/master/LICENSE)
- [Peter Lowe's ad and tracking server list](https://pgl.yoyo.org/adservers/), by Peter Lowe

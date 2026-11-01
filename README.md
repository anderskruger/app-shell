# app-shell

A small Android app that opens one of my GitHub Pages web apps full screen, and gives it native extras
the browser can't: notifications with sound while the phone is locked, keeping the screen awake,
the back button, and storage Android won't clear.

The web app stays the source of truth. Pushing to its GitHub Pages repo updates the app on the phone
the next time it opens; this repo only needs a new build when the shell itself changes.

## Apps

| App | Web | APK |
|---|---|---|
| Plate Log | https://anderskruger.github.io/plate-log/ | [plate-log.apk](https://github.com/anderskruger/app-shell/releases/download/plate-log-latest/plate-log.apk) |

## Adding an app

1. Make a folder `apps/<name>/` with:
   - `app.json`: `appId` (unique, e.g. `io.github.anderskruger.<name>`), `appName`, `url`, `backgroundColor`, and optionally `scheme`
     (links like `<scheme>://anything` open the app; the page gets them from `App.getLaunchUrl()` and the `appUrlOpen` event)
   - `icon-only.png`: 1024×1024 launcher icon, artwork inside the middle ~60% (Android crops to a circle or squircle)
2. Push. GitHub Actions builds every app and publishes `<name>.apk` to the `<name>-latest` release.

## Native extras from the web page

Inside the app, `window.Capacitor.isNativePlatform()` is true and these are on `window.Capacitor.Plugins`:

- `LocalNotifications`: schedule notifications; channels can use the bundled sounds `rest_one`, `rest_two`, `rest_three`, `rest_long`
- `KeepAwake`: `keepAwake()` / `allowSleep()`
- `App`: `pause` / `resume` / `backButton` events, `minimizeApp()`
- `Session`: a lock-screen card with a big count-up timer and a button that works without unlocking
  - `show({ steps: [{ id, title, text, sound, data }], since, label, restLabel, button, doneTitle, doneText, alerts: [{ after, label, title, channel, channelName, sound }], volume, silent })`
  - the button completes the first open step: restarts the timer (unless `restart: false`), plays a random clip from the `sound` group and remembers `{ id, data, at }`
  - a step with `at` (ms) opens at that time, so a page can plan steps ahead; while none is open the card shows `waitTitle` and `waitText` + the time
  - `info()` exists from build 3 (`{ version: 3 }`), so a page can tell the card apart from older app versions
  - `take()` returns and clears those (`{ events }`); the `step` event fires when the page is running
  - `alerts` beep at `since + after` seconds while the app is out of sight
  - `cacheSound({ group, name, data })` (base64), `listSounds()`, `pruneSounds({ keep })`, `hide()`
  - see `SessionCard.java` for the details

In a normal browser `window.Capacitor` doesn't exist, so the page should treat all of this as optional.

## Signing

Release APKs are signed with one key so each update installs over the previous version.
The key lives outside the repo (`%USERPROFILE%\.android-signing\`) and in the repo secrets
`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_PASSWORD`, `SIGNING_KEY_ALIAS`.
Losing it means uninstalling the app (and its data) before installing a build signed with a new key.

## Local build (optional)

Needs Android Studio / the Android SDK and JDK 21:

```
npm ci
node scripts/configure.js plate-log
npx cap sync android
node scripts/configure.js plate-log --android
node scripts/make-sounds.js
cd android && ./gradlew assembleDebug
```

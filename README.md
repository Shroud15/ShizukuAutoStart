# Shizuku Auto Start

![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)
![Platform: Android 8.0+](https://img.shields.io/badge/platform-Android%208.0%2B-green.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple.svg)
![minSdk 26](https://img.shields.io/badge/minSdk-26-lightgrey.svg)

A small, standalone Android app that keeps a **Shizuku-compatible manager** running
after the phone is locked and unlocked. It does **not** modify the Shizuku app, does
**not** need root, and does **not** replace Shizuku — it only asks the installed manager
to start, again and again, until the Shizuku service is actually running.

It works with the **original Shizuku**, **Shizuku Next**, **Shizuku+** and other forks:
the installed manager is discovered at runtime instead of being hard-coded.

> **Not affiliated with the Shizuku project or any fork.** Shizuku is created by
> RikkaApps; forks such as Shizuku Next and Shizuku+ are independent projects. This is a
> companion utility.

---

## The problem

On many phones the Shizuku server dies while the device is locked (wireless-debugging
sockets and network access can be suspended with the screen off). When you unlock, the
manager's own watchdog is supposed to restart the service — but it often doesn't:

- forks such as Shizuku Next give up after a few failed starts and need a manual
  **Start**, and
- on devices without a secure keyguard the unlock broadcast they listen for may never
  arrive, so they never retry at all.

Result: after lock/unlock Shizuku stays down until you open the manager.

## What this app does

A low-priority foreground service (ongoing notification, no wakelock) watches for:

- `ACTION_USER_PRESENT` — the normal unlock event, and
- `ACTION_SCREEN_ON` when no keyguard is showing (devices with swipe/none lock screen).

On every unlock, and whenever Shizuku stops while the phone is unlocked, it asks the
installed manager to start and then retries **every 10 s → 15 s → 20 s → 30 s, forever**,
until `Shizuku.pingBinder()` answers as running. It pauses while the screen is off and
resumes on the next unlock. It restarts itself after a reboot and after an app update.

The interface is built with **Material 3 (Material You) and the Expressive motion
language**: large tonal cards, bold typography, a spring-driven entrance, springy button
presses, a status card that morphs between running and stopped, an animated loading
indicator while a retry is pending, and an animated battery button that leaves the screen
once the restriction is gone. Dynamic colours are used on Android 12+.

The two icons in the top-right corner switch between light and dark mode and open a short
**How to use** guide. The compact indicator beside *Shizuku service* is a solid lit button: a
lit green or red body held inside a fully opaque rim (no inner dot and no outer glow), green
while the service runs and red while it is stopped. Tapping it shows the current state
in a snackbar.
Everything else is the live status, the detected Shizuku apps and the one-time setup
buttons.

## Watcher option

Shizuku forks such as Shizuku Next ship their own watchdog, and some users prefer the app
to leave that watchdog alone and do the whole restart itself. Both are supported:

- **Also wake Shizuku's watchdog on each retry (no token needed)** — when on, every retry
  also sends the fork's token-free `WATCHDOG_ON` action, so the manager's watchdog probes
  the server as well. When off, the app still asks the manager to start and still retries,
  but it never wakes the watchdog.
- **Wake the watcher now** — sends that watchdog action immediately, once.

The whole **Watcher** section is only shown when an installed manager actually exports a
watchdog action, so nothing is offered for forks that do not have one.

## Which Shizuku builds it supports

Managers are found by asking the PackageManager which apps implement the standard
Shizuku binder-request protocol (`rikka.shizuku.intent.action.REQUEST_BINDER`), plus the
known application ids. Each manager is then probed for the start/watchdog broadcasts it
really exports — so a renamed fork is handled like any other.

| Manager | Automatic restart after unlock |
|---|---|
| Shizuku Next, and forks built on it (`<applicationId>.START`, `WATCHDOG_ON`) | Yes — the token-free `WATCHDOG_ON` is used; `START` is used when a token is saved |
| Shizuku+ (`af.shizuku.plus.api`) and its compat hub | Yes when the build exports a start action; otherwise its own watchdog + the standard binder request are used |
| Original Shizuku (`moe.shizuku.privileged.api`) | **Not possible for any third-party app.** Original Shizuku only starts on boot or from its own UI; there is no exported start intent. The helper still sends the standard binder request, keeps retrying, and offers a one-tap *open the Shizuku app* notification |
| Sui (Magisk module) | Nothing to start — the module provides the binder itself; the helper simply detects it as running |
| Any future fork | Yes, if it keeps the standard protocol or the usual `<applicationId>.START` / `WATCHDOG_ON` actions |

Everything is discovered on the device: if you switch from Shizuku Next to another fork,
the app re-scans on the next unlock and uses whatever that fork exports.

## How it asks the manager to start

No source changes are made to the Shizuku app; only already-exported entry points are
used:

| Route | Token required | When it is used |
|---|---|---|
| the standard binder request (`rikka.shizuku.intent.action.REQUEST_BINDER`) | no | always, to every detected manager |
| discovered start actions such as `<package>.WATCHDOG_ON` | no | on every retry, when the watcher option is on |
| discovered `<package>.START` | yes, when the manager requires it | only when a token is saved |

The token is optional and only needed for forks that gate their `START` intent with one
(Shizuku Next: **Intents** screen). Leave the field empty otherwise.

## Requirements

- Android 8.0 (API 26) or newer.
- Any Shizuku-compatible manager installed, or the Sui Magisk module.
- Notification permission (Android 13+).
- One-time setup: tap **Disable Battery Restrictions** in the app (Vivo/iQOO and other
  aggressive OEM battery managers may otherwise kill the helper service). The button
  disappears as soon as the app is exempt.

## Install

Download `ShizukuAutoStart-v1.6.3-release.apk` (or `ShizukuAutoStart-v1.6.3-debug.apk`)
from the latest CI artifact or the v1.6.3 release, then:

```bash
adb install -r ShizukuAutoStart-v1.6.3-release.apk
```

Open **Shizuku Auto Start**, keep *Auto restart after unlock* enabled, check that your
manager is listed under *Detected Shizuku apps*, paste the auth token if needed, and
press *Disable Battery Restrictions* once.

## Build

The project targets AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, targetSdk 34, minSdk 26,
Java 17.

```bash
./gradlew assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease  # app/build/outputs/apk/release/app-release.apk
```

`assembleRelease` always produces an installable APK: it is signed with a private release
key when the `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
`RELEASE_KEY_PASSWORD` Gradle properties are set (for example in `~/.gradle/gradle.properties`
or CI secrets), and falls back to the debug key otherwise. Keep a private keystore out of
this repository.

On Termux/on-device toolchains without the wrapper, the installed `gradle` (8.14+) works
the same way. A GitHub Actions workflow runs the unit tests and builds both the debug and
the release APK on every push and pull request to `main`, and attaches them to a release
as `ShizukuAutoStart-v<version>-debug.apk` and `ShizukuAutoStart-v<version>-release.apk`
when a `v*` tag is pushed.

Unit tests cover the action classifier and the signal selection:

```bash
./gradlew testDebugUnitTest
```

## Project structure

```
app/
  src/main/AndroidManifest.xml
  src/main/java/com/helper/shizukuautostart/
    App.kt                applies the saved theme and Material You dynamic colours
    MainActivity.kt       Material 3 Expressive screen, live status, expressiveness
    RestartService.kt     foreground service: unlock detection + retry loop
    ShizukuManagers.kt    discovers Shizuku managers and their start actions
    StatusButtonView.kt   the compact lit status lamp, drawn as a custom view
    BootReceiver.kt       restarts the service after boot / app update
  src/main/res/layout/    activity_main.xml (Material 3 layout)
  src/main/res/values*/   strings and themes for light and dark mode
  src/main/res/drawable/  vector icons
  src/test/java/...       unit tests for the fork action logic
build.gradle              top-level plugin versions
settings.gradle           module and repository configuration
.github/workflows/        CI: builds both APKs and attaches them to v* releases
```

## Troubleshooting

- **Nothing happens after unlock.** Some launch-on-unlock launchers/skins do not send
  `USER_PRESENT`; the app also listens for screen-on with no keyguard. Check the ongoing
  notification — it always shows the current state and the countdown to the next attempt.
- **Retries stop after the phone has been idle.** The OEM battery manager killed the
  service. Use *Disable Battery Restrictions*.
- **A manager is not listed.** It does not export any of the known start actions. The app
  will still send the standard binder request and show *manual start needed* — that
  manager (e.g. original Shizuku) can only be started from its own UI.
- **Shizuku still does not come back.** Shizuku Next may have the token requirement on:
  copy the token from **Shizuku Next → Intents** into the app.
- **Notification missing.** Allow notifications for the app on Android 13+.

## Privacy

No analytics, no ads, no network permission. All state (enable flag, optional token) is
stored in the app's private `SharedPreferences`.

## License

Licensed under the **Apache License 2.0** — see [LICENSE](LICENSE) for the full text.

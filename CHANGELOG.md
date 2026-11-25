# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.6.3] - 2026-11-25

### Changed
- After **Save token** is pressed, the button stays on **Saved** instead of snapping back
  after a moment. The normal label returns only when the token field is touched again, so
  the stored state is always visible without flashing.
- The status lamp now has a fully opaque rim around its lit green/red body. The small
  white specular dot inside the lamp is gone; the rim gives the button its solid outline.
- Launcher icon: the restart ring is now a complete, closed circle (the gap and arrowhead
  are gone), and the whole mark is shaded to look slightly three-dimensional — a dark
  outer bevel, a top-to-bottom gradient body, a top highlight, an inner bottom shade, a
  cast shadow, and a background vignette.
- versionCode 10, versionName 1.6.3.

## [1.6.2] - 2026-11-25

### Fixed
- Saving the auth token now drops the caret and the keyboard immediately: the field
  loses focus, the cursor stops blinking, and the save button briefly reads "Saved" as
  confirmation. Tapping the field again brings the caret back.
- versionCode 9, versionName 1.6.2.

## [1.6.1] - 2026-11-25

### Changed
- The status lamp keeps the compact size of the original dot beside "Shizuku service"
  (10dp instead of the oversized disc) while keeping the lit green/red look and the colour
  and press animations. Its touch target stays a comfortable 48dp.
- versionCode 8, versionName 1.6.1.

## [1.6.0] - 2026-11-25

### Changed
- The status indicator beside "Shizuku service" is now a solid, lit button: a green disc
  with a soft top light while the service is running and a red one while it is stopped.
  The outer glow/halo is gone; the colour still animates on every state change, the button
  gives a small press animation, and tapping it shows the current state in a snackbar.
- New launcher icon: a Shizuku-style water-splash mark on an indigo gradient with the
  faint blueprint grid, wrapped by a restart ring. Adaptive icon for Android 8+ with a
  matching monochrome layer for Android 13+ themed icons.
- versionCode 7, versionName 1.6.

## [1.4.0] - 2026-11-25

### Added
- The whole screen is rebuilt with **Material 3 (Material You)** and the **Expressive**
  motion language: tonal cards with large rounded shapes, bold typography, dynamic
  colours on Android 12+, an animated loading indicator while a retry is pending, a
  status card that morphs between running and stopped, and spring-driven animations on
  every action (entrance, presses, switches, buttons and the help dialog).

### Changed
- The battery shortcut is now labelled **Disable Battery Restrictions**, and it hides
  itself automatically once the app is exempt.
- The "Try one start now" button was removed; the helper already retries on its own.
- Light/dark mode now uses the Material 3 day/night theme and follows the system until
  the toggle is used for the first time.
- versionCode 5, versionName 1.4.

## [1.3.0] - 2026-11-25

### Added
- The **Watcher** section is back on the main screen, and it is only shown when an
  installed Shizuku manager exposes a watchdog.
- "Also wake Shizuku's watchdog on each retry (no token needed)" switch: when it is on the
  app both nudges the manager's watchdog and asks the manager to start; when it is off the
  app performs the whole restart itself without waking the watchdog.
- "Wake the watcher now" button, which immediately sends the watchdog action to every
  detected manager that supports one.

### Changed
- versionCode 4, versionName 1.3.

## [1.2.0] - 2026-11-25

### Added
- Dark/light mode toggle as an icon in the top-right corner of the main screen. The choice
  is saved and applied immediately; when it has never been set, the app follows the system
  dark theme.
- "How to use" icon in the top-right corner, opening a short setup guide.
- A light and a dark theme (`Theme.ShizukuAutoStart` / `Theme.ShizukuAutoStart.Dark`), with
  matching status-bar and accent colours.
- `assembleRelease` now always produces an installable release APK. It uses a private
  keystore when the `RELEASE_*` Gradle properties are provided and otherwise falls back to
  the debug key.
- The CI workflow builds and publishes both the debug and the release APK.

### Changed
- versionCode 3, versionName 1.2.

## [1.1.0] - 2026-11-25

### Added
- Support for every Shizuku-compatible manager instead of only Shizuku Next: the app now
  discovers installed managers at runtime (standard binder-request protocol plus known
  application ids) and probes the start/watchdog broadcasts each one really exports.
- Shizuku+ (`af.shizuku.plus.api`) and renamed future forks are picked up automatically.
- Sui (Magisk module) is detected as already-running; nothing needs to be started.
- A "Detected Shizuku apps" list with the supported start mode for each manager.
- One-tap "open the Shizuku app" notification for managers that cannot be started by
  another app (original Shizuku), instead of silently pretending to restart them.
- Unit tests for the action classifier, manager-independent signal selection and token
  gating.

### Changed
- The auth token is only used for a bare `<package>.START` action, so no manager is
  spammed with "authentication failed" notifications when no token is configured.
- Status and documentation now describe all supported managers.

## [1.0.0] - 2026-11-25

### Added
- Foreground service that detects unlock (`ACTION_USER_PRESENT`, and screen-on when no
  keyguard is present) and asks Shizuku Next to start.
- Infinite retry loop (10 s → 15 s → 20 s → 30 s) until the Shizuku binder is alive.
- Retry pause while the screen is off and immediate retry when Shizuku dies repeatedly.
- Token-free start via `WATCHDOG_ON` and binder requests; optional auth-token support for
  the `START` intent.
- Boot and app-update receiver that restarts the helper service.
- Live status screen with one-touch battery-optimization exemption.

# Security Policy

## Supported versions

Only the latest release of Shizuku Auto Start receives fixes.

## Reporting a vulnerability

Please **do not** open a public issue for security problems. Instead use GitHub's
[private vulnerability reporting](../../security/advisories/new) on this repository and
include:

- a description of the issue and its impact,
- reproduction steps or a proof of concept,
- the app version (`versionName`) and Android version affected.

You can expect an acknowledgement within a few days. Please allow reasonable time for a
fix before public disclosure.

## Scope

Shizuku Auto Start is a local-only utility: it requests no network permission, collects
no telemetry, and stores only its enable flag and an optional auth token in the app's
private `SharedPreferences`. Reports about the Shizuku service itself belong to the
upstream Shizuku / Shizuku Next projects.

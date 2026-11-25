# Contributing

Thanks for wanting to improve Shizuku Auto Start.

1. Fork the repository and create a branch from `main`.
2. Keep changes focused: this app intentionally does one thing — restarting Shizuku after
   unlock.
3. Build and check before opening a pull request:
   ```bash
   ./gradlew assembleDebug
   ```
4. Describe what you changed and, if it is behaviour-related, how you tested it (device,
   Android version, Shizuku manager and version).

Please do not add network permissions, analytics, ads, or changes to your Shizuku manager
itself — the app must stay a read-only companion that only uses the manager's public
automation entry points.

By contributing you agree that your changes are licensed under the project's
Apache License 2.0 (see [LICENSE](LICENSE)).

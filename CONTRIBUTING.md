# Contributing to BlackClaw

Thanks for your interest in improving BlackClaw. This project is in **beta**, so
bug reports, reproductions, and focused pull requests are especially valuable.

## Ways to contribute

- **Report bugs.** Open an issue and attach the debug report ZIP from
  *Settings → About → Share debug report*. It includes device fingerprint, ABIs,
  RAM, permission state, and recent logs — enough to triage most issues without a
  follow-up.
- **Test on your device.** OEM skins (MagicOS, MIUI/HyperOS, One UI, ColorOS)
  behave differently around Accessibility, background limits, and Wireless
  Debugging. Reports from real devices help a lot.
- **Improve docs.** Clarifications and fixes to the README or the skill spec are
  welcome.
- **Send code.** Bug fixes and small, focused features are easiest to review.

## Development setup

1. Install Android Studio (or the command-line SDK) with an Android 9+ (API 28+)
   target and JDK 17.
2. Clone the repo and let Gradle sync.
3. Build a debug APK:

   ```bash
   ./gradlew assembleDebug
   ```

4. Install on a connected device:

   ```bash
   adb install -r app/build/outputs/apk/debug/*.apk
   ```

## Pull request guidelines

- Branch from `main`; keep each PR focused on one change.
- Match the existing Kotlin/Java style and the conventions already in the file
  you are editing. Don't introduce new libraries for things the project already
  solves.
- Build and lint locally before opening the PR:

  ```bash
  ./gradlew assembleDebug lintDebug
  ```

- Describe what changed, why, and how you tested it. For UI changes, include a
  screenshot.
- Don't commit secrets. `local.properties`, keystores (`*.jks`), and `.env` are
  gitignored — keep them out of commits.

## Commit messages

Use a short prefix that describes the area, e.g. `fix(agent):`, `feat(tools):`,
`perf(agent):`, `docs:`. Write the body in plain language explaining the change.

## Code of conduct

Be respectful and constructive. Assume good faith. Harassment or hostility isn't
welcome here.

## License

By contributing, you agree that your contributions are licensed under the
project's [Apache License 2.0](LICENSE).

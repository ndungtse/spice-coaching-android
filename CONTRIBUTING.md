# Contributing

Thanks for your interest in the MicroCoaching Android SDK.

## Local setup

1. Clone the repo.
2. Create `local.properties` at the repo root with at minimum:
   ```
   sdk.dir=/path/to/Android/Sdk
   HF_TOKEN=<your-huggingface-token>
   ```
   `HF_TOKEN` is required at runtime to download the on-device Gemma model. Get a token at https://huggingface.co/settings/tokens.
3. Build the library:
   ```
   ./gradlew :sdk-android:assembleDebug
   ```
4. Build the sample app:
   ```
   ./gradlew :app:assembleDebug
   ```
5. Run unit tests:
   ```
   ./gradlew :sdk-android:test
   ```

## Pull requests

- Open PRs against the default branch.
- Each PR should have a clear summary, a short test plan, and screenshots if it changes user-facing UI in the sample app.
- Keep diffs focused — one logical change per PR.
- Follow existing Kotlin style; no formatter is enforced in CI yet, but match the surrounding code.

## Reporting issues

- Use GitHub Issues for bugs and feature requests.
- For security issues, see [SECURITY.md](SECURITY.md).

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).

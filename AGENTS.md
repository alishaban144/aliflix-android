# Working in Aliflix

- Use [the code map](docs/ARCHITECTURE.md) to pick the owning file and read its tests first. Default `rg` searches skip release history and generated Worker types. Use `rg --no-ignore '<term>' docs/archive/` when investigating an old release.
- Android has one mobile flavor. Keep `com.aliflix.app`, the release certificate, exported activity contracts, and update asset names compatible with installed apps.
- Media3 owns native playback. The WebView is used for provider resolution and supported fallback playback. Inspect the route policy and fallback tests when editing either path.
- Android checks on Windows run with `.\scripts\verify-build.ps1`. For a scoped check, use `:app:testMobileDebugUnitTest --tests '<class or pattern>'`. Worker checks are `npm run typecheck` and `npm test` from `recommendation-worker/`.
- Put temporary outputs under the ignored `.local/` directory, or use Gradle's and the validation harness's existing output paths. Keep device captures, downloaded APKs, and experiments out of source.
- See [Development](docs/DEVELOPMENT.md) for test setup and [Releasing](docs/RELEASING.md) for signing and updater details.

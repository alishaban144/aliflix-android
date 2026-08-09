# Discover performance benchmark

`DiscoverRecommendationBenchmark` drives the complete mobile Discover journey
against a release-equivalent `mobileBenchmark` APK:

1. cold-start Aliflix and explicitly open Discover;
2. submit a series recommendation;
3. answer a deterministic preference clarification;
4. wait for usable initial results; and
5. scroll until a later ranked page starts and finishes.

The run records startup and frame-timing metrics and emits a Perfetto trace for
each measured iteration. It accepts a failed later page only when the initial
results remain usable, matching the product's partial-page contract.

## Physical-device run

Use one mid-range physical phone on API 34 or newer. Keep it unplugged from a
thermal-throttled charge state, above 25% battery, with a stable network. Close
other foreground apps and leave system animation scales at their normal values.

```powershell
adb devices -l
.\gradlew.bat :benchmark:connectedMobileBenchmarkAndroidTest --no-daemon --console=plain
```

Inspect the generated result JSON and `.perfetto-trace` files under
`benchmark/build/outputs/connected_android_test_additional_output/`. In Android
Studio, open the traces in the System Trace profiler and inspect the Discover
submission, clarification, initial ranking, and append interval. A passing run
must show no ANR/process exit, no frame stalled for seconds, one append request
despite repeated near-end signals, and an interactive result list if the later
page fails.

The benchmark relies on Compose test tags being exported as resource IDs from
the app semantics tree. Its stable contract is documented by the constants in
`DiscoverRecommendationBenchmark.kt`.

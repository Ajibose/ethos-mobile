# Multidex Support (#430)

Android's dex format has a hard ceiling of **65,536 method references per dex
file** (the "64K limit"). As the dependency tree grows — Compose, Hilt, Ktor,
Firebase, Room, WorkManager, and their transitive pulls — it's easy to cross
that boundary and receive a build error like:

```
Cannot fit requested classes in a single dex file (# methods: 76349 > 65536)
```

Multidex support splits the compiled output across multiple dex files so the
build succeeds and all methods are reachable at runtime.

## Implementation in this project

`multiDexEnabled = true` is set in `defaultConfig` in
`android/app/build.gradle.kts`. That's the only change required.

**Why no `MultiDexApplication` and no support library?**
`minSdk = 28` (Android 9, API 28). The platform-native multidex loader has been
built into Android since API 21; it transparently loads all dex files before
`Application.onCreate()` runs. The legacy `androidx.multidex` library and the
`MultiDexApplication` / `MultiDex.install()` patterns it ships are only needed
for `minSdk < 21`. Adding them here would be dead code.

See `EthosProtocolApplication.kt` for the confirming comment.

## Tracking the current method count

Staying aware of method count before it becomes a build failure is good
practice. There are two low-effort ways to do this.

### 1. dexcount-gradle-plugin (automated, per-build)

[`dexcount-gradle-plugin`](https://github.com/KeepSafe/dexcount-gradle-plugin)
reports the method and field counts for every assembled APK/AAB as a Gradle
build output, and can be configured to fail the build if a threshold is
crossed.

Add to `android/app/build.gradle.kts`:

```kotlin
plugins {
    // …existing plugins…
    id("com.getkeepsafe.dexcount") version "4.0.0"
}

// Optional: fail the build when the total reference count exceeds a threshold,
// giving early warning before headroom runs out.
dexcount {
    // Warn at 80 % of the 64K limit per dex file (≈ 52 000 references).
    // Adjust as the project grows. Remove maxMethodCount to make it advisory only.
    maxMethodCount = 52_000
    // Print a per-package breakdown in the build output for triage.
    printAsTree = true
    // Also write a CSV report to build/outputs/dexcount/ for trend-tracking.
    enableForRelease = true
}
```

The plugin writes a report to `build/outputs/dexcount/` on every `assemble`
run. Archive it in CI (`actions/upload-artifact`) to track the trend across
PRs.

### 2. Manual one-off count with `apkanalyzer`

Android SDK ships `apkanalyzer`, which gives an exact count without a plugin:

```bash
# From the repo root, after assembling a debug APK:
cd android
./gradlew assembleDebug

$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer dex references \
    app/build/outputs/apk/debug/app-debug.apk
```

Output is per-dex-file. Sum the totals for the true cross-dex reference count.

For a release AAB:

```bash
./gradlew bundleRelease
$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer dex references \
    app/build/outputs/bundle/release/app-release.aab
```

### 3. Android Studio built-in

Open the assembled APK in Android Studio via **Build → Analyze APK…** to see
an interactive breakdown of method references by package, which helps identify
which dependency is contributing the most references.

## Startup performance impact

Native multidex (API 21+) incurs **no meaningful cold-start overhead** because
the platform's class loader handles dex file chaining before the application
process is handed to user code. The historical startup penalty associated with
multidex was specific to the legacy support-library implementation on API < 21,
which had to perform secondary-dex extraction at first launch.

The `StartupPerformance.markAppStart()` call in `EthosProtocolApplication` and
the Macrobenchmark suite (`androidTestImplementation(libs.benchmark.macro)`)
can be used to verify that cold-start time is unaffected after any dependency
additions that push the build further into multidex territory.

## What to do if method count grows further

1. Check `apkanalyzer` or the dexcount report to identify the largest
   contributors.
2. Prefer dependency trimming (remove unused transitive pulls, use `-ktx` /
   `-compose` variants that don't bundle the full library) over adding more dex
   files.
3. `isMinifyEnabled = true` in the `release` build type (already configured)
   runs R8, which strips dead code and can reduce reference count significantly
   for release artifacts.
4. If a debug build is also too large, enable R8 for debug builds with
   `isMinifyEnabled = true` under `buildTypes { debug { … } }` — but be aware
   this slows incremental build times.

# Performance Test, fixture version 1

Open **Settings → Performance Test → Run Test**. Confirm the approximately
10.2 MiB transfer and keep the screen visible. The screen shows stage progress,
errors and a summary. Cancel closes the Gecko session and disconnects the
network probe; a blocked read can take up to 15 seconds to unwind. Leaving the
activity, rotating, or sharing while running cancels it. Repeat after cleanup.

The ten most recent reports stay in private app preferences. Copy/share exports
JSON; Clear saved results deletes that local history. No browsing history,
user tabs, accounts, cookies, visited URLs, serial numbers or advertising IDs
are read. Exports include device model, Android/browser versions, build type,
timestamp, measurements and errors. Review these before sharing.

## Measurement scope and limitations

* **Page:** a bundled HTML fixture runs in a new private GeckoSession using the
  existing runtime. The native load-request-to-page-stop callback and available
  Navigation/Paint Timing entries measure this local document, not an HTTPS
  website navigation. Missing timing entries are unavailable, never zero-filled.
  DOM work followed by two animation frames reports a render opportunity, not
  proof of physical display/presentation time.
* **Images:** three cache-busted HTTPS requests to a PNG pinned to repository
  commit `488386baa8ce7e280364edbb041daf625bb3c08d`, exactly 38,625 bytes each.
  Byte counts are verified; fetch, decode, and fetch-to-render-opportunity times
  are separate. Each decoded image is appended and scrolled into view before
  waiting two animation frames. This small image is not a photo-gallery benchmark.
  DNS/TLS/connections and server caches can remain warm between requests.
* **JavaScript:** fixed-seed xorshift32, one million iterations, two warmups and
  five samples with checksum and median. This synthetic integer workload is not
  a general browser score. Timer precision, JIT and scheduling affect results.
* **Network:** `https://speed.cloudflare.com/__down?bytes=10485760` supplies a
  dedicated 10 MiB test response. Exactly 10,485,760 bytes must arrive. HTTP
  errors, redirects, truncated and oversized responses fail. Bytes are streamed
  and discarded. Decimal Mbps includes the entire request duration, including
  connection setup. This Android HTTPS probe preserves normal TLS verification;
  it does not measure Gecko's download service, disk speed, maximum connection
  capacity, or uBlock Origin filtering.
* **UI:** three seconds of native marker animation use Android FrameMetrics.
  P95 is nearest rank. Over-budget counts use per-frame DEADLINE on API 31+;
  older Android versions use an explicitly labeled initial-refresh-rate estimate.
  Dropped callback samples are reported. These are not Gecko compositor,
  webpage-scroll or touch-latency measurements. Variable refresh rates and
  dropped samples limit interpretation.
* **Startup:** unavailable. Cold startup needs an external launch harness with
  a defined process state; an in-app button cannot measure it accurately.

Only the fixed HTTPS resources above are contacted. Cloudflare and GitHub see
the device IP and ordinary request metadata. Image requests omit credentials
and referrers. Neither endpoint receives browser history. Provider failures
produce errors, not fallback scores. No global Gecko, security or extension
settings are changed. The synthetic private fixture does not exercise the full
normal-tab extension lifecycle; normal browsing with uBO remains a regression gate.

## Optimized APK

The existing **Development APK** workflow is unchanged. **Performance APK** runs
on matching pull requests into `iceraven` and supports manual dispatch once
present on the default branch. It checks fixture contracts and JVM calculation
tests, builds optimized `forkRelease`, signs and verifies APKs, and retains them
as artifacts for seven days. It reuses the working development toolchain and
memory limits, but does not publish releases or upload to Play.

The measurement tests run in the independent JVM build at
`automation/iceraven/performance-tests`. It imports Kotlin and JUnit versions
from the root version catalog and compiles only the existing `PerformanceMath.kt`
and `PerformanceMathTest.kt` files. It does not load the Android root build or
compile unrelated application tests. Run it from the repository root with
`./gradlew -p automation/iceraven/performance-tests clean test` (Java 17 toolchain
required). The test task and workflow report check both require exactly five
passing, non-skipped tests. `clean` prevents an old report from satisfying the
check. This isolates the measurement suite; it does not repair or validate the
rest of the application's test suite.

Each run creates an ephemeral RSA test key in runner temporary storage. Its
password is the conventional public test password; no production credentials
are used and the private key is never uploaded. Keys differ between runs.
APKs retain the existing forkRelease package/shared-user identity, so they
cannot update differently signed Iceraven installations and may conflict with
an installed debug variant. Back up data before uninstalling anything. These
are review APKs, not a stable production update channel.

The job has a two-hour timeout and can hit runner memory/disk limits. Actions
minutes and artifact/cache storage are charged according to the repository's
plan. A successful signature check is not evidence of runtime correctness.

## Validation and device acceptance

Local checks: fixture contracts with fake browser objects, five pure JVM
calculation tests, Kotlin parsing/formatting, XML and actionlint. Fake data is
used only in contract tests, never in app reports. No local Android build or
device performance run was performed. Android compilation and integration tests
must be checked in GitHub Actions before merging.

Required device checks:

1. Run, repeat, cancel during every stage, navigate away, rotate, and restart.
   Check cleanup, saved partial reports, and prevention of concurrent runs.
2. Offline mode, blocked image host, slow/stalled transfers and HTTP failures.
   Check errors, bounded memory, and absence of fabricated scores.
3. Copy/share/clear, ten-report retention, accurate metadata and no user URLs.
4. Large fonts, dark mode, system insets, scrolling, and TalkBack.
5. Full uBO blocking, cosmetic filters, scriptlets, updates and private-mode
   permissions before and after testing, without changes to extension settings.
6. Compare repeated results only under matching build, phone, network, refresh
   rate, battery/thermal and background-load conditions. Keep debug and optimized
   reports separate; do not infer Chrome parity from this synthetic fixture.

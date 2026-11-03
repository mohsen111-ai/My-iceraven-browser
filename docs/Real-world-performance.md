# Real-world browsing investigation

Baseline: `bf68af0d5f` on `performance-test-screen`, Firefox/GeckoView 159,
optimized `forkRelease`. The working synthetic Performance Test is preserved.
This investigation used source inspection and executable host-side workload
checks. No Infinix device trace, real-site waterfall, or Chrome timing was
available. The changes below remove demonstrated app overhead; they do not
establish a percentage improvement in page loading or explain the whole Chrome gap.

## What the architecture shows

| Path | Ownership and finding | Decision |
| --- | --- | --- |
| Navigation | Fenix use cases/BrowserStore create or reuse a GeckoEngineSession. Gecko's navigation delegate calls AppRequestInterceptor for app links, about:home and error handling. Gecko owns DNS/TLS/HTTP, caching and document processing. | Preserve interception and security checks. Measure app dispatch delay separately from server response and content work. |
| Page images | Page subresources, image decode, layout and WebRender/compositing are inside Gecko, not a Fenix image-loader loop. Fetch-priority support is already enabled in Core. | No speculative image-cache, HTTP-connection, decoder, process or rendering preference changes. Use a Networking profile/waterfall and Graphics profile to distinguish late downloads from late decoding/painting. |
| Touch/scroll | GeckoView handles webpage input/compositing. Android Main also handles toolbar/layout, store observers and session/view attachment. ScrollAwareThumbnailFeature only updates two coordinates per scroll; it requests capture when leaving the foreground, not every scroll frame. | Remove demonstrated no-op work on Main; retain gesture handling and thumbnails. A native UI FrameMetrics score is not webpage-scroll latency. |
| Tab switching | GeckoEngineView.render checks session identity before reattaching and releases the previous session without closing it. Reload covers/thumbnails and restoration can add visible delay. | No blanket session reload/recreation found to remove. Compare already-live tabs separately from restored/evicted tabs. |
| Startup | Fenix initializes Nimbus, Gecko and WebExtension support in dependency order, overlaps some initialization, then waits for application-services network setup. Session restoration and background services also run. | The synchronous dependency barrier is real but its device cost is unmeasured. Do not reorder it or delay extension initialization without traces; doing so could change settings or allow initial requests before blockers are ready. |
| Diagnostics | BrowserStore and AppStore installed ProfileMarkerMiddleware unconditionally; every action launched a Main coroutine, even with profiling off. ProfilerMarkerFactProcessor also posted off-main implementation-detail events when inactive. | Skip these two consumers' marker work while profiling is inactive or absent. |
| Downloads | DownloadService extends AbstractFetchDownloadService, uses Core.client (GeckoViewFetchClient/GeckoWebExecutor), and can reuse the original Gecko response. Its IO coroutine copies streams in 32 KiB chunks, throttles progress updates to 750 ms, and writes through DefaultDownloadFileWriter to MediaStore/SAF or a legacy file. | No justified throughput tuning found. Measure actual file transfers separately from webpage resources and the Performance Test's Android HTTPS probe. Preserve resume, cookies/private flags, destination permissions, integrity/error handling and notifications. |

Relevant implementation files: `components/Core.kt`, `FenixApplication.kt`,
`AppRequestInterceptor.kt`, `browser/ScrollAwareThumbnailFeature.kt`,
`downloads/DownloadService.kt` under `app/src/main/java/org/mozilla/fenix`, plus
the `browser/engine-gecko`, `browser/thumbnails`, `feature/session`, and
`feature/downloads` components in the pinned `android-components` submodule.

## Implemented change and evidence

`ProfileMarkerMiddleware` still calls the next middleware/reducer for every
action. It then checks profiling state before creating a coroutine. Active
markers continue to run on the supplied Main dispatcher. The check is performed
per action, so starting/stopping profiling does not require restarting the app.

`ProfilerMarkerFactProcessor` checks profiling state before inspecting loopers,
reading profiler time, or posting a Handler task. It only skips its own marker
consumer; it does not discard facts for telemetry or other processors. Active
off-main markers retain the original event timestamp and Main-thread delivery.

The pinned Gecko implementation's `ProfilerController.isProfilerActive()` calls
`GeckoJavaSampler.isProfilerActive()`, a documented nonblocking AtomicReference
read. This early check is safe off Main; adding Java markers still stays on Main.
See the [Firefox 159 source](https://github.com/mozilla-firefox/firefox/blob/FIREFOX-ANDROID_159_0_RELEASE/mobile/android/geckoview/src/main/java/org/mozilla/gecko/GeckoJavaSampler.java).

Deterministic host-side workload checks against the real app implementations:

| Workload, profiler off | Baseline queued marker tasks | Patched queued marker tasks |
| --- | ---: | ---: |
| 10,000 store actions | 10,000 | 0 |
| 10,000 background implementation-detail facts | 10,000 | 0 |

These counts show removed coroutine/Runnable allocation and dispatch work, not
10,000 events observed on a website or an on-phone millisecond saving. There is
still one cheap state check per action/event. Benefit depends on actual event
rate and contention on Android Main. No direct network or image-decoding speedup
is claimed. Instrumented runs retain marker overhead, so use **unprofiled** runs
for before/after timings and separate profiled runs for attribution.

Run the focused regression suite with Java 21 and a Java 17 toolchain installed:

```sh
./gradlew -p automation/iceraven/browsing-tests clean test --no-daemon --max-workers=1
```

It compiles the real middleware, fact processor, Store, and relevant component
interfaces. Handler/Looper are mocked and a one-entry fixture replaces the
Android build's generated Component identifier enum; it does not run Gecko or
Android. Eleven tests cover inactive/null cases, 10,000-event queue counts,
reducer delivery, active Main/background markers, timestamps, and enabling
profiling later. The new lightweight Browsing regression checks workflow runs
them independently. The existing optimized APK workflow, five calculation tests,
fixture/diagnostics, R8, browser preferences and WebExtensions remain unchanged.

## Verify on the Infinix GT 30 Pro / Android 16

1. Record the current build's results before installing the new APK. Compare
   **old Iceraven vs new Iceraven** to attribute this change; use current Chrome
   as a separate reference. Keep uBlock Origin and Dark Reader settings, filter
   lists, site permissions, privacy protections and tab count identical between
   Iceraven builds. Chrome has a different extension/content workload, so its
   result is not an engine-only comparison. Ephemeral APK signatures differ
   between workflow runs; back up browser data before any required reinstall.
2. Use the same Wi-Fi, account/login state, viewport, brightness, refresh rate
   and battery mode. Let the phone cool; avoid concurrent downloads/updates.
   Record browser versions and extension versions. Do not change about:config.
3. Select two image-heavy sites that reproduce the problem and a simple control
   page. Keep the exact URLs/actions constant. Alternate browser order and run
   at least five paired trials. Report median and range, not just the fastest run.
4. Separate initial navigation from warm reload/revisit. A force-stop is a cold
   **process**, not a cold HTTP cache. Do not clear all app data to reset a cache.
   Record which cache condition you used and apply it equally to both browsers.
5. For each URL record: tap/Go to first visible content, tap/Go to the chosen
   prominent image being visible, and tap/Go to usable interaction. Use the same
   visual landmark. Network `load` and the progress bar are not image-display
   completion; lazy-loaded images may only start after scrolling. Screenshots or
   screen recording help locate landmarks but add overhead: keep the method
   identical and collect traces in separate runs.
6. After loading, perform the same 10-second scroll and fixed sequence of links,
   back navigation and switches between three already-loaded tabs. Repeat cold
   launch and restored-tab cases separately. Check Android's actual refresh
   interval (120 Hz gives about 8.33 ms, 60 Hz about 16.67 ms), missed frames and
   input-to-presentation latency. The Performance Test's native marker animation
   cannot substitute for this.
7. Confirm uBO blocking/cosmetic filters/scriptlets and Dark Reader styling on
   the same pages, in normal and permitted private tabs, plus video, forms,
   back/forward, tab restore and downloads. Run the existing Performance Test
   twice to check it still finishes and exports diagnostics.

Suggested results columns:

```text
build,browser,url,extensions,cache_condition,trial,refresh_hz,first_content_ms,chosen_image_ms,usable_ms,scroll_missed_frames,live_tab_switch_ms,notes
```

## Collect evidence for remaining delays

The app already includes Firefox Profiler; no new always-on recorder is needed.
In Settings → About Iceraven, tap the logo five times, then return to Settings
and use Start profiler. Use **Networking** for navigation/transfers and
**Graphics** for image decoding/scrolling. Capture one short reproduction per
profile, ideally 15–30 seconds. Stop profiling between trials.

For a local capture over authorized USB debugging while the profiler is running:

```sh
adb shell content read --uri content://io.github.forkmaintainers.iceraven.profiler/stop-and-upload > profile.gz
```

Despite that legacy URI name, the current ProfilerProvider streams gzip data to
ADB; it does not upload it. The UI's stop-and-save path **does upload** via
ProfilerUtils, so use ADB when a local file is preferred. Confirm the installed
package name for other build variants. Profiles can contain URLs/screenshots;
review before sharing. Load the file locally in Firefox Profiler without using
its Publish action. For Android Main scheduling and frame presentation, capture
a separate Android System Tracing/Perfetto recording with scheduling, frequency,
graphics/view/input and SurfaceFlinger frame-timeline data; the optimized app
already declares `profileable android:shell="true"`.

Interpretation:

* Long Android Main work before Gecko begins navigation: inspect Fenix/store,
  intent/interceptor, session attach and GC stacks. Separate runnable-but-not-
  scheduled time from CPU work; thermal limits and other apps can cause both.
* Early image requests with long DNS/connect/TTFB/download time: investigate
  server/CDN/network/cache/protocol differences. Capture the request waterfall
  using desktop Firefox's remote debugging (`about:debugging`) and Chrome's
  equivalent (`chrome://inspect`). DevTools cache disabling is a separate test
  condition, not a default optimization.
* Images received early but displayed late: inspect TaskController/JxlDecode,
  GeckoMain layout/style/script, WebRender and compositor work. Extension
  content scripts and website scripts can both consume content-process time.
  Identify stacks before attributing it to Gecko itself. An optional temporary
  per-site Dark Reader toggle can isolate its contribution; restore it afterward
  and keep normal-extension results as the primary comparison.
* Smooth compositor motion with slow toolbar/taps: inspect Android Main.
  Late content frames with a responsive toolbar: inspect content/compositor,
  image decode and GPU scheduling. Engine problems require a Gecko fix or
  upgrade, not speculative Fenix preferences.

### Actual downloads: separate experiment

Choose one trusted direct file of known size/hash (for example 50–100 MiB) and
use the **same URL** in each browser's normal download UI. Test the default
local Downloads destination first; test a custom SAF provider separately.
Record tap-to-first-progress, active transfer time, total tap-to-complete time,
exact saved bytes and file hash. Compute decimal Mbps as
`saved_bytes * 8 / transfer_seconds / 1_000_000`; report end-to-end time too.
Avoid mixing server wait/confirmation-dialog time with sustained throughput.
Alternate browsers, repeat, and check the server has no rate/account limits.

Capture a Networking profile during one transfer, plus an Android trace for IO
threads/disk scheduling if the network completes before the file becomes ready.
Compare default Downloads against the **same** custom destination. Test pause,
resume, cancellation, offline failure and reopening the file. The existing
10 MiB Performance Test uses HttpsURLConnection and discards bytes: its Mbps is
neither the Gecko download path nor disk throughput. Do not use it to conclude
that real browser downloads are fast or slow.

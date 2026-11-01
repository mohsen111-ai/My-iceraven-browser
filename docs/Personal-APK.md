# Build a personal development APK from a phone

The **Development APK** workflow runs only when requested manually. It builds
`:app:assembleForkDebug` on a GitHub-hosted Ubuntu 24.04 runner and uploads APKs
for arm64-v8a, armeabi-v7a, and x86_64. No signing secrets, Android Studio, local
computer, release publication, or Play account are required.

This uses the repository's existing development variant, including its existing
application ID (`io.github.forkmaintainers.iceraven.debug`), icons, resources,
and behavior. The preparation script adapts standalone build paths and generates
required files; it does not apply the existing CI script's search-engine,
toolbar, or string substitutions. No browser source changes are committed.

## Commit from Android

1. Sign into GitHub in your phone's browser and open
   <https://github.com/mohsen111-ai/My-iceraven-browser>. Enable **Desktop site**
   in the browser if GitHub hides editing or workflow controls.
2. Select your repository's default branch (expected to be `iceraven`). Use
   **Add file → Create new file** for each of the three files supplied with this
   change. Enter the complete path, paste its contents, and select **Commit
   changes**. Commit directly to the default branch if permitted. Alternatively,
   create a branch and merge a pull request into the default branch.
   - `.github/workflows/development-apk.yml`
   - `automation/iceraven/prepare-development-build.sh`
   - `docs/Personal-APK.md` (this guide)
3. Use a commit message such as `Add manual development APK build`.
   The script needs no executable-bit change: the workflow invokes it with Bash.
   A manual workflow must exist on the default branch before its Run workflow
   control appears. Existing push-triggered CI may also run when committing to
   `iceraven`; that separate release-style CI still expects signing secrets.

## Trigger, download, and install

1. Open the repository's **Actions** tab. Enable workflows if GitHub asks.
2. Select **Development APK → Run workflow**, select `iceraven` (or the branch
   containing these changes), then press **Run workflow**.
3. Open the run and watch its steps. A successful upload produces
   **Artifacts → iceraven-development-apks**. Tap it to download the ZIP and
   extract it with your phone's file manager.
4. Most modern Android phones need the APK containing `arm64-v8a` in its name.
   Older 32-bit ARM phones need `armeabi-v7a`; `x86_64` is mainly for emulators.
   The repository currently requires Android 8.0/API 26 or later.
5. Tap the matching APK and permit your browser/file manager to install unknown
   apps when Android requests it. These are complete per-architecture APKs, not
   a set of split APKs that must be installed together.

The APK is automatically signed with a generated development key. Fresh runners
can generate different keys, so later runs are not guaranteed to install as
updates. The existing development variant also shares a sharedUserId with
release Iceraven: a differently signed installed Iceraven can cause an install
conflict even though the package names differ. Back up browser data before
removing an installation to resolve a signature conflict; uninstalling deletes
its local data. This workflow does not change the app ID or sharedUserId to
avoid that conflict. Stable signing for ongoing personal updates is separate
work and is not configured here.

## Toolchain, resources, and validation

The workflow uses Java 21 to run Gradle/Android tooling and Java 17 for the pinned
compilation toolchain, the checksum-verified Gradle 9.8.1 wrapper, SDK 37.2,
build tools 36.0.0, NDK 29.0.14206865, and Python 3.12 with Glean parser 20.x.
The SDK and NDK pins come from this checkout's Gradle/submodule configuration
and existing Iceraven automation. Update them together if those pins change.

Expect several GB of downloads and substantial disk/memory use. The build uses
two workers and a 3 GB Gradle heap, with a 120-minute timeout. Standard public
Linux runners typically have 4 CPUs and 16 GB RAM; private repository runners
can have only 2 CPUs and 7 GB RAM. Available disk space and cache hits vary.
Large dependency downloads, unavailable pinned artifacts, disk exhaustion, or
memory pressure may still fail the run. Consult the failed step's logs before
changing pins or increasing memory. No emulator or device tests run here.

Standard GitHub-hosted Actions usage is generally free for public repositories.
Private repositories consume the account's included minutes, then may incur
charges if paid usage is enabled. Dependency caching and artifact storage also
count toward GitHub's applicable quotas; artifacts expire after seven days.
Check **Settings → Billing and licensing** for current quotas and spending
controls. This workflow requests no larger paid runner.

Only static workflow/script checks were performed in Codex. No Android build
was run there. A successful Actions run is required to establish that APKs can
be built; installing and testing on your phone is still required to confirm
runtime behavior. If a step fails, share its error log, not any secret values.

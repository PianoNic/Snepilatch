# Notes for Claude (and humans)

Living notes about non-obvious things in this repo. Keep this short and only add things that are noteworthy enough to matter for future work. Read [ARCHITECTURE.md](ARCHITECTURE.md) first for the layout.

## Native TLS binary lives in `jniLibs/` and must match KotifyClient

KotifyClient's JAR is code only; the JNA-loaded `libtls_client_go.so` ships separately from [`PianoNic/kotlin-tls-client-natives`](https://github.com/PianoNic/kotlin-tls-client-natives/releases/latest). We commit one `.so` per ABI under `src/app/src/main/jniLibs/<abi>/` so local debug builds work without a network step.

CI (`.github/workflows/build-and-release.yml`) re-fetches the latest natives release on every release build, overwriting whatever is in the tree, so production APKs always ship matching binaries even if the committed copies have drifted.

**When you bump KotifyClient locally**, refresh the four ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) from the natives release matching the kotlin-tls-client version your new KotifyClient depends on. Otherwise you'll get `UnsatisfiedLinkError` for any FFI symbol added since your last refresh (e.g. `wsOpen` after the WebSocket migration).

`libjnidispatch.so` (JNA's own dispatcher) sits next to `libtls_client_go.so` in each ABI directory. KotifyClient's shadow jar bundles JNA's Java classes but not its per-ABI native; Android's W^X rules forbid extracting `.so` files from inside a jar at runtime, so the file must be on disk in `jniLibs/`. CI re-extracts the latest from the JNA AAR alongside the natives step; locally, the committed copies are fine unless KotifyClient bumps its JNA dependency.

## Verification before merging anything

```sh
cd src
./gradlew :app:assembleDebug
./gradlew :app:testProdDebugUnitTest
./gradlew detekt
```

All three must be green. The test rig + detekt baseline catch most regressions before they reach a phone.

## Build flavors

There are two product flavors on the `environment` dimension: **prod** (`ch.snepilatch.app`, "Snepilatch", the one that ships) and **dev** (`ch.snepilatch.app.dev`, "Snepilatch Dev", `-dev` versionName suffix). They differ only in identity, not behaviour, so a dev build installs side-by-side with the production app.

Variant task names are flavor-qualified: `assembleProdDebug` / `assembleDevDebug`, `testProdDebugUnitTest` / `testDevDebugUnitTest`. The umbrella `assembleDebug` still builds both; there is no `testDebugUnitTest` anymore; use `:app:testProdDebugUnitTest` or `:app:test`. The dev `app_name` override lives in `app/src/dev/res/values/strings.xml`. The release pipeline builds `assembleProdRelease`.

## Versioning & releases

**The git tag is the source of truth for a release's version, not `build.gradle.kts`.** `versionCode`/`versionName` in `src/app/build.gradle.kts` read from `-PappVersionCode`/`-PappVersionName` Gradle properties and fall back to the checked-in literals only when those aren't passed — that fallback is what local/dev builds get, and what `nightly.yml` reads as the current base version. The actual build always goes through **`build-apk.yml`** (a reusable `workflow_call` workflow taking a `tag` input), which derives both from that tag:

- **`versionName`** = the tag with its leading `v` stripped (`v2.9.80` → `2.9.80`, `v2.9.80-nightly.3` → `2.9.80-nightly.3`).
- **`versionCode`** = `major*1e8 + minor*1e5 + patch*100 + ordinal`, where `ordinal` is `99` for a full release or the nightly number (capped at 98) for a `-nightly.N` tag. This keeps a full release always outranking every nightly of the same core version, and later nightlies outranking earlier ones — required since Android's `versionCode` must strictly increase for an update to install over what's there.

**`build-apk.yml` is the only place the APK actually gets built and uploaded.** Both callers pass it an explicit tag, so there's no event-type or ref-shape guessing about whether a release exists to upload to — being called at all means yes:

- **`build-and-release.yml`** — triggered by `release: published` (a human publishing a release through the GitHub UI), or `workflow_dispatch` with a `tag` input to (re)build an existing release manually. Calls `build-apk.yml` with that tag.
- **`nightly.yml`** — see below. Calls `build-apk.yml` directly as a job (`uses: ./.github/workflows/build-apk.yml`), not via a dispatched/triggered second workflow run.

**To cut a stable release:** bump `versionName`/`versionCode` in `build.gradle.kts` as usual (a manual `"Bump version to X.Y.Z"` commit, same as always — this keeps the local/dev fallback and the nightly base current even though it's no longer what a *tagged* release actually ships with), then publish a GitHub Release tagged `vX.Y.Z`.

**Nightlies are cut on a schedule**: the **"Cut a Nightly Build"** workflow (`nightly.yml`) runs five times a day (cron, UTC, see the workflow for the exact times) plus `workflow_dispatch` for a manual cut. The cron times target 05:00, 10:00, 15:00, 20:00 and midnight Swiss summer time; since cron has no timezone, the job also checks the Swiss clock itself and refuses to cut between 01:00 and 04:59, which keeps the window right through a DST change. A manual dispatch is never blocked by that guard. It skips entirely if `main` hasn't moved since the last nightly tag, so a quiet stretch produces nothing. Otherwise it takes its base version from **release-drafter's draft** (the highest-versioned draft, since the drafter can leave more than one behind), finds the next free `-nightly.N` suffix for that base (scanning existing tags), tags and pushes it, publishes it as a GitHub **prerelease**, then calls `build-apk.yml` as a normal job dependency. **There is no fallback**: if the releases API cannot be read, or no draft is found, or the resolved base is older than the newest existing nightly line, the job fails instead of guessing. Guessing is what cut `v3.0.0-nightly.4` on a four month old line during the GitHub outage on 2026-08-17, after `v3.1.0-nightly.8`. Not cutting is a safe outcome; a forked version line is not. **It never publishes the draft itself** — that stays a deliberate, human step, and publishing the draft *as* a nightly forks the version line and spends the changelog the next real release needs. Earlier this dispatched a second workflow run via the API to work around `GITHUB_TOKEN`-published releases not firing `release: published` (GitHub's anti-recursion safeguard) — that needed an extra `actions: write` permission and left the upload step guessing whether it should run. A `workflow_call` sidesteps the whole problem: it's not a triggered event at all, just a job in the same run. A `concurrency` group serializes runs so a manual dispatch landing close to a scheduled run can't race it.

**In-app update channel:** `AppSettings.updateChannel` (`Account` → About) is `stable` (default, only surfaces full releases) or `nightly` (also surfaces prereleases, with an explicit "install at your own risk" warning in `UpdateDialog`). `UpdateService` queries the `/releases` list endpoint (not `/releases/latest`, which never returns a prerelease) and picks the first non-draft entry matching the channel filter — GitHub returns that list newest-first. Version comparison (`UpdateService.isNewerVersion`) is semver-prerelease-aware: `2.9.80-nightly.3` ranks *below* `2.9.80`, not above it — a naive positional dot-parts comparison would get this backwards.

**The draft is now the source of the version.** `release-drafter` resolves the next version from the labels on everything merged since the last published release (`feature`/`enhancement` → minor, `bug` → patch), and `nightly.yml` mirrors it. Prereleases don't disturb that resolution (`include-pre-releases` is off), so nightlies can't move the line they're derived from. Two consequences worth knowing: a PR's labels decide the next version, and `build.gradle.kts`'s `versionName` is now only the local/dev fallback — it no longer steers nightlies.

**Known wart:** the drafter runs on both `push` to `main` and `pull_request: closed`, so a squash-merge fires it twice and usually leaves *two* identical drafts. Harmless for the version (nightly sorts and takes the highest), but delete the spare before publishing a release.

## Test rig

`PlaybackTestRig` (`app/src/test/.../viewmodel/PlaybackTestRig.kt`) lets you exercise extracted handler methods without a real `PlayerConnect`. It mocks `MusicPlaybackService.instance` via mockk and swaps `Dispatchers.Main` for an unconfined test dispatcher.

When fixing a playback bug, add a test that would have caught it.

## ViewModel coroutine helpers

Two helpers in `PlaybackViewModel` cover most IO launches:

- **`launchWithSession("tag") { sess -> ... }`**: null-checks the session, runs on `Dispatchers.IO`, catches and logs against the tag.
- **`launchWithPlayer("tag") { pc -> ... }`**: same shape, but for transport commands that need `PlayerConnect`.

Prefer these over hand-rolled `viewModelScope.launch(Dispatchers.IO) { try { val s = session ?: return@launch ... } catch ... }` blocks.

## Session ownership

There is exactly one Kotify `Session`, `PlayerConnect`, `SpotifyPlayback`, and `SpotifyCdnResolver` in the entire process. They live in `playback/SessionHolder.kt` (an `object`). The ViewModel writes them during `initialize()` and reads them through property delegates. The service and the headphone receiver read them directly.

Never store the session on Activity-scoped objects. The headphone-cold-launch path needs them when no Activity exists.

## Playback ordering

The Kotify dealer fires `onState`, `onTrackChange`, `onPlay`/`onPause`, and `onPlaybackId` independently and not always in a stable order. In particular, **`onPlaybackId` and `onTrackChange` race** for the same transition: either can fire first. Any code that pairs a file id with a track URI must tolerate both orderings without losing the pre-resolved cache.

The pre-resolved cache (`nextCdnUrl` + `nextCdnFileId` from `onNextPlaybackId`) is what makes skip-next instant. Don't break it.

## ViewModel split strategy

`PlaybackViewModel` is large (~3k lines). We extract feature-scoped ViewModels incrementally. **Extracted so far: `SearchViewModel`, `LyricsViewModel`, `DetailViewModel`, `LibraryViewModel`, `HomeViewModel`** (Detail/Library built on the shared `Navigator`; Home/Library load their feed in `init`). Each lands with its own tests. That's the clean feature set; what's left on `PlaybackViewModel` is playback, the playback-coupled features (Queue/Account/Devices), and cross-cutting settings/theme.

**Persisted user settings live in the process-scoped `AppSettings` store** (like `SessionHolder`/`Navigator`): audio source, content region, language, lyrics-anim direction, notification buttons, player gradient, canvas toggle, plus `load(context)`, `effectiveRegion()`, and the setters (incl. side effects: notification push to the service, locale change). `PlaybackViewModel` reads `AppSettings` in the stream-resolution path (`preferredAudioSource`, `effectiveRegion()`); the UI reads/writes it directly. `canvasUrl` (the current track's video URL: playback state, not persisted) stays on `PlaybackViewModel`, whose thin `setCanvasEnabled` wraps `AppSettings.setCanvasEnabled` to also clear it.

**The album-art accent palette lives in the process-scoped `ThemeController`** (`themeColors` + `updateFromArt`, fed by PlaybackViewModel on track change; read by the ~12 screens that tint themselves).

**Navigation is a process-scoped `Navigator`** (like `SessionHolder`): it owns `currentScreen` + the back stack + `navigateTo`/`navigateToTab`/`goBack`. `PlaybackViewModel` delegates to it and `reset()`s it on construction. Feature VMs navigate through `Navigator` directly; that's what unblocked the Detail extraction. For a feature VM whose openers must also be reachable from `PlaybackViewModel`'s own code (deep links, playback-context bridges) or from non-composable UI builders, add a tiny process-scoped router object next to the VM (see `DetailRoutes`): the live VM registers itself in `init` and the callers hop through it, so no one holds a cross-VM reference. A screen (normally Home) is always composed before any deep link is processed, so a VM is always registered in time.

Pattern (proven by `SearchViewModel`/`LyricsViewModel`):
1. Move the feature's state + methods into a new `<Feature>ViewModel : SessionViewModel("<Tag>")`.
2. `SessionViewModel` (the base for all five feature VMs) provides `launchWithSession(op) { sess -> }` and `launchWithSessionLoading(op, loadingFlag) { sess -> }`; both null-check `SessionHolder.session`, rethrow cancellation, and log against the tag. Don't re-roll these. `PlaybackViewModel` is NOT a `SessionViewModel` (it owns the session lifecycle and needs player-scoped launches too).
3. A screen can hold **both** ViewModels at once: obtain the feature VM in the body with `val featureVm: <Feature>ViewModel = viewModel()`. `LyricsScreen` reads playback/transport/theme from `PlaybackViewModel` and lyrics content from `LyricsViewModel` side by side. The feature VM doesn't need to own the whole screen.
4. Pass the inputs the feature needs down from the screen (e.g. `lyricsVm.fetch(track.uri)`), rather than having the feature VM reach back into `PlaybackViewModel` state.

**What makes a feature safe to extract now vs. what's blocked:**
- Clean = the feature reads the session, writes its own state, and navigates through `Navigator`. `Lyrics` needed no navigation at all; `Detail` navigates via `Navigator` and exposes `DetailRoutes` for the `PlaybackViewModel`/non-composable callers.
- **`Account` and `Devices` are NOT clean extractions; leave them on `PlaybackViewModel`.** They're playback-coupled: `AccountInfo.isPremium` gates audio-source logic inside the VM, and `activeDeviceName` is written from the playback state handler (`updatePlaybackFromState`) while `loadDevices`/`transferPlayback` need `PlayerConnect`. Extracting them would drag playback state across a VM boundary, the same reason we don't extract playback itself.
- **`LibraryViewModel`** took the clean part: the library list + pagination + `createPlaylist`/`removeFromLibrary`. It loads in `init` (the old eager load lived in `PlaybackViewModel.initialize`, which runs before any composable exists) and reads `username` from `SessionHolder`. The snackbar-emitting `followArtist`/`savePlaylist` and the add-to-playlist picker stayed on `PlaybackViewModel` (they'd have needed the snackbar channel + a router for non-composable callers); "add external content to the library" is a separable concern from browsing it.

Don't try to extract playback. The handler-extraction + test rig pattern (see `RemotePlayPauseHandlerTest`) is the safer route for that area.

## KotifyClient is a local jar

`app/libs/KotifyClient.jar` is the obfuscated jar from `../KotifyClient`, gitignored. To rebuild after a Kotify change (paths are relative to this repo root):

```sh
cd ../KotifyClient
./gradlew obfuscate
cp build/libs/KotifyClient-obfuscated.jar ../Snepilatch/src/app/libs/KotifyClient.jar
```

**Symptom of a stale jar:** `:app:compileProdDebugKotlin` fails with a wall of unresolved references in `PlaybackViewModel.kt` (`onAd`, `onSeek`, `artistNames`, `passthroughUrl`, `saveToLibrary`, wrong `playTrack` arity). That is the committed jar lagging the app source; rebuild it with the recipe above. It is not a bug in whatever file you were editing.

## Logging

`LokiLogger` posts structured logs to a Loki endpoint when `loki.endpoint` is set in `local.properties` (gitignored). Without it, logs go to `Log.i/d/e` only.

## Conventions

- Branch: `feature/<issue#>_PascalCase` or `fix/<issue#>_PascalCase`. Always a GitHub issue first.
- Commit subject: short imperative, no AI attribution.
- Squash merge.
- PR labels: `bug`, `enhancement`, `refactor`, `stale`.

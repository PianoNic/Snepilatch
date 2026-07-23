# Notes for Claude (and humans)

Living notes about non-obvious things in this repo. Keep this short and only add things that are noteworthy enough to matter for future work. Read [ARCHITECTURE.md](ARCHITECTURE.md) first for the layout.

## Native TLS binary lives in `jniLibs/` and must match KotifyClient

KotifyClient's JAR is code only — the JNA-loaded `libtls_client_go.so` ships separately from [`PianoNic/kotlin-tls-client-natives`](https://github.com/PianoNic/kotlin-tls-client-natives/releases/latest). We commit one `.so` per ABI under `src/app/src/main/jniLibs/<abi>/` so local debug builds work without a network step.

CI (`.github/workflows/build-and-release.yml`) re-fetches the latest natives release on every release build, overwriting whatever is in the tree, so production APKs always ship matching binaries even if the committed copies have drifted.

**When you bump KotifyClient locally**, refresh the four ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) from the natives release matching the kotlin-tls-client version your new KotifyClient depends on. Otherwise you'll get `UnsatisfiedLinkError` for any FFI symbol added since your last refresh (e.g. `wsOpen` after the WebSocket migration).

`libjnidispatch.so` (JNA's own dispatcher) sits next to `libtls_client_go.so` in each ABI directory. KotifyClient's shadow jar bundles JNA's Java classes but not its per-ABI native — Android's W^X rules forbid extracting `.so` files from inside a jar at runtime, so the file must be on disk in `jniLibs/`. CI re-extracts the latest from the JNA AAR alongside the natives step; locally, the committed copies are fine unless KotifyClient bumps its JNA dependency.

## Verification before merging anything

```sh
cd src
./gradlew :app:assembleDebug
./gradlew :app:testProdDebugUnitTest
./gradlew detekt
```

All three must be green. The test rig + detekt baseline catch most regressions before they reach a phone.

## Build flavors

There are two product flavors on the `environment` dimension: **prod** (`ch.snepilatch.app`, "Snepilatch" — what ships) and **dev** (`ch.snepilatch.app.dev`, "Snepilatch Dev", `-dev` versionName suffix). They differ only in identity, not behaviour, so a dev build installs side-by-side with the production app.

Variant task names are flavor-qualified: `assembleProdDebug` / `assembleDevDebug`, `testProdDebugUnitTest` / `testDevDebugUnitTest`. The umbrella `assembleDebug` still builds both; there is no `testDebugUnitTest` anymore — use `:app:testProdDebugUnitTest` or `:app:test`. The dev `app_name` override lives in `app/src/dev/res/values/strings.xml`. The release pipeline builds `assembleProdRelease`.

## Test rig

`PlaybackTestRig` (`app/src/test/.../viewmodel/PlaybackTestRig.kt`) lets you exercise extracted handler methods without a real `PlayerConnect`. It mocks `MusicPlaybackService.instance` via mockk and swaps `Dispatchers.Main` for an unconfined test dispatcher.

When fixing a playback bug, add a test that would have caught it.

## ViewModel coroutine helpers

Two helpers in `SpotifyViewModel` cover most IO launches:

- **`launchWithSession("tag") { sess -> ... }`** — null-checks the session, runs on `Dispatchers.IO`, catches and logs against the tag.
- **`launchWithPlayer("tag") { pc -> ... }`** — same shape, but for transport commands that need `PlayerConnect`.

Prefer these over hand-rolled `viewModelScope.launch(Dispatchers.IO) { try { val s = session ?: return@launch ... } catch ... }` blocks.

## Session ownership

There is exactly one Kotify `Session`, `PlayerConnect`, `SpotifyPlayback`, and `SpotifyCdnResolver` in the entire process. They live in `playback/SessionHolder.kt` (an `object`). The ViewModel writes them during `initialize()` and reads them through property delegates. The service and the headphone receiver read them directly.

Never store the session on Activity-scoped objects. The headphone-cold-launch path needs them when no Activity exists.

## Playback ordering

The Kotify dealer fires `onState`, `onTrackChange`, `onPlay`/`onPause`, and `onPlaybackId` independently and not always in a stable order. In particular, **`onPlaybackId` and `onTrackChange` race** for the same transition: either can fire first. Any code that pairs a file id with a track URI must tolerate both orderings without losing the pre-resolved cache.

The pre-resolved cache (`nextCdnUrl` + `nextCdnFileId` from `onNextPlaybackId`) is what makes skip-next instant. Don't break it.

## ViewModel split strategy

`SpotifyViewModel` is large (~3k lines). We extract feature-scoped ViewModels incrementally. **Extracted so far: `SearchViewModel`, `LyricsViewModel`, `DetailViewModel`** (the last built on the shared `Navigator`). Each lands with its own tests.

**Navigation is a process-scoped `Navigator`** (like `SessionHolder`): it owns `currentScreen` + the back stack + `navigateTo`/`navigateToTab`/`goBack`. `SpotifyViewModel` delegates to it and `reset()`s it on construction. Feature VMs navigate through `Navigator` directly — that's what unblocked the Detail extraction. For a feature VM whose openers must also be reachable from `SpotifyViewModel`'s own code (deep links, playback-context bridges) or from non-composable UI builders, add a tiny process-scoped router object next to the VM (see `DetailRoutes`): the live VM registers itself in `init` and the callers hop through it, so no one holds a cross-VM reference. A screen (normally Home) is always composed before any deep link is processed, so a VM is always registered in time.

Pattern (proven by `SearchViewModel`/`LyricsViewModel`):
1. Move the feature's state + methods into a new `<Feature>ViewModel : ViewModel()`.
2. It reads `Session` from `SessionHolder` and rolls its own small `launchWith…` helper (the ones on `SpotifyViewModel` are private; copy the shape).
3. A screen can hold **both** ViewModels at once — obtain the feature VM in the body with `val featureVm: <Feature>ViewModel = viewModel()`. `LyricsScreen` reads playback/transport/theme from `SpotifyViewModel` and lyrics content from `LyricsViewModel` side by side. The feature VM doesn't need to own the whole screen.
4. Pass the inputs the feature needs down from the screen (e.g. `lyricsVm.fetch(track.uri)`), rather than having the feature VM reach back into `SpotifyViewModel` state.

**What makes a feature safe to extract now vs. what's blocked:**
- Clean = the feature reads the session, writes its own state, and navigates through `Navigator`. `Lyrics` needed no navigation at all; `Detail` navigates via `Navigator` and exposes `DetailRoutes` for the `SpotifyViewModel`/non-composable callers.
- **Still on `SpotifyViewModel`:** `Library`, `Account`, `Devices`. These are now unblocked (Navigator exists) — follow the `DetailViewModel` PR as the template. Watch for cross-feature triggers (e.g. "refresh library after `createPlaylist`"): route them through a small router object like `DetailRoutes`, or inline them in the caller. `removeFromLibrary` stayed on `SpotifyViewModel` with the other library-mutation actions.

Don't try to extract playback. The handler-extraction + test rig pattern (see `RemotePlayPauseHandlerTest`) is the safer route for that area.

## KotifyClient is a local jar

`app/libs/KotifyClient.jar` is the obfuscated jar from `../KotifyClient`, gitignored. To rebuild after a Kotify change (paths are relative to this repo root):

```sh
cd ../KotifyClient
./gradlew obfuscate
cp build/libs/KotifyClient-obfuscated.jar ../Snepilatch/src/app/libs/KotifyClient.jar
```

**Symptom of a stale jar:** `:app:compileProdDebugKotlin` fails with a wall of unresolved references in `SpotifyViewModel.kt` (`onAd`, `onSeek`, `artistNames`, `passthroughUrl`, `saveToLibrary`, wrong `playTrack` arity). That is the committed jar lagging the app source — rebuild it with the recipe above. It is not a bug in whatever file you were editing.

## Logging

`LokiLogger` posts structured logs to a Loki endpoint when `loki.endpoint` is set in `local.properties` (gitignored). Without it, logs go to `Log.i/d/e` only.

## Conventions

- Branch: `feature/<issue#>_PascalCase` or `fix/<issue#>_PascalCase`. Always a GitHub issue first.
- Commit subject: short imperative, no AI attribution.
- Squash merge.
- PR labels: `bug`, `enhancement`, `refactor`, `stale`.

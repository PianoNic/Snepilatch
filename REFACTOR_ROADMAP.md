# Refactor roadmap

A practical, step-by-step plan for the rest of the SpotifyViewModel decomposition. Read [ARCHITECTURE.md](ARCHITECTURE.md) first for the layout and the playback rules. This document is the work plan, not the architecture.

## Current state (April 2026)

```
SpotifyViewModel.kt   2153 lines   (started at 2208 — net −2.5%)
NowPlayingScreen.kt   1117 lines
MusicPlaybackService  711 lines
DetailScreen.kt       710 lines
AccountScreen.kt      692 lines
LyricsScreen.kt       662 lines

Tests passing:        20
Feature ViewModels:   1 of ~13 done (SearchViewModel)
Repositories:         0
DI:                   none (singletons via SessionHolder)
```

What we already have in place:
- Test rig (`PlaybackTestRig`) and the pattern for testing handlers without a real `PlayerConnect`
- `launchWithSession` / `launchWithPlayer` helpers covering the common IO patterns
- detekt + ktlint with a baseline
- Process-scoped `SessionHolder`
- One feature successfully extracted (Search) — this is the **template** for everything else
- Headphone cold-launch + idle notification metadata working

## The plan

Extract one feature at a time, each as its own PR, each landing with at least one test. Order chosen by **smallest blast radius first** so the tooling and pattern stay clean before tackling the larger features.

### Phase 1 — Feature ViewModel extractions

| # | Feature | Est. lines moved | Risk | Why this order |
|---|---|---|---|---|
| 1 | `SearchViewModel` | ~30 | low | **Done** — see PR #201, use as template |
| 2 | `AccountViewModel` | ~60 | low | Pure profile data, no playback contact |
| 3 | `LyricsViewModel` | ~70 | low | Self-contained, only depends on current track URI |
| 4 | `LibraryViewModel` | ~80 | low | Library list + create playlist + follow/unfollow |
| 5 | `HomeViewModel` | ~30 | low | Home feed + loading state |
| 6 | `DetailViewModel` | ~250 | medium | Album / playlist / artist detail with pagination |
| 7 | `QueueViewModel` | ~80 | medium | Reads from playback state, watch ordering |
| 8 | `DevicesViewModel` | ~50 | low | Device list + transfer |
| 9 | `PreferencesViewModel` | ~70 | low | Audio source, language, notification buttons |
| 10 | `ThemeViewModel` | ~40 | low | Album art palette, theme colors |
| 11 | `CanvasViewModel` | ~30 | low | Canvas video URL fetch |

After all 11 are done, `SpotifyViewModel` should be ~600 lines containing only the playback orchestration (init, session lifecycle, the WS callback wiring, cold-start, transport commands, state mirroring). At that point it's a candidate for renaming to `PlaybackViewModel`.

### Phase 2 — Playback decomposition (after Phase 1)

This phase requires the test rig to be fleshed out first (see below). Do not attempt without test coverage.

- Extract `PlaybackStateManager` — owns the ~14 mutable streaming fields (`currentStreamUri`, `latestFileId`, `nextCdnUrl`, etc.) currently scattered in the VM
- Extract `ColdStartCoordinator` — owns `coldStartPending` + the pre/await/play sequence
- Extract `RemoteStateMirror` — owns `updatePlaybackFromState` and the ExoPlayer ↔ cluster reconciliation

### Phase 3 — Cross-cutting

- **Repository pattern.** Wrap KotifyClient API entry points: `PlaylistRepository`, `AlbumRepository`, `ArtistRepository`, `SongRepository`, `UserRepository`, `LyricsRepository`. Each takes a `Session` constructor arg, exposes suspend functions returning UI types (the existing `KotifyMappers` move into the repositories).
- **DI.** Adopt Hilt. The repositories and the `SessionHolder` accessors become `@Singleton`-injected. Each feature ViewModel becomes `@HiltViewModel`.
- **Decompose `NowPlayingScreen`** (1117 lines) into 4-5 child composables (`AlbumArtPanel`, `TitleAndControls`, `ProgressBar`, `LyricsCard`, `QueuePeek`).
- **Decompose `MusicPlaybackService`** (711 lines) — extract `NotificationBuilder`, `MediaSessionWiring`, `DrmStreamLoader`. Each is independently testable.

---

## Recipe: extracting a feature ViewModel

This is the exact recipe used for `SearchViewModel`. Follow it for every Phase 1 step.

### Step 1 — Create the issue and branch

```sh
gh issue create --title "Extract <Feature>ViewModel" --label refactor --body "Move <feature> state and logic out of SpotifyViewModel into a dedicated <Feature>ViewModel. Pattern: see SearchViewModel (PR #201)."
git checkout -b feature/<issue#>_Extract<Feature>ViewModel
```

### Step 2 — Identify what to move

In `SpotifyViewModel.kt`, find:
- Public state flows for the feature (e.g. `val currentTrackLiked = MutableStateFlow(false)`)
- Private state (`var lastLikeCheckUri: String? = null`)
- All public methods that read or mutate that state
- Any helper methods only called from those methods

Use `grep -n` to make sure nothing outside the feature reads the same fields. If it does, you have a coupling — note it for later (you may need to keep an event bus or callback into `SpotifyViewModel`).

### Step 3 — Create the new ViewModel

```kotlin
// app/src/main/java/ch/snepilatch/app/viewmodel/<Feature>ViewModel.kt
package ch.snepilatch.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.playback.SessionHolder
import ch.snepilatch.app.util.LokiLogger
import kotify.session.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class <Feature>ViewModel : ViewModel() {

    private val tag = "<Feature>VM"

    // ----- public state -----
    val someState = MutableStateFlow(...)
    private val _otherState = MutableStateFlow(...)
    val otherState: StateFlow<...> = _otherState

    // ----- private state -----
    private var someJob: Job? = null

    // ----- methods (move from SpotifyViewModel) -----
    fun doSomething(arg: Foo) {
        launchWithSession("doSomething") { sess ->
            // body
        }
    }

    // ----- helpers -----
    private fun launchWithSession(
        label: String,
        block: suspend (Session) -> Unit
    ): Job = viewModelScope.launch(Dispatchers.IO) {
        val sess = SessionHolder.session ?: return@launch
        try {
            block(sess)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LokiLogger.e(tag, label, e)
        }
    }
}
```

**Note on the `launchWithSession` duplication:** for now copy-paste it into each new feature ViewModel. After the third copy, extract it into a `BaseFeatureViewModel : ViewModel()` and have all feature VMs inherit from it.

### Step 4 — Wire the screen

```kotlin
@Composable
fun <Feature>Screen(
    vm: SpotifyViewModel,
    featureVm: <Feature>ViewModel = viewModel()
) {
    val state by featureVm.someState.collectAsState()
    // … existing screen body, swap vm.someState → featureVm.someState
}
```

The `vm: SpotifyViewModel` parameter stays for things the feature still needs from the main VM (like `playTrack`, `togglePlayPause`, theme colors during the transition).

### Step 5 — Delete from `SpotifyViewModel`

Remove the moved state and methods. Replace each section with a one-line breadcrumb:
```kotlin
// <Feature> state was extracted into <Feature>ViewModel — see viewmodel/<Feature>ViewModel.kt
```

### Step 6 — Add tests

Mirror `SearchViewModelTest`:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class <Feature>ViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: <Feature>ViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = <Feature>ViewModel()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `state transition test`() { /* ... */ }
}
```

Cover at minimum:
- Initial state values
- Each public method's happy path (without a real session)
- Edge cases the original code had to handle

### Step 7 — Verify and ship

```sh
cd src
./gradlew :app:assembleDebug :app:testDebugUnitTest detekt
```

All three must pass. Then commit, push, PR, squash-merge.

---

## Patterns to follow

### 1. Read session from `SessionHolder`, never inject

```kotlin
val sess = SessionHolder.session ?: return  // bail if not logged in
```

The session is process-scoped. There is exactly one. Don't pass it around.

### 2. State flows: backing field convention

```kotlin
private val _foo = MutableStateFlow(initialValue)
val foo: StateFlow<Foo> = _foo
```

For simple cases where the flow is also writable from outside (e.g. UI input bound to a query string), expose `MutableStateFlow` directly without the backing field.

### 3. Coroutines: always cancel + relaunch

```kotlin
private var fooJob: Job? = null

fun foo() {
    fooJob?.cancel()
    fooJob = viewModelScope.launch(Dispatchers.IO) { /* ... */ }
}
```

Or use the helpers:
```kotlin
private var fooJob: Job? = null

fun foo() {
    fooJob?.cancel()
    fooJob = launchWithSession("foo") { /* ... */ }
}
```

### 4. Logging: structured tag per ViewModel

Each feature ViewModel has its own `tag` constant: `private val tag = "<Feature>VM"`. Use it consistently in `LokiLogger.i/d/w/e(tag, msg)` calls so the host can filter logs per feature.

### 5. Snackbar / cross-feature events

If a feature ViewModel needs to surface a message (e.g. "Added to playlist"), forward it to `SpotifyViewModel.snackbarMessage`:

```kotlin
class <Feature>ViewModel(private val onSnackbar: (String) -> Unit = {}) : ViewModel()
```

Wire it in the screen:
```kotlin
val featureVm: <Feature>ViewModel = viewModel(factory = viewModelFactory {
    initializer { <Feature>ViewModel(onSnackbar = { msg -> vm.showSnackbar(msg) }) }
})
```

### 6. No new singletons

If a feature needs cross-cutting state, put it in the existing `SessionHolder` or extend it. Do not introduce new top-level `object`s or static `var`s. There should be one source of truth per concept.

---

## Ground rules

1. **Each PR does one thing.** Extracting `LyricsViewModel` is one PR. Adding tests for it is the same PR. Updating `LyricsScreen` is the same PR. Don't bundle two extractions.
2. **Each PR is reverted independently.** If `LibraryViewModel` extraction breaks library loading, the revert PR only undoes that one extraction — playback is unaffected.
3. **Tests ship with the extraction.** No "I'll add tests later." That's how we got into the playback mess the first time.
4. **Run all three checks before merging:**
   ```sh
   ./gradlew :app:assembleDebug :app:testDebugUnitTest detekt
   ```
5. **Squash-merge always.** Branch + issue + label + squash. Branch deleted on merge.
6. **No playback changes in Phase 1.** Phase 1 only touches non-playback code. The playback decomposition is Phase 2 and requires the test rig to be expanded first.

---

## Where to start

Pick #2 from the table: **`AccountViewModel`**. Reasons:
- Smallest of the un-done features (~60 lines)
- Pure profile data (display name, avatar, follower count, playlist count, premium status)
- No playback contact
- The feature is well isolated already — `_account: MutableStateFlow<AccountInfo>` plus 3-4 methods
- A clean win that gets the second extraction in front of you, validating the pattern outside Search

Then `LyricsViewModel`, then `LibraryViewModel`. After three extractions, the helper duplication justifies extracting `BaseFeatureViewModel`.

## What to avoid

- **Do not touch `SpotifyViewModel.initialize`** during Phase 1 — that function owns the bootstrap order and is the most fragile non-playback code.
- **Do not touch `wirePlayerConnectCallbacks`** during Phase 1 — that's where the playback ordering invariants live.
- **Do not introduce a `coordinator` / `mediator` class** — every "coordinator" eventually grows into a mini-monolith. Feature ViewModels should be flat and independent.
- **Do not modularize** (split into multiple Gradle modules) until Phase 1 + 2 + 3 are done. Modularization on top of the current structure adds friction without untangling anything.

## Estimate

- Phase 1: 11 PRs × ~1 hour each = ~11 hours of focused work over 1-2 weeks
- Phase 2: 3 PRs × ~3 hours each (more risk, more tests required) = ~9 hours
- Phase 3: ~10 PRs × ~2 hours each = ~20 hours

Total: roughly **40 hours** to take the codebase from "working but monolithic" to "modular, tested, maintainable." Each hour ships a working APK; nothing is a multi-day big-bang change.

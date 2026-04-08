# Notes for Claude (and humans)

Living notes about non-obvious things in this repo. Keep this short and only add things that are noteworthy enough to matter for future work. Read [ARCHITECTURE.md](ARCHITECTURE.md) first for the layout.

## Verification before merging anything

```sh
cd src
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew detekt
```

All three must be green. The test rig + detekt baseline catch most regressions before they reach a phone.

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

`SpotifyViewModel` is large. The plan is to extract feature-scoped ViewModels incrementally: Search → Library → Account → Lyrics → Detail. Each extraction lands with its own tests.

Pattern:
1. Move the feature's state and methods into a new `<Feature>ViewModel` class.
2. The new ViewModel reads `Session` from `SessionHolder` and uses the same `launchWithSession` shape (or its own equivalent).
3. The screen calls `viewModel<<Feature>ViewModel>()` instead of pulling the field off `SpotifyViewModel`.
4. Cross-feature triggers (e.g. "library refresh after createPlaylist") go through a shared event bus or are inlined in the caller.

Don't try to extract playback. The handler-extraction + test rig pattern (see `RemotePlayPauseHandlerTest`) is the safer route for that area.

## KotifyClient is a local jar

`app/libs/KotifyClient.jar` is the obfuscated jar from `../KotifyClient`, gitignored. To rebuild after a Kotify change:

```sh
cd ../KotifyClient
./gradlew obfuscate
cp build/libs/KotifyClient-obfuscated.jar ../snepilatch/src/app/libs/KotifyClient.jar
```

## Logging

`LokiLogger` posts structured logs to a Loki endpoint when `loki.endpoint` is set in `local.properties` (gitignored). Without it, logs go to `Log.i/d/e` only.

## Conventions

- Branch: `feature/<issue#>_PascalCase` or `fix/<issue#>_PascalCase`. Always a GitHub issue first.
- Commit subject: short imperative, no AI attribution.
- Squash merge.
- PR labels: `bug`, `enhancement`, `refactor`, `stale`.

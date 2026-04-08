# Notes for Claude (and humans)

Living notes about non-obvious things in this repo. Keep this short and only add things that are noteworthy enough to matter for future work. Read [ARCHITECTURE.md](ARCHITECTURE.md) first for the layout.

## Read before changing anything playback-related

The five rules in `ARCHITECTURE.md` under "Playback rules" are not suggestions — every one of them maps to a real regression we shipped at least once. The most painful ones:

- **`onPlaybackId` and `onTrackChange` race.** Either can fire first for the same transition. Code that pairs file id with track URI must handle both orderings. The "URI-tagged file id cache" approach broke skip-next instant playback because it rejected file ids that arrived just before the track-change event.
- **Don't try to clean up `latestFileId` "for safety".** It works *because* it's reused across the small window between events. The pre-resolved cache (`nextCdnUrl` + `nextCdnFileId` from `onNextPlaybackId`) is what makes skip-next instant.
- **Remote pause from another Spotify client must mirror to ExoPlayer.** When `isStreaming` is true, `pc.onPause` has to call `syncPause()` AND update `_playback`. Hit this twice.
- **Don't force-skipNext on `STATE_ENDED`.** Spotify's state machine handles auto-advance. Our manual force-skip created a feedback loop that combined with stale cluster snapshots to skip whole songs.

## Test rig

`PlaybackTestRig` (`app/src/test/.../viewmodel/PlaybackTestRig.kt`) lets you exercise the extracted handler methods (`handleRemotePlay`, `handleRemotePause`, ...) without a real `PlayerConnect`. It mocks `MusicPlaybackService.instance` via mockk and swaps `Dispatchers.Main` for an unconfined test dispatcher. New regressions should ship with a test that would have caught them.

`./gradlew :app:testDebugUnitTest` runs the suite in <10s.

## Linting

`./gradlew detekt` runs detekt with the formatting plugin (which wraps ktlint). Existing issues are baselined in `detekt-baseline.xml` so they don't fail CI; new code is checked against the rules in `detekt.yml`. To regenerate the baseline after a major cleanup: `./gradlew detektBaseline`.

The baseline tracks ~170 pre-existing issues, mostly in `SpotifyViewModel.kt` (long methods, cyclomatic complexity, large class). Don't try to fix those one at a time — they'll mostly be resolved by extracting feature ViewModels.

## ViewModel coroutine helpers

Two helpers in `SpotifyViewModel` cover ~80% of the IO launches:

- **`launchWithSession("tag") { sess -> ... }`** — null-checks the session, runs on `Dispatchers.IO`, catches and logs exceptions against the tag.
- **`launchWithPlayer("tag") { pc -> ... }`** — same shape, but for transport commands that need `PlayerConnect`.

Use these instead of writing the boilerplate `viewModelScope.launch(Dispatchers.IO) { try { val s = session ?: return@launch ... } catch ... }` pattern. The handlers that still write the long form do so because they need custom `finally` blocks or `player?.getState()` plus `session?` (both required), which the helpers can't express cleanly yet.

## Session ownership

There is exactly one Kotify `Session`, `PlayerConnect`, `SpotifyPlayback`, and `SpotifyCdnResolver` in the entire process. They live in `playback/SessionHolder.kt` (an `object`). The ViewModel writes them during `initialize()` and reads them through property delegates (`session`, `player`, etc. in `SpotifyViewModel` are property-delegate accessors that hit `SessionHolder`). The service and the headphone receiver read them directly via `SessionHolder.player`.

Never store the session on Activity-scoped objects. The headphone-cold-launch path needs them when no Activity exists.

## KotifyClient is a local jar

`app/libs/KotifyClient.jar` is consumed as a local file dependency, gitignored. It's the obfuscated jar from `../KotifyClient`. To rebuild after a Kotify change:

```sh
cd ../KotifyClient
./gradlew obfuscate
cp build/libs/KotifyClient-obfuscated.jar ../snepilatch/src/app/libs/KotifyClient.jar
cd ../snepilatch/src
./gradlew :app:assembleDebug
```

## Logging

`LokiLogger` posts structured logs to a Loki endpoint when `loki.endpoint` is set in `local.properties` (gitignored). Without it, logs go to `Log.i/d/e` only. The `[Timing]` prefix is used for measuring command-to-effect latency in playback paths — keep them short and don't add new ones unless you're investigating a perf issue.

## Folder structure

Layered: `data/` `playback/` `playback/engine/` `ui/` `util/` `viewmodel/`. We considered a feature-based layout (`feature/home/data,domain,ui` per Google's app architecture guide) but it requires splitting the monolithic `SpotifyViewModel`, which is risky given the playback ordering invariants. Stick with layered until we have a real reason to split.

## Conventions

- Branch: `feature/<issue#>_PascalCase` or `fix/<issue#>_PascalCase`. Always a GitHub issue first.
- Commit subject: short imperative, no AI attribution.
- Squash merge.
- PR labels: `bug`, `enhancement`, `refactor`, `stale`.
- Issues older than the current day get the `stale` label automatically.

# Snepilatch Architecture

A short, opinionated guide to how the app is laid out and the rules to follow when adding code. Read this before changing anything non-trivial.

## Layout

```
src/app/src/main/java/ch/snepilatch/app/
├── KotifyApp.kt          Application class. Loki logging init.
├── MainActivity.kt       Single activity. Hosts Compose root.
├── data/                 UI models + KotifyClient → UI mappers.
├── playback/             Everything Android-specific about playback.
│   ├── MusicPlaybackService.kt   Foreground service. Owns ExoPlayer.
│   ├── MediaActionReceiver.kt    Notification button intents.
│   ├── MediaButtonReceiver.kt    Headphone cold-launch (BT/wired play).
│   ├── PositionInterpolator.kt   500ms position ticker.
│   ├── SessionHolder.kt          Process-scoped session/player owner.
│   └── engine/
│       └── SpotifyCdnResolver.kt CDN URL + Widevine license helper.
├── ui/
│   ├── theme/            Material3 theme, colors, type.
│   ├── components/       Shared composables (BottomNav, MiniPlayer, ...)
│   └── screens/          Feature screens.
├── util/                 Pure helpers. No Android lifecycle, no state.
│   ├── AlbumArtPalette.kt
│   ├── AudioOutputDetector.kt
│   ├── CookieManager.kt
│   ├── FormatUtils.kt
│   ├── LokiLogger.kt
│   ├── SpotifyImageUrl.kt
│   └── UpdateService.kt
└── viewmodel/
    └── SpotifyViewModel.kt  Single ViewModel. See "ViewModel rules" below.
```

## Layered responsibility

- **`util/`** is pure. No Compose, no ViewModel, no service. Top-level functions, easy to test.
- **`data/`** is types + mappers. No business logic. The mappers translate KotifyClient DTOs into the UI models defined here.
- **`playback/`** owns everything Android about playback: the foreground service, ExoPlayer, the media buttons, the session holder. Code here can use Android APIs but should not reach into the ViewModel.
- **`ui/`** is Compose only. Screens take a `SpotifyViewModel` and read its state flows. No direct API calls, no service references — go through the ViewModel.
- **`viewmodel/`** holds the single `SpotifyViewModel`. It owns the UI state, dispatches user actions, and bridges between the UI layer and the playback / data layers.

## Session ownership

There is exactly one Kotify `Session`, `PlayerConnect`, `SpotifyPlayback`, and `SpotifyCdnResolver` in the entire process. They live in `SessionHolder` (a `playback/SessionHolder.kt` `object`). The ViewModel writes them during `initialize()` and clears them during teardown; everything else (including the service and the media-button receiver) reads them.

**Do not** create `Session` or `PlayerConnect` in any other place. **Do not** store them on Activity-scoped objects. The whole point of `SessionHolder` is that headphone-cold-launch and similar non-Activity entry points need them.

## Playback rules — read before touching

The playback path has subtle ordering invariants between `onState`, `onTrackChange`, `onPlay`, `onPause`, and `onPlaybackId`. Refactoring it without tests is dangerous; we have a graveyard of regressions to prove it.

Rules:

1. **ExoPlayer is the source of truth for position** when streaming locally. Don't seek ExoPlayer based on interpolated cluster snapshots.
2. **`onPlaybackId` and `onTrackChange` race.** `onPlaybackId` can fire either before or after `onTrackChange` for the same transition. Code that pairs file id with track URI must handle both orderings.
3. **Pre-resolved cache (`nextCdnUrl` / `nextCdnFileId` from `onNextPlaybackId`) is what makes skip-next instant.** Don't break it.
4. **Remote pause from another client must mirror to ExoPlayer.** When `isStreaming` is true, `onPause` has to call `syncPause()` AND update `_playback`.
5. **Don't force-skipNext on `STATE_ENDED`.** Spotify's state machine handles auto-advance; our manual force-skip created feedback loops.

If you're going to refactor playback, **add a unit test first** (see `app/src/test/java/.../viewmodel/PlaybackTestRig.kt`).

## ViewModel rules

`SpotifyViewModel` is large (~2200 lines). It owns:
- Navigation state (current screen, screen stack)
- Session state (via `SessionHolder` accessors)
- Playback state (`_playback`, `isStreaming`, etc.)
- Per-feature state (home, library, search, detail, lyrics, queue, devices, account, theme, audio output, preferences)

Rules:
- New features should add their state and methods to the ViewModel until / unless they grow large enough to justify a feature-scoped ViewModel.
- Use `launchWithSession("tag") { sess -> ... }` for IO calls that need a session. It handles the null check, IO dispatch, and try/catch logging.
- Public state is exposed as `StateFlow`. Internal mutable state is `MutableStateFlow` with a leading `_`.
- Do not add new singletons or static `var`s. Use `SessionHolder` for cross-component shared state.

## Tests

Unit tests live in `app/src/test/`. Run with `./gradlew :app:testDebugUnitTest`.

- **Pure helpers** (`util/`) get straightforward JUnit tests. See `SpotifyImageUrlTest`, `FormatUtilsTest`.
- **ViewModel handlers** use `PlaybackTestRig` which mocks `MusicPlaybackService.instance` via `mockk` and swaps the Main dispatcher. See `RemotePlayPauseHandlerTest`.
- The rig pattern lets you exercise individual `handle*` methods (extracted from `pc.onPlay` / `pc.onPause` lambdas) without needing a real `PlayerConnect`. Extend the rig with new mocks as you extract more handlers.

When you fix a playback bug, **add a test that would have caught it.** The "browser pause doesn't pause phone" regression hit us twice; the third time it would have failed `RemotePlayPauseHandlerTest.remotePauseWhileStreaming_pausesExoPlayer` immediately.

## Build

- `./gradlew :app:assembleDebug` — debug APK
- `./gradlew :app:testDebugUnitTest` — unit tests
- KotifyClient is consumed as a local jar: `app/libs/KotifyClient.jar`. Rebuild with `cd ../KotifyClient && ./gradlew obfuscate` and copy `build/libs/KotifyClient-obfuscated.jar` over.
- Local logs go to Loki when `loki.endpoint` is set in `local.properties` (gitignored). Otherwise logs are local.

## Conventions

- Branch naming: `feature/<issue#>_PascalCase` or `fix/<issue#>_PascalCase`. Always a GitHub issue first.
- Commits: short imperative subject, 1-2 sentence body, no AI attribution.
- PR labels: `bug`, `enhancement`, `refactor`, `stale`.
- Squash merge.

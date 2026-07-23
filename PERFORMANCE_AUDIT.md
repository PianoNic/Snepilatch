# Snepilatch — KISS / Performance / Thermal Audit & Remediation Plan

> **Goal:** the phone runs hot during use. This plan fixes that first, then battery/perf, then KISS/maintainability, then comment hygiene.

> **Grounding:** every item was produced by reading the actual `.kt` source and adversarially re-verified against the code. In-repo docs (CLAUDE.md/ARCHITECTURE.md) were treated as untrusted and are **not** a source of truth here.

> **Provenance:** 14 parallel audit lenses → per-finding adversarial verification → dedup/synthesis → completeness critic. 87 raw findings, **86 verified-kept**, 1 rejected. Deduped to ~40 distinct issues across 8 workstreams.


## How later agents should use this document

- Work **top-down**: finish **P0** and profile before touching P1+. Heat is dominated by the P0 always-on frame loops.

- Each task is self-contained: **Files**, **Steps** (do in order), **Acceptance** (must all pass), **Risk**, **Depends on**.

- Tick `- [ ]` boxes as you go. One task = one small PR unless noted. Add a regression test where the repo already has a rig for that area.

- Do **not** add speculative/defensive code — fix the observed symptom only, then stop.

- Build gate per PR: `cd src && ./gradlew :app:assembleDebug && ./gradlew :app:testProdDebugUnitTest && ./gradlew detekt` (all green).


## Executive summary

86 verified findings deduplicate to ~40 distinct issues. The phone-overheating symptom has ONE dominant class of root cause: always-on per-frame render loops that keep the GPU/CPU awake continuously while the app is in normal use — chiefly the Kawarp fluid background (rebuilds a full-screen 120px blur + 2 AGSL shaders every vsync, and never stops when paused) and value-returning composables (rememberSmoothPositionMs, MarqueeText, LyricsScreen's interpolation loop) that force per-frame recomposition or perpetual frame callbacks even when nothing is moving. Fixing those four items (P0 workstream, all S/M effort) removes the great majority of the continuous heat. Everything after is battery/perf (feature-gated Jukebox DSP, redundant per-track network/decode/wakeups, a 2Hz engine-ticker leak, an unbounded log buffer) then KISS/maintainability (3199-line god-object, heavy duplication) then comment hygiene. Ordering is strict: land the P0 frame-loop fixes first, verify with a GPU/frame profiler while paused, then proceed down the tiers.


### The continuous heat comes from one class of cause: always-on per-frame render loops

1. FluidBackground Kawarp warp clock is a while(true)+withFrameNanos loop that keeps advancing time even when paused (0.15x drift), so the graphicsLayer rebuilds a full-screen 120px blur + two RuntimeShader RenderEffects EVERY vsync during both playback and pause — the single largest continuous GPU heat source (FluidBackground.kt L82-92, L127-149).

2. rememberSmoothPositionMs returns a raw Long, so its per-frame withFrameNanos state write is attributed to the caller's restart scope; MiniPlayerContent (present on nearly every screen while playing) and NowPlayingScreen therefore recompose every display frame during playback (SharedComponents.kt L164-182).

3. MarqueeText uses basicMarquee(iterations = Int.MAX_VALUE) so overflowing titles scroll forever, pinning a per-frame animation callback that prevents the now-playing panel from idling even while PAUSED (NowPlayingScreen.kt L1384-1387).

4. LyricsScreen's per-frame position-interpolation loop is gated only on isPlaying, so it keeps Choreographer producing frames at 60-120Hz for UNSYNCED / loading / no-lyrics tracks where nothing consumes the value (LyricsScreen.kt L81-89).

5. Secondary/feature-gated: Eternal Jukebox runs an ~8Hz viz emit and repeated whole-buffer FFT + O(n^2) self-similarity passes while active; a KotifyClient engine ticker wakes at 2Hz and never stops on pause or session teardown (leaking its coroutine scope). These are real but only fire when their feature is on, not on the baseline listening path.


---


## ⚠️ VERIFICATION CORRECTIONS — read before implementing P0

The completeness-critic pass re-checked the plan against source and found corrections + two P0-class misses. **These override the task cards below where they conflict.**


**Plan corrections:**

1. FluidBackground 'freeze and throttle' fix must EXIT the frame loop when paused, not just zero the dt multiplier. Today the while(true)+withFrameNanos loop (FluidBackground.kt L84-L91) keeps scheduling a Choreographer callback every vsync; if the fix only sets the 0.15x/1x factor to 0 it still wakes the CPU at 60-120Hz while paused. The paused path must return from / suspend the LaunchedEffect until isPlaying flips back. Separately, confirm 'throttle' actually caps the update rate WHILE PLAYING: `time` currently advances every vsync, so the full-screen 120px blur + createRuntimeShaderEffect/createBlurEffect/createChainEffect chain (L142-L147) rebuilds and allocates 3 native RenderEffects every frame at the panel's native 120Hz — cap it to ~30fps.

2. 'MarqueeText: use finite marquee iterations' (NowPlayingScreen.kt L1384-L1387) is a UX regression: basicMarquee(iterations=N) scrolls N times then STOPS permanently, so an overflowing title gets stuck truncated and never reveals its tail again until the composable is recreated. Prefer gating the marquee on visibility (only animate while the full player is expanded/visible) or pausing it while !isPlaying, rather than a finite count. Finite iterations does let the frame loop idle, but at the cost of never showing the rest of long titles.

3. 'HttpClient 429 back-off: suspend with delay() instead of Thread.sleep' has no matching code in this module. The only Thread.sleep in the app source is PcmJukeboxEngine.kt L155 (a legitimate paused-thread poll on the dedicated audio thread, not a hot spin), and the OkHttp usage in UpdateService.kt/ReleaseNotesScreen.kt has no sleep or 429 handling. The 429/Thread.sleep target almost certainly lives in the obfuscated app/libs/KotifyClient.jar (a separate repo) and cannot be changed here — verify the task's scope before assigning it to this codebase.


**Two continuous-load sources the 14 lenses missed (added as tasks P0-5 and P0-6 below):**

- **Canvas video background decodes + GPU-composites every frame while the player is open, even when audio is PAUSED**  
  _Why:_ When the Canvas feature is on (canvasEnabled + canvasUrl != null), PlayerBackground spins up a SECOND ExoPlayer that hardware-decodes a looping clip (REPEAT_MODE_ALL, L85) and pushes it to a TextureView every vsync. playWhenReady is forced true on every ON_START/ON_RESUME (L105-L111) with NO tie to audio isPlaying, so pausing the song leaves the video decoding + compositing indefinitely while the now-playing screen is foreground. Video decode is among the most power-hungry paths on the SoC and is a continuous load the 14-lens audit never flagged (the plan only covers the Fluid warp on this screen). It is as hot as the Kawarp warp when active.  
  _Fix:_ Gate the canvas player.playWhenReady on audio isPlaying (freeze the last decoded frame on pause) in the DisposableEffect and the LifecycleEventObserver; keep the existing ON_STOP pause. Optionally only build the ExoPlayer once the full player is actually expanded (it is already composition-gated by bgFade>0.001 in SpotifyApp L417, so this is mostly the pause-on-pause change).  
  _Thermal:_ High while the Canvas feature is enabled and the player screen is foreground — a continuous hardware-decoder + GPU-composite load comparable to the Fluid warp; zero when the feature is off. Pausing audio currently gives no thermal relief. · _Effort:_ S-M  
  _File:_ `app/ui/screens/NowPlayingScreen.kt (L60-L127, esp. L83-L93 and L101-L118)`

- **MiniPlayerContent (and NowPlaying/PlayerBackground/Lyrics) subscribe to the entire PlaybackUiState, forcing a 2Hz full recompose on nearly every screen — and this survives the planned rememberSmoothPositionMs fix**  
  _Why:_ PositionInterpolator rewrites _playback.positionMs every 500ms (PositionInterpolator.kt L42, TICK_MS=500 L65), so `val playback by vm.playback.collectAsState()` emits a new object twice a second while playing. MiniPlayerContent reads the whole object (L95, then track/isPaused/durationMs/isAd), so its restart scope re-runs 2x/sec. The mini bar is present on Home/Search/Library/Account, so this is an app-wide 2Hz recomposition of a non-trivial composable. Crucially, the plan's P0 'rememberSmoothPositionMs -> State<Long>' fix only removes the per-FRAME (60-120Hz) churn; the 500ms whole-object churn remains because these composables still collect the entire flow. The plan narrows only the SpotifyApp root (task 'read currentTrackUri projection'), not MiniPlayerContent / NowPlayingScreen / PlayerBackground / LyricsScreen.  
  _Fix:_ In MiniPlayerContent (and the three screens) collect narrow distinctUntilChanged'd projections — currentTrackUri/track, isPlayingFlow, a new isPausedFlow/isAdFlow/durationFlow, isStreamLoading — instead of the whole PlaybackUiState, and read positionMs ONLY inside the progress-bar leaf via the State<Long> from the fixed rememberSmoothPositionMs. That confines all 500ms churn to a 2dp progress indicator.  
  _Thermal:_ Moderate and continuous while playing, on every screen (not just the player). Re-running large composable bodies 2x/sec is measurable steady CPU even after the per-frame fixes land. · _Effort:_ M  
  _File:_ `app/ui/components/MiniPlayer.kt (L95); also NowPlayingScreen.kt L61 & L258, LyricsScreen.kt L53`


**Third (lower-priority) gap — added as P1-T6:**
- **Hot screens use collectAsState instead of collectAsStateWithLifecycle, so per-track/position flows keep churning snapshots while the app is backgrounded**  
  _Why:_ collectAsState keeps collecting for as long as the composition is alive, which persists across ON_STOP. While the app is backgrounded during playback the 500ms positionMs emissions (plus themeColors/viz) keep firing setValue/invalidations. The platform recomposer pauses on ON_STOP so drawing is deferred, which caps the damage — but the collectors and snapshot writes still run, and this is a legitimate omission from a thermal/battery-focused plan. Lower confidence and lower magnitude than the two gaps above, listed for completeness.  
  _Fix:_ Migrate the playback/theme/viz collectors on the hot screens to collectAsStateWithLifecycle(minActiveState = Lifecycle.State.STARTED) so collection stops when the UI is not visible. Playback itself is service-driven, so the UI collectors are safe to suspend.  
  _Thermal:_ Low. Background snapshot churn only; the service wakelock keeps the CPU awake regardless, so the incremental cost is small. Include only if cheap. · _Effort:_ M (broad but mechanical)


_Critic scoping note:_ The plan's four P0 frame-loop items are well-targeted and correctly identified as the dominant continuous heat, and I verified each in source (FluidBackground.kt L82-92; SharedComponents.kt rememberSmoothPositionMs L164-182; NowPlayingScreen.kt MarqueeText L1371-1389 iterations=Int.MAX_VALUE; LyricsScreen.kt L81-89 gated only on isPlaying). Secondary items also check out: DeezerDecryptProxy creates a fresh Cipher.getInstance per block (L213-216), Jukebox viz emits at 120ms with no paused/unchanged skip (JukeboxController.kt L311-330), and the derived flows currentTrackUri/isPlayingFlow already use distinctUntilChanged (SpotifyViewModel L187-193). Derived StateFlows are clean and SpotifyImage relies on Coil's automatic target-size downsampling, so no gap there. Two continuous-load sources are MISSING from the plan: (1) the Canvas video ExoPlayer, which is as hot as the Fluid warp when enabled and is never paused when audio pauses; and (2) the app-wide 2Hz whole-PlaybackUiState recomposition of MiniPlayerContent, which survives the planned rememberSmoothPositionMs fix and affects every screen, not just the player. Important scoping context the plan slightly overstates: the Fluid warp only runs when the user's background mode is 'fluid' (gradient off; AlbumBackdrop picks one or the other at NowPlayingScreen L228-243) AND only while the full player is expanded (bgFade>0.001, SpotifyApp L417) — likewise Canvas — so 'always-on during normal use' is accurate for the expanded-player screen but not while browsing with the mini bar (where gap #2 is the relevant continuous cost). The three plan risks above (FluidBackground fix semantics, MarqueeText UX, out-of-repo HttpClient target) should be resolved before implementation.


---


## Workstreams (execute in order)


## P0 · WS-P0-FRAMELOOPS — Stop the always-on per-frame GPU/CPU render loops (the overheating fix)

These four items are the continuous, in-normal-use per-frame GPU/CPU work that keeps the phone hot. All are S/M effort with high payoff. Land and profile these before anything else; the fluid-background and smooth-position fixes remove the bulk of the sustained heat, and the marquee + lyrics-loop fixes let the screen truly idle when paused/unsynced.

### ✅ STATUS 2026-07-20 — all of P0-1…P0-6 IMPLEMENTED

Gate run after every step: `:app:assembleProdDebug`, `:app:testProdDebugUnitTest`, `detekt` — **all green**.

What landed, and where it deviates from the card text:

- **P0-1** `FluidBackground.kt` — loop now early-returns when `!isPlaying` (per the critic correction, not just a zeroed drift term), the `0.15f` paused-drift term is gone, and `time` is written only once ~33ms has accumulated (≈30fps) so the 3-effect RenderEffect chain rebuilds ~30×/s instead of every vsync. Stale KDoc ("nearly freezes when paused") corrected.
- **P0-2** `rememberSmoothPositionMs` now returns `State<Long>`. MiniPlayer reads `.value` inside the `progress = { }` draw lambda. A new `PlaybackProgress` leaf in NowPlayingScreen owns seek state + jukebox reads + the smooth position, replacing the duplicated ~50-line progress block in *both* orientations. Elapsed label derived at ~1Hz via chained `derivedStateOf` so `formatTime` no longer runs per frame.
- **P0-3** MarqueeText — **did NOT use finite iterations** (critic: that sticks long titles truncated forever). Instead `iterations = if (isPlaying) Int.MAX_VALUE else 0`, so it scrolls fully while playing and stops requesting frames when paused.
- **P0-4** LyricsScreen frame loop now gated on `isPlaying && syncedReveal` (`syncType` is `"LINE_SYNCED"`/`"SYLLABLE_SYNCED"`, verified in source).
- **P0-5** Canvas video — `playWhenReady` is now driven by `audioPlaying && isForeground`. The lifecycle observer only tracks foreground; a `LaunchedEffect` sets the flag. Gating on foreground too avoids the race where a pause/resume while backgrounded would re-arm the decoder. The whole canvas block was extracted to `CanvasVideoBackground` (+ a `coverModifier` helper) to stay under the detekt complexity/length limits.
- **P0-6** Added single-field `distinctUntilChanged` projections to the VM (`currentTrack`, `isPausedFlow`, `isAdFlow`, `durationFlow`, `isShufflingFlow`, `repeatModeFlow`, `positionFlow`). MiniPlayerContent, NowPlayingScreen, PlayerBackground and LyricsScreen no longer collect the whole `PlaybackUiState`. `positionFlow` (the only 2Hz projection) is collected **only** in the `MiniProgressBar` / `PlaybackProgress` leaves; LyricsScreen re-anchors by collecting it *inside* a coroutine instead of keying a `LaunchedEffect` on a composition read, so position ticks no longer recompose its scaffold.
- **Extra (not on a card, same root cause):** `SpotifyApp.kt` — the app **root** collected the whole `PlaybackUiState` just to test `track != null`, recomposing the entire app at 2Hz. Now reads the `currentTrackUri` projection. This is the "narrow the SpotifyApp root" item the critic note referenced.

**On-device verification — DONE for P0-1 (2026-07-20).** Samsung Galaxy S25 Ultra (SM-S938B, SDK 36, 120Hz panel), measured with `adb shell dumpsys gfxinfo ch.snepilatch.app` over 8-second windows in fluid-background mode, as a true A/B of the pre-P0 commit (`647b0ef`) vs the P0 commit (`5487c4e`):

| Scenario | Pre-P0 | P0 fix |
|---|---|---|
| Fluid bg, **paused**, 8s | **~975 frames (~122 fps)** — warp never froze | **0 frames** — fully idle |
| Fluid bg, playing, 8s | ~121 fps | ~121 fps (display-capped by the smooth progress bar; warp rebuild throttled to ~30fps, which frame-count can't isolate) |
| Accent bg, paused, 8s | — | 0 frames |

P0-1's "freeze when paused" claim is proven: the pre-P0 build rebuilt the full 3-effect RenderEffect chain ~122×/s while paused and doing nothing — the phone-heat root cause — and the fix drops that to zero.

**Still unmeasured:** P0-5 canvas pause-on-pause (needs a track that actually has a Canvas clip), and the per-composable recomposition-count claims for P0-2 / P0-6 (gfxinfo counts frames, not recompositions — these need Layout Inspector / composition tracing to confirm the leaf-confinement).

**Environment note:** `app/libs/KotifyClient.jar` was stale (Jun 14) against the current source and the build was already broken before any of this work. Rebuilt from the sibling `KotifyClient` repo via `./gradlew obfuscate`.

That rebuild initially failed on the ambient JDK 25 with `IllegalArgumentException: 25.0.3`. Root cause: **KotifyClient is a Gradle 8.14 build and Gradle 8.14 cannot run on JDK 25** (the app is on Gradle 9.3.1, which is why only KotifyClient broke). Since that project targets JDK 21 throughout (`jvmToolchain(21)`, `org.gradle.jvmTarget=21`, CI = temurin 21), its daemon is now pinned to 21 via `gradle/gradle-daemon-jvm.properties` in **both** the root and `tools/string-obfuscator/` (the latter is Exec'd as a separate Gradle process and would otherwise inherit the ambient JAVA_HOME). `./gradlew obfuscate` now works on any ambient JDK — no override needed.


#### [x] `P0-1` — Freeze and throttle the Fluid (Kawarp) album background clock

**severity** high · **thermal** high (largest GPU source, ran full-tilt even while paused) · **effort** S


> **CORRECTION (critic):** the fix must **exit/suspend the LaunchedEffect when paused** (return early), not merely zero the 0.15x drift term — otherwise the withFrameNanos loop keeps scheduling a Choreographer callback every vsync while paused. Also confirm the 'throttle' actually caps the RenderEffect-chain rebuild to ~30fps *while playing* (it currently rebuilds 3 native RenderEffects every vsync at up to 120Hz). The steps below already encode this.


_Files:_ `app/ui/components/FluidBackground.kt`


_Steps:_

- [ ] In KawarpBackground, change the warp-clock LaunchedEffect(isPlaying) at L82-92 to early-return when !isPlaying (`if (!isPlaying) return@LaunchedEffect`) so it stops requesting frames and `time.floatValue` holds its last value; the effect is already keyed on isPlaying so it restarts cleanly on resume.

- [ ] Delete the `* (if (isPlaying) 1f else 0.15f)` paused-drift term; advance `time` by real dt only.

- [ ] Throttle to ~30fps while playing: accumulate dt across frames and only write `time.floatValue` when the accumulator >= ~0.033s, so the L127-149 graphicsLayer (which reads time.floatValue and rebuilds warp+blur+grade RenderEffect chain) invalidates ~30x/s instead of at full vsync. The withFrameNanos callback still fires each vsync (cheap) but the expensive rebuild/redraw halves.

- [ ] Do NOT restructure the shaders or change BLUR_RADIUS in this task.


_Acceptance:_

- [ ] With NowPlayingScreen open (fluid backdrop selected, SDK>=33) and playback PAUSED, a GPU/frame profiler shows 0 frame callbacks and 0 RenderEffect rebuilds from this composable (time stops mutating; last RenderNode output is reused).

- [ ] While playing, graphicsLayer invalidations drop to ~30/s with no visible change to the warp (it animates at 0.05x speed).

- [ ] Build: `cd src && ./gradlew :app:assembleDebug` green; eyeball paused (static warp) vs playing on a device.


_Risk:_ Paused background becomes a static warped frame instead of an imperceptibly drifting one (doc already says 'nearly freezes when paused'). Very slightly less smooth warp at 30fps, imperceptible for this effect.


#### [x] `P0-2` — Make rememberSmoothPositionMs return State<Long> and defer reads to draw/leaf scope

**severity** high · **thermal** medium (baseline-while-playing via the always-present mini bar) · **effort** M


_Files:_ `app/ui/components/SharedComponents.kt`, `app/ui/components/MiniPlayer.kt`, `app/ui/screens/NowPlayingScreen.kt`


_Steps:_

- [ ] In SharedComponents.kt L164-182 change the helper to hold the state without `by`: `val displayed = remember { mutableLongStateOf(positionMs) }`, write `displayed.longValue = ...` in both the paused branch and the withFrameNanos loop, and `return displayed` (a State<Long>/MutableLongState). Keep the paused/off-screen early-return that snaps to positionMs.

- [ ] MiniPlayer.kt L148-159: read the value ONLY inside the existing `LinearProgressIndicator(progress = { (smooth.longValue.toFloat()/playback.durationMs).coerceIn(0f,1f) })` lambda so the read lands in the draw phase; MiniPlayerContent then stops recomposing every frame.

- [ ] NowPlayingScreen.kt (portrait L895-933, landscape L469-507): extract a Unit-returning leaf `PlaybackProgress(vm, playback)` that owns seekDragging/seekDragValue, calls rememberSmoothPositionMs internally, and renders the Slider/JukeboxTimeline + the elapsed time Row. Because Material3 Slider `value` and formatTime are eager composition reads, they MUST live inside this leaf so per-frame invalidation is confined to it. Use it in both orientations.

- [ ] Derive the elapsed label at ~1Hz inside the leaf (e.g. `smooth.longValue / 1000`) to avoid a per-frame String allocation.

- [ ] Fold jukeboxViz + jukeboxElapsedMs reads (NowPlayingScreen L460/L886, L462-468/L888-894) into this same PlaybackProgress leaf so their emissions recompose only the leaf, not the whole Column.


_Acceptance:_

- [ ] Recomposition counts (Layout Inspector / composition tracing) show MiniPlayerContent recompositions = 0 per frame while playing/browsing — only the 2dp progress bar redraws.

- [ ] In NowPlaying, only the PlaybackProgress leaf recomposes per frame, not the ~400-line orientation Column.

- [ ] Seek drag, seekTo(duration), and jukebox map swap still work in both portrait and landscape.

- [ ] Build `:app:assembleDebug` green.


_Risk:_ If any caller reads the State in a wide scope the win is lost. Slider needs the wrapper leaf because its `value` is eager. Verify the thumb still tracks smoothly and the paused/off-screen snap still zeroes the state.


#### [x] `P0-3` — MarqueeText: use finite marquee iterations so the frame loop can idle

**severity** low · **thermal** low (only when title/artist/album overflows; but it is the SOLE per-frame cost while paused on now-playing after P0-1) · **effort** S


> **CORRECTION (critic):** do **not** use `basicMarquee(iterations = N)` finite count — an overflowing title would scroll N times then stick truncated forever. Instead **gate the marquee on visibility / `!isPlaying`** (only animate while the full player is expanded and playing) so the frame loop can idle without hiding the tail of long titles.


_Files:_ `app/ui/screens/NowPlayingScreen.kt`


_Steps:_

- [ ] At L1385 drop `iterations = Int.MAX_VALUE` from `.basicMarquee(...)` so it uses the finite default count and the marquee settles, letting the frame loop stop requesting frames.

- [ ] Keep velocity as-is.


_Acceptance:_

- [ ] On a PAUSED now-playing screen with a long (overflowing) title, a frame profiler shows the marquee stops requesting frames after a few passes (0 sustained frame callbacks while paused).

- [ ] Build `:app:assembleDebug` green.


_Risk:_ UX only: long titles settle after a few scroll passes instead of scrolling forever.


#### [x] `P0-4` — Gate the LyricsScreen per-frame interpolation loop on synced-reveal availability

**severity** medium · **thermal** medium (kills continuous 60-120Hz frame production for unsynced/loading/no-lyrics playback) · **effort** S


_Files:_ `app/ui/screens/LyricsScreen.kt`


_Steps:_

- [ ] Above the effect compute `val syncedReveal = lyrics?.syncType == "LINE_SYNCED" || lyrics?.syncType == "SYLLABLE_SYNCED"` (these two enum values are exactly the synced branches at L419/L422).

- [ ] Change the L81-89 loop to `LaunchedEffect(isPlaying, syncedReveal) { if (!isPlaying || !syncedReveal) return@LaunchedEffect; while (true) { withFrameNanos { }; ... } }`.

- [ ] Leave the L74-78 anchor `LaunchedEffect(playback.positionMs)` untouched so paused/coarse updates still set smoothPosition.


_Acceptance:_

- [ ] With the lyrics screen open on an UNSYNCED or no-lyrics track while playing, a profiler shows 0 frame callbacks from this loop.

- [ ] Synced (LINE/SYLLABLE) tracks still animate the active line and karaoke reveal.

- [ ] Build `:app:assembleDebug` green.


_Risk:_ None for synced tracks; the anchor effect still updates position on coarse ticks for the others.


#### [x] `P0-5` — Pause the Canvas video ExoPlayer when audio is paused (second decoder never stops today)

**severity** high · **thermal** high (when Canvas feature enabled + player foreground — a continuous HW-decoder + GPU-composite load comparable to the fluid warp; zero when feature off) · **effort** S-M


_Files:_ `app/ui/screens/NowPlayingScreen.kt`


_Steps:_

- [ ] In PlayerBackground (NowPlayingScreen.kt ~L60-127) the Canvas ExoPlayer forces playWhenReady=true on every ON_START/ON_RESUME (L105-111) with NO tie to audio isPlaying, so pausing the song leaves the looping clip decoding + compositing every vsync while the screen is foreground.

- [ ] Gate `canvasPlayer.playWhenReady` on audio `isPlaying`: true only when audio plays, false when paused — freezing the last decoded frame on pause. Wire it in the DisposableEffect and the LifecycleEventObserver, keeping the existing ON_STOP pause.

- [ ] (Optional) only construct the canvas ExoPlayer once the full player is actually expanded — it is already composition-gated by bgFade>0.001, so the main change is pause-on-pause.


_Acceptance:_

- [ ] With Canvas enabled and player foreground, pausing audio drops the video decoder to 0 (frozen frame) — verify via GPU/HWUI or profiler; no decoder wakeups while paused.

- [ ] Resuming audio resumes the clip; ON_STOP still fully pauses.

- [ ] Build green; sanity-check on a device with a track that has a Canvas.


_Risk:_ Canvas becomes a still frame while paused (expected). Ensure resume re-arms playWhenReady so it doesn't stay frozen after unpause.


#### [x] `P0-6` — Stop the app-wide 2Hz whole-PlaybackUiState recompose (MiniPlayer + hot screens) — survives P0-2

**severity** medium · **thermal** moderate & continuous while playing, on EVERY screen (mini bar is on Home/Search/Library/Account) — measurable steady CPU even after the per-frame fixes · **effort** M · **depends on** P0-2


_Files:_ `app/ui/components/MiniPlayer.kt`, `app/ui/screens/NowPlayingScreen.kt`, `app/ui/screens/LyricsScreen.kt`


_Steps:_

- [ ] PositionInterpolator rewrites `_playback.positionMs` every 500ms, so `val playback by vm.playback.collectAsState()` emits a new PlaybackUiState object 2x/sec while playing. MiniPlayerContent (MiniPlayer.kt L95) and NowPlaying/PlayerBackground/Lyrics read the whole object, so their restart scopes re-run 2x/sec app-wide.

- [ ] In MiniPlayerContent and those screens, collect narrow `distinctUntilChanged` projections instead of the whole flow: currentTrackUri/track, isPlayingFlow, and add isPausedFlow / isAdFlow / durationFlow / isStreamLoading as needed (mirror the existing currentTrackUri/isPlayingFlow projections that already use distinctUntilChanged).

- [ ] Read `positionMs` ONLY inside the progress-bar leaf, via the `State<Long>` produced by the P0-2 fix to rememberSmoothPositionMs. This confines all 500ms churn to the 2dp progress indicator.


_Acceptance:_

- [ ] Recomposition counts: while playing on Home (mini bar visible), MiniPlayerContent body does NOT recompose on position ticks — only the progress-bar leaf updates.

- [ ] NowPlayingScreen: only the progress leaf recomposes on position; track/artist/controls do not.

- [ ] Build + tests green.


_Risk:_ Adding flow projections widens the VM surface slightly; keep them derived + distinctUntilChanged. Do P0-2 first (depends on the State<Long> form).


## P1 · WS-P1-JUKEBOX — Reduce Eternal Jukebox DSP cost (feature-gated but runs indefinitely when on)

The jukebox is opt-in and off the baseline listening path, but when enabled it runs indefinitely: an ~8Hz viz emit, repeated whole-buffer FFT + O(n^2) self-similarity passes, an always-installed audio tap copying every PCM buffer, and a ~63MB buffer held for the sink lifetime. These are the heaviest sustained CPU/memory costs once the feature is active.


#### [ ] `P1-J1` — JukeboxAudioTap: bypass when off, lazy-allocate the 63MB buffer, bulk PCM copy

**severity** medium · **thermal** low (audio-thread cleanliness + ~63MB memory/GC pressure, not sustained CPU heat) · **effort** M


_Files:_ `app/playback/JukeboxAudioTap.kt`, `app/playback/MusicPlaybackService.kt`


_Steps:_

- [ ] Override `isActive()` to return `analyzing` so ExoPlayer bypasses the processor (no per-buffer queueInput copy) when the jukebox is off. isActive is only re-evaluated on a flush; the enable path already flushes (enable() -> jukeboxSeekToStart() seeks). Verify capture still starts cleanly on enable.

- [ ] In onConfigure (L62-72) keep storing sampleRate/channels but do NOT allocate `pcm`. Allocate `pcm = ShortArray(needed)` lazily in setJukeboxAnalyzing(true)/resetCapture (rate & channels are known by then), and null it (`pcm = ShortArray(0)`) on disable to release the ~63MB. Keep capture()'s non-empty pcm guard.

- [ ] Replace capture()'s per-sample `while (buf.remaining() >= 2 ...) { pcm[i++] = buf.short }` (L89-97) with a bounded bulk copy: `val count = minOf(buf.remaining()/2, cap-len); if (count>0) { buf.asShortBuffer().get(pcm, len, count); len += count }` — asShortBuffer inherits the duplicate's LITTLE_ENDIAN order; clamp count to preserve the overflow guard.

- [ ] Leave the @Synchronized snapshot locks as-is (snapshots are infrequent).


_Acceptance:_

- [ ] With jukebox OFF, audio-thread profiling shows queueInput is bypassed (processor inactive) and the heap has no ~63MB ShortArray from this tap.

- [ ] With jukebox ON, capture populates and remix/handoff still work.

- [ ] Build `:app:assembleDebug` + `:app:testProdDebugUnitTest` green; device-test the jukebox enable path per project memory (force-stop + relaunch after install).


_Risk:_ isActive changes only take effect on a sink flush — confirm the enable path flushes and capture populates. Ensure rate/channels are set before the first capture write (they are).


#### [ ] `P1-J2` — WaveformAnalyzer: cut preview reanalysis cadence and hoist the Hann window

**severity** medium · **thermal** medium (bursty repeated FFT + O(n^2) passes while jukebox capturing) · **effort** S


_Files:_ `app/playback/JukeboxController.kt`, `app/playback/WaveformAnalyzer.kt`


_Steps:_

- [ ] Raise PREVIEW_EVERY_S (JukeboxController L51) from 5 to ~15-20s (or populate previewBuckets once at handoff), so the whole-buffer WaveformAnalyzer.analyze at L168-174 runs ~3-6 times over the capture half instead of ~19 — it feeds only the cosmetic 56-bar previewBuckets histogram. Handoff analysis (L193-194) and growth reanalysis (L266-274, every 30s) are unchanged.

- [ ] In WaveformAnalyzer.kt hoist the immutable Hann window to a `private val WINDOW = DoubleArray(FFT_SIZE){ 0.5 - 0.5*cos(2.0*Math.PI*it/(FFT_SIZE-1)) }` computed once and use it in analyze() instead of rebuilding hann(FFT_SIZE) each call (L55/L172).


_Acceptance:_

- [ ] During a jukebox capture session, WaveformAnalyzer.analyze call count over the capture half drops from ~19 to ~3-6.

- [ ] Remix map / preview histogram still populates (just refreshes less often); handoff unchanged.

- [ ] Build green.


_Risk:_ Preview histogram refreshes a little later / less often — cosmetic, no correctness or handoff impact.


#### [ ] `P1-J3` — JukeboxController viz loop: skip emits when paused and when unchanged

**severity** medium · **thermal** low (sustained ~8Hz wake while jukebox active, small per-tick cost) · **effort** S


_Files:_ `app/playback/JukeboxController.kt`


_Steps:_

- [ ] In startViz() (L311-330) add `if (paused) { delay(VIZ_TICK_MS); continue }` so it stops recomputing a frozen frame.

- [ ] Cache last-emitted posFrames/bufferedFraction/remixing and a buckets-dirty flag (buckets only change on eng.update()); `continue` without emitting to `_viz` when all are unchanged.

- [ ] Optionally raise VIZ_TICK_MS (L49) to ~200ms.


_Acceptance:_

- [ ] With jukebox active and playback PAUSED, the jukeboxViz StateFlow emits 0 updates; while playing, emissions fire only on actual change.

- [ ] JukeboxTimeline still animates (animateFloatAsState eases between ticks). Build green.


_Risk:_ Playhead could look marginally less smooth if the unchanged-skip is too aggressive; keep emitting whenever posFrames/bufferedFraction actually change.


## P1 · WS-P1-IO — Eliminate redundant per-event network fetches, full-res decodes, and unbounded buffers

Per-track-change and per-recreation waste: album art re-fetched full-resolution with no cache/timeout on skips, a fresh Coil ImageLoader + full-res decode per palette extraction, a Blowfish key-schedule rebuilt per decrypted block, network device polls on every state push, PendingIntents rebuilt per notification, a blocking IO-thread sleep on 429, an unbounded log buffer in production, and a GitHub update check refired on every config change. None are per-frame, but each is real battery/radio/memory waste and most are S-effort quick wins.


#### [ ] `P1-IO1` — Album-art loader: downsample, reuse Coil cache, add timeout, dedupe the copy-pasted block

**severity** medium · **thermal** low (one fetch+decode per track change/skip, not sustained) · **effort** M


_Files:_ `app/playback/MusicPlaybackService.kt`


_Steps:_

- [ ] Rewrite loadBitmap (L1126-1138) to route through the app Coil singleton: `imageLoader.execute(ImageRequest.Builder(ctx).data(resolved).size(256).allowHardware(false).build())` and read the resulting software Bitmap (allowHardware(false) required for MediaMetadata). Keep the spotify:image: -> i.scdn.co rewrite (L1130-1132). This reuses the disk/memory cache the UI already populated and downsamples the ~1.6MB full-res decode to ~256px. (If not routing through Coil, at minimum use openConnection() with ~10s connect/read timeouts + BitmapFactory.Options.inSampleSize.)

- [ ] Extract one `private fun loadArtAndRefresh(url: String?, refresh: Boolean = true, apply: (Bitmap?) -> Unit)` and replace the ~5 duplicated serviceScope.launch{loadBitmap...; mainHandler.post{...}} blocks in playUrl (L486-494), setIdleMetadata (L715-724), setNextUrl prefetch (L745-747), playDrmUrl (L886-894), updateMetadata (L900-906).

- [ ] Short-circuit when the requested URL equals the currently-loaded art so idle->play and prefetch->skip stop re-fetching the identical image.


_Acceptance:_

- [ ] Skipping a track hits the cache (no second network fetch of the same URL); notification icon decodes at ~256px, not full-res.

- [ ] A stalled CDN no longer hangs the IO coroutine indefinitely (bounded timeout).

- [ ] Build green; confirm lockscreen/notification art still renders.


_Risk:_ Notification art slightly lower-res (imperceptible at icon size). If routing through Coil, confirm the spotify:image URI rewrite still applies and a software bitmap is returned.


#### [ ] `P1-IO2` — AlbumArtPalette: use the shared Coil singleton and downsample to 112px

**severity** medium · **thermal** low (per-skip spike, not continuous) · **effort** S


_Files:_ `app/util/AlbumArtPalette.kt`


_Steps:_

- [ ] Replace `val loader = ImageLoader(context)` (L20) with `val loader = coil.Coil.imageLoader(context)` (the app convention at SharedComponents.kt:468) so it reuses the shared memory/disk cache instead of a fresh empty one.

- [ ] Add `.size(112)` to the ImageRequest builder (L21-24) to match Palette's internal resize target (default resizeBitmapArea 112x112), avoiding a wasted full-res software decode.


_Acceptance:_

- [ ] On a track skip, palette extraction reuses cached art (no redundant network fetch / full-res decode); extracted swatches are unchanged.

- [ ] Build green.


_Risk:_ None functional; hits cache more often. Palette already downsamples to ~112px so swatches are unchanged.


#### [ ] `P1-IO3` — DeezerDecryptProxy: init one Cipher per stream instead of per block

**severity** medium · **thermal** low (paced by ExoPlayer buffering, Deezer-path only) · **effort** S


_Files:_ `app/playback/DeezerDecryptProxy.kt`


_Steps:_

- [ ] In stream()/decryptInto, build and init ONE Cipher once: `cipher.init(DECRYPT_MODE, SecretKeySpec(key,"Blowfish"), IvParameterSpec(IV))`. Keep it local to the streaming thread (Cipher is not thread-safe; handle() is one-per-socket).

- [ ] In the per-block loop (decryptBlock L213-217) call only `cipher.doFinal(buf)` — doFinal resets the cipher to its post-init state (IV reset to the fixed constant IV), so every block still decrypts against the same fixed IV as the current positional/no-chaining scheme requires.

- [ ] Remove the per-block Cipher.getInstance + init.


_Acceptance:_

- [ ] Deezer playback and seek/Range re-open decrypt identically; the ~5000 Blowfish key expansions per track collapse to one init per stream.

- [ ] Build green; run any Deezer decrypt test if present.


_Risk:_ None if the Cipher stays local to the streaming thread. Verified: doFinal resets to the post-init IV (standard CBC).


#### [ ] `P1-IO4` — loadDevices(): dedupe the per-state-push getDevices() network call

**severity** low · **thermal** low (radio waste while remote, not local decode) · **effort** S


_Files:_ `app/viewmodel/SpotifyViewModel.kt`


_Steps:_

- [ ] Add `private var lastDeviceIndicatorKey: Pair<Boolean,Boolean>? = null`.

- [ ] In updatePlaybackFromState at L1038 compute `val key = state.is_active_device to state.has_active_device` and run the L1038-1045 loadDevices() block only when `key != lastDeviceIndicatorKey`, updating it after.


_Acceptance:_

- [ ] With playback on a foreign active device, getDevices() fires once on the transition into foreign-active rather than on every onState push (~5/track).

- [ ] Build green.


_Risk:_ A foreign device->foreign device handover (both booleans identical) won't refresh the name until another edge — acceptable for a display-only indicator.


#### [ ] `P1-IO5` — Cache the 5 notification PendingIntents and drop redundant metadata double-calls

**severity** low · **thermal** none (strictly event-driven) — battery/KISS hygiene · **effort** S


_Files:_ `app/playback/MusicPlaybackService.kt`


_Steps:_

- [ ] Build the five broadcast PendingIntents (PREV/PLAY_PAUSE/NEXT/LEFT/RIGHT, L989-1013) once as lazy fields or in onCreate (fixed actions + request codes + FLAG_IMMUTABLE make caching safe) and reuse them in buildNotification.

- [ ] Delete the standalone `updateMediaSessionMetadata()` calls that immediately precede `updateNotification()` at L481-482, L491-492, L687-688, L719-720, L881-882, L903-904 (updateNotification already calls updateMediaSessionMetadata internally, including on art-arrival paths).

- [ ] Optionally only re-putBitmap(ALBUM_ART, currentArt) when currentArt actually changed.


_Acceptance:_

- [ ] A notification refresh no longer constructs new PendingIntents and no longer double-builds metadata/bitmap; transport controls and lockscreen still work.

- [ ] Build green.


_Risk:_ Low; the internal updateMediaSessionMetadata inside updateNotification still refreshes the bitmap on the art-arrival path.


#### [ ] `P1-IO6` — HttpClient 429 back-off: suspend with delay() instead of Thread.sleep

**severity** low · **thermal** none (parked thread, zero CPU) — IO-pool/cancellation hygiene · **effort** S


> **OUT OF SCOPE (critic):** there is no 429/`Thread.sleep` back-off in this module — the target lives in the obfuscated `app/libs/KotifyClient.jar` (separate repo). The only `Thread.sleep` in app source is `PcmJukeboxEngine.kt` L155, a legitimate paused-thread poll on the dedicated audio thread. **Skip here**; fix in the KotifyClient repo if desired.


_Files:_ `KotifyClient/http/HttpClient.kt`


_Steps:_

- [ ] Replace both `Thread.sleep((retryAfter.toIntOrNull() ?: 30) * 1000L)` at L263 and L361 (inside withContext(Dispatchers.IO)) with `kotlinx.coroutines.delay((retryAfter.toIntOrNull() ?: 30) * 1000L)`.

- [ ] Add `import kotlinx.coroutines.delay` to HttpClient.kt (it is NOT currently imported in this file).


_Acceptance:_

- [ ] A cancelled/abandoned request during 429 back-off frees the IO thread immediately (delay is cancellable); behavior otherwise identical.

- [ ] KotifyClient build + tests green; rebuild the obfuscated jar per project memory (`cd ../KotifyClient && ./gradlew obfuscate` then copy to app/libs/KotifyClient.jar).


_Risk:_ None; delay aborts on cancellation (desirable), otherwise identical timing.


#### [ ] `P1-IO7` — LokiLogger: stop unbounded buffer growth in production and cap the dev buffer

**severity** medium · **thermal** low (memory/GC over a long session, not per-frame) · **effort** S


_Files:_ `app/util/LokiLogger.kt`


_Steps:_

- [ ] Add `@Volatile private var started = false` and set it true at the end of init().

- [ ] At the top of log() (L70) keep the Log.x console call but add `if (!started) return` before `buffer.add()` (L78). Production ships with LOKI_ENDPOINT empty so init() is never called; started stays false and the queue never grows.

- [ ] Add a `MAX_BUFFER` const and, after add(), `while (buffer.size > MAX_BUFFER) buffer.poll()` to bound the dev case where flushJob is running.


_Acceptance:_

- [ ] In a shipping (prod) build, LokiLogger's buffer size stays 0 over a multi-hour session (no unbounded growth).

- [ ] In a dev build with loki.endpoint set, the buffer caps at MAX_BUFFER. Build green.


_Risk:_ Pre-init entries in production are not buffered, which is fine since production never inits and dev inits in Application.onCreate before meaningful logging.


#### [ ] `P1-IO8` — MainActivity: dedupe the GitHub update check per process

**severity** medium · **thermal** low (bursty radio + TLS per config change) · **effort** S


_Files:_ `app/MainActivity.kt`


_Steps:_

- [ ] Add `companion object { val updateChecked = AtomicBoolean(false) }` and wrap the withContext(Dispatchers.IO){ UpdateService.checkForUpdates } block (L102-110) so it runs only when `updateChecked.compareAndSet(false, true)`.

- [ ] Optional: add `android:configChanges` for common changes (orientation, uiMode, fontScale, screenSize) to MainActivity in AndroidManifest.xml (L26-31) to avoid needless recreation entirely.


_Acceptance:_

- [ ] Rotating the device or toggling dark mode does not re-fire the GitHub update HTTPS round-trip.

- [ ] Build green.


_Risk:_ The AtomicBoolean dedupe alone has no functional risk. (Leave prefs reads on Main; moving them off-main risks briefly showing default StateFlow values.)


## P1 · WS-P1-TICKERS — Fix wasteful ticker cadence, a coroutine-scope leak, and a pagination correctness bug

Lower-frequency but real: a position ticker cancelled/relaunched on every state push (resetting its 30s report counter), a 2Hz KotifyClient engine ticker that never stops on pause and leaks its scope past disconnect, a synced-lyrics view that recomposes every frame, and a library-pagination trigger keyed on the wrong list that silently breaks paging under a filter. Mixed perf/correctness.


#### [ ] `P1-T1` — Make PositionInterpolator.start() idempotent

**severity** low · **thermal** low (KISS + latent correctness; per-push cancel/relaunch churn) · **effort** S


_Files:_ `app/playback/PositionInterpolator.kt`


_Steps:_

- [ ] At the top of start() (L29-33), before the cancel, add `if (job?.isActive == true) return` so redundant per-push startPositionTicker() calls don't cancel/relaunch the loop.

- [ ] Move `tickCount = 0` out of start() and into stop() so redundant start() calls don't reset the 30s-report counter (stop() nulls job first, so a genuine restart still resets). The while-loop already re-reads playback.value each tick, so seek-to-0 callers still work.

- [ ] Add a unit test asserting tickCount survives a redundant start().


_Acceptance:_

- [ ] Test shows redundant start() neither cancels/relaunches the coroutine nor resets tickCount; the tickCount % 60 (30s) Connect position report can fire during steady playback.

- [ ] `cd src && ./gradlew :app:testProdDebugUnitTest` green.


_Risk:_ Keying on job?.isActive is safe because stop() nulls the job before a genuine restart; cover with the test.


#### [ ] `P1-T2` — TrackPlaybackHandler engine ticker: gate on play state and add teardown/release

**severity** medium · **thermal** low (2Hz near-no-op wakeups; real value is the never-cancelled scope leak) · **effort** S


_Files:_ `KotifyClient/api/playerstatus/TrackPlaybackHandler.kt`, `KotifyClient/api/SpotifyClient.kt`


_Steps:_

- [ ] Add `if (isPaused) return` at the top of startTicker() (L88-97) so scheduleAutoAdvance()/sendNewTrackSequence() cannot start a ticker for a paused track (isPaused is set before every start path).

- [ ] Call stopTicker() in localPauseImpl (L547-555, next to advanceJob?.cancel()) and in handleCommandImpl's pause branch (~L846, where playedThresholdJob?.cancel() sits).

- [ ] Add a release()/shutdown() method on TrackPlaybackHandler that calls stopTicker(), scope.cancel(), and playbackEngine.release(); invoke it from SpotifyClient.disconnect() (L529-558) so the loop and its scope don't leak past teardown.

- [ ] Verify a virtual-engine track paused in its last 500ms still advances on resume (resume restarts the ticker).


_Acceptance:_

- [ ] While paused, the 2Hz ticker stops (no wakeups); resume/seek/new-track re-arm it via scheduleAutoAdvance().

- [ ] After disconnect(), the handler scope is cancelled (no leaked coroutine).

- [ ] KotifyClient tests green; rebuild the obfuscated jar and copy to app/libs/KotifyClient.jar.


_Risk:_ Low. Resume/seek/new-track all call scheduleAutoAdvance()/sendNewTrackSequence which re-arm the ticker, so gating on play state doesn't lose end-detection; verify the last-500ms-paused resume case.


#### [ ] `P1-T3` — SyncedLyricsView: derive activeIndex via derivedStateOf to stop per-frame recomposition

**severity** low · **thermal** low (foreground-only; removes LazyColumn recompose churn while synced lyrics play) · **effort** S · **depends on** P0-4


_Files:_ `app/ui/screens/LyricsScreen.kt`


_Steps:_

- [ ] Delete the body-level `val posMs by smoothPosition` read at L335.

- [ ] Replace the activeIndex derivation (L337-339) with `val activeIndex by remember(lines, isUnsynced) { derivedStateOf { if (isUnsynced) -1 else lines.indexOfLast { smoothPosition.value >= it.startTimeMs }.coerceAtLeast(0) } }` so the composable only recomposes when the active line actually changes, not every frame.

- [ ] Leave the karaoke reveal (SyllableSyncedLine/LineSyncedLine reading smoothPosition.value inside drawWithContent, L502/L587) untouched — that is the draw phase and is inherent.


_Acceptance:_

- [ ] With synced lyrics playing, SyncedLyricsView recomposes once per lyric line instead of every frame (verify via recomposition counts); karaoke reveal still animates every frame.

- [ ] Build green.


_Risk:_ None behavioral; the O(n) indexOfLast now runs inside the derived block each frame but is cheap vs the recomposition churn removed. Depends only on this screen being open + playing.


#### [ ] `P1-T4` — Library pagination: key the near-end trigger on the filtered list (correctness)

**severity** medium · **thermal** none (correctness bug, not heat) · **effort** M


_Files:_ `app/ui/screens/LibraryScreen.kt`


_Steps:_

- [ ] In the grid items (L261-263) and list items (L273-275), change both the near-end comparison and the LaunchedEffect key from `library.size` to `sortedLibrary.size` so pagination fires when a filter/search shrinks the visible list.

- [ ] Cleaner alternative: hoist rememberLazyListState()/rememberLazyGridState() and drive one screen-level `snapshotFlow { layoutInfo.visibleItemsInfo.lastOrNull()?.index }.distinctUntilChanged().collect { if (it != null && it >= sortedLibrary.size - 10 && libraryHasMore) vm.loadMoreLibrary() }`. The existing `if (_isLoadingMoreLibrary.value) return` guard already prevents runaway.

- [ ] Note the client-side-filter caveat: if newly loaded pages contain few matching items the filtered list may still stall (keep-paging-until-enough is out of scope for KISS).


_Acceptance:_

- [ ] With a filter/search active, scrolling to the end still triggers loadMoreLibrary in both grid and list; unfiltered paging behaves as before.

- [ ] Build green.


_Risk:_ Medium — changes the paging trigger; verify load-more fires near the end in both grid and list and that filtered views now paginate.


#### [ ] `P1-T5` — Verify control-plane wakelock is not held on remote-only playback (verify-only, no code change)

**severity** low · **thermal** medium (continuous but intentional and correctly balanced) · **effort** S


_Files:_ `app/playback/MusicPlaybackService.kt`


_Steps:_

- [ ] Do NOT change the lock lifecycle: the PARTIAL_WAKE_LOCK + WIFI_MODE_FULL (L152-198) exist to keep the Connect dealer socket + screen-off auto-advance responsive (see L128-134); the team already downgraded HIGH_PERF->FULL. Per project no-speculative-code rule, leave it.

- [ ] On-device verify only: (a) the local player is NOT playWhenReady=true (so the lock is not held) while audio plays on a remote Connect device; (b) the lock releases on extended pause/idle.

- [ ] Only if a prolonged transient-suppression leak is actually observed, add a short grace timer in onPlaybackSuppressionReasonChanged (L302-322). Do NOT adopt isPlaying-keying or an unconditional STATE_ENDED release (both re-introduce the advance stall the lock prevents).


_Acceptance:_

- [ ] Device check confirms the lock is released within ~1s of pause and is not held during remote-only playback. No code change lands unless the grace-timer condition triggers.


_Risk:_ Weakening the lock regresses the screen-off auto-advance it was built for; treat as verify-only.


#### [ ] `P1-T6` — Migrate hot-screen collectors to collectAsStateWithLifecycle (stop background snapshot churn)

**severity** low · **thermal** low (background snapshot churn only; service wakelock keeps CPU awake anyway — include only because it is cheap + mechanical) · **effort** M


_Files:_ `app/ui/screens/SpotifyApp.kt`, `app/ui/screens/NowPlayingScreen.kt`, `app/ui/components/MiniPlayer.kt`, `app/ui/screens/LyricsScreen.kt`


_Steps:_

- [ ] collectAsState keeps collecting across ON_STOP, so while backgrounded during playback the 500ms positionMs emissions (plus themeColors/viz) keep firing setValue/invalidations on the ~177 collectAsState sites.

- [ ] Migrate the playback/theme/viz collectors on the hot screens to `collectAsStateWithLifecycle(minActiveState = Lifecycle.State.STARTED)` so collection suspends when the UI isn't visible. Playback is service-driven, so suspending UI collectors is safe.

- [ ] Verify each site: do NOT migrate a collector a background path depends on (there are none for pure UI state here).


_Acceptance:_

- [ ] Backgrounding during playback stops UI snapshot writes (no positionMs setValue while ON_STOP) — verify via debug log/profiler.

- [ ] Foreground/resume restores live updates immediately.

- [ ] Build + tests green.


_Risk:_ Over-migrating a collector a background code path needs could freeze state; audit each site. Broad but mechanical.


## P2 · WS-P2-VIEWMODEL — SpotifyViewModel decomposition and de-duplication (KISS)

Zero runtime/thermal cost, but the 3199-line god-object and its repeated boilerplate (duplicate stream-commit helpers, 15 hand-rolled IO launches, 5 copy-paste detail loaders, a 190-line resolveAndPlay, ~12 loose state flags) impede reasoning about which coroutine touches which state. Continue the incremental feature-ViewModel extraction (SearchViewModel is the one already split out) and land each with its own tests. Order: cheap mechanical dedups first; the god-object extraction and the state-flag refactor (the riskiest, touching the fragile cold-start ordering) LAST.


#### [ ] `P2-VM1` — Consolidate the two stream-commit helpers and extract the DRM load+commit tail

**severity** medium · **thermal** none · **effort** M


_Files:_ `app/viewmodel/SpotifyViewModel.kt`


_Steps:_

- [ ] Delete one of the byte-identical helpers (commitRecoveredStream L1481-1485 / commitEpisodeStream L2636-2640) and keep a single `commitStream(uri, provider)`.

- [ ] Add one suspend `loadDrmAndCommit(stream, title, artist, art, uri, provider, startPlaying, startPositionMs=0)` doing svc.stop() -> playUrlAt=now -> playDrmUrl(...) -> commitStream, and route the ~6 duplicated tails (L1327-1337, L1673-1684, L1468-1477, L2513-2528, L2614-2622, L2692-2703) through it.

- [ ] Parameterize startPlaying (`!coldStart` vs true) and startPositionMs (savedPositionAtEntry vs positionMs vs 0). Always calling stop() is safe (no-op when idle) so coldStartPlay can use it too; keep coldStartPlay's lossless pre-branch and resolveAndPlay's file-id acquisition OUTSIDE the helper.


_Acceptance:_

- [ ] One commit helper and one load+commit tail remain; adding a new playDrmUrl field is a single edit.

- [ ] PlaybackTestRig + episode tests green before/after; `:app:testProdDebugUnitTest` green.


_Risk:_ Call sites differ in startPlaying/startPositionMs — parameterize explicitly. coldStartPlay deliberately omits stop() today; verify the always-stop() variant is a no-op there.


#### [ ] `P2-VM2` — Migrate hand-rolled IO launches to launchWithSession / launchWithPlayer

**severity** low · **thermal** none · **effort** M


_Files:_ `app/viewmodel/SpotifyViewModel.kt`


_Steps:_

- [ ] Migrate session-only sites (fetchLyrics L1784, refreshQueue L1801, fetchCanvasForTrack L2106, loadMoreLibrary L2873, openLikedSongs L2893, loadMoreDetail L2910, openPlaylist L2971, openAlbum L2984, openArtist L2997, openShow L3014, checkDetailSaved L3035, toggleDetailSaved L3048, removeFromLibrary L3062) to `launchWithSession("tag") { sess -> ... }`.

- [ ] Route the three player-based sites (openAlbumFromCurrentTrack L1862, openArtistFromCurrentTrack L1908, loadDevices L3079) to `launchWithPlayer` instead.

- [ ] For detail loaders that flip isLoading/_isLoadingMore in finally, add a `launchWithSessionLoading(loadingFlag){ sess -> }` variant so the loading wrapper isn't re-typed. Migration also fixes the sites that currently swallow CancellationException.


_Acceptance:_

- [ ] Cited raw `viewModelScope.launch(Dispatchers.IO)` sites now use the helpers; CancellationException is re-thrown (helper does this).

- [ ] Behavior unchanged; `:app:testProdDebugUnitTest` + detekt green.


_Risk:_ A few sites set extra state in finally — keep explicit or extend the helper.


#### [ ] `P2-VM3` — Extract openDetail() and hoist the kotify_prefs helper/setters

**severity** low · **thermal** none · **effort** M


_Files:_ `app/viewmodel/SpotifyViewModel.kt`


_Steps:_

- [ ] Add `private fun openDetail(screen: Screen, load: suspend (Session) -> DetailData)` wrapping navigate + isLoading + try/finally, and collapse openPlaylist/openAlbum/openArtist/openShow/openLikedSongs (L2891-3027) onto it (keep openShow's publisher/imageUrl and openLikedSongs' offset=0 in the load lambda).

- [ ] Hoist `const val PREFS = "kotify_prefs"` and `private fun prefs(ctx) = ctx.getSharedPreferences(PREFS, MODE_PRIVATE)`, then collapse the 7 setters (L1973-2093) onto it. Leave setAppLanguage (L2018) out of the generic setter (it also recreates the Activity).


_Acceptance:_

- [ ] The literal "kotify_prefs" appears once (in PREFS); the 5 detail loaders share openDetail; behavior unchanged.

- [ ] `:app:testProdDebugUnitTest` + detekt green.


_Risk:_ Low, mechanical; preserve per-type specifics in the load lambda.


#### [ ] `P2-VM4` — Extract maybeUpdatePalette() for the 3x duplicated palette guard

**severity** low · **thermal** none · **effort** S


_Files:_ `app/viewmodel/SpotifyViewModel.kt`


_Steps:_

- [ ] Add `private fun maybeUpdatePalette(art: String?) { if (art != null && art != lastPaletteUrl) { lastPaletteUrl = art; extractColorsFromArt(art) } }`.

- [ ] Call it from updatePlaybackFromState (L998-1001, pass imageUrl), optimisticTapPlay (L1669), and resolveAndPlay (L2425-2428).


_Acceptance:_

- [ ] The dedupe-then-extract idiom exists once; palette extraction behavior unchanged (guard already prevents redundant extraction).

- [ ] Build green.


_Risk:_ None — identical semantics.


#### [ ] `P2-VM5` — Extract the delay(300) notification-resync block copy-pasted across 3 callbacks

**severity** low · **thermal** none · **effort** S


_Files:_ `app/viewmodel/SpotifyViewModel.kt`


_Steps:_

- [ ] Extract `private fun syncNotificationButtonsAfterCommand()` that delays once (hoist 300 to `NOTIFICATION_RESYNC_MS`) and mirrors isLiked/isShuffling/repeatMode + updateNotification; call it from onLikeToggle (L2177-2181), onShuffleToggle (L2185-2189), onRepeatToggle (L2193-2197).

- [ ] First verify whether the delayed re-sync is still needed at all: toggleShuffle/cycleRepeat already call pushTransportButtonsToNotification and like/unlike already call pushLikeToNotification synchronously. If redundant, drop it; if kept, one shared helper suffices.


_Acceptance:_

- [ ] The 300ms resync exists once (or is removed if redundant); notification button state still correct after a WS round-trip.

- [ ] Build green.


_Risk:_ None — same observable behavior. Confirm the delayed re-sync is still wanted given the toggles push synchronously.


#### [ ] `P2-VM6` — Refactor resolveAndPlay into a linear dispatch with per-source resolvers

**severity** medium · **thermal** none · **effort** M · **depends on** P2-VM1


_Files:_ `app/viewmodel/SpotifyViewModel.kt`


_Steps:_

- [ ] Extract `private suspend fun resolveSpotifyCdnStream(event): SpotifyStream` containing the cached-url / file-id acquisition + resolveForFileId from the CDN branch (L2464-2535), and reuse the P2-VM1 loadDrmAndCommit tail.

- [ ] resolveAndPlay (L2380-2570) then reads as a linear dispatch: ad -> sameUri -> episode -> preResolvedThirdParty -> cdn -> thirdParty. Extract mechanically WITHOUT reordering the try/catch fallback semantics; preserve the bare catch(Exception) at L2532 that falls through to the third-party fallback.


_Acceptance:_

- [ ] resolveAndPlay is a flat dispatch; the CDN acquisition lives in one resolver; skip/ad/cold-start/instant-tap paths behave identically.

- [ ] PlaybackTestRig + episode tests green before/after.


_Risk:_ Highest-traffic correctness path. Preserve the fallback catch; run the rig before/after.


#### [ ] `P2-VM7` — Continue the feature-ViewModel extraction (god-object decomposition)

**severity** medium · **thermal** none · **effort** L · **depends on** P2-VM2, P2-VM3


_Files:_ `app/viewmodel/SpotifyViewModel.kt`


_Steps:_

- [ ] Follow the the code plan (Search done -> Library -> Account -> Lyrics -> Detail). Extract read-only areas first as feature ViewModels that read Session from SessionHolder and reuse the launchWithSession shape SearchViewModel already uses: LibraryViewModel (library + detail loaders + save/follow), LyricsViewModel, DevicesViewModel, and a SettingsRepository for loadPreferences/effectiveRegion/setX.

- [ ] Screens call viewModel<FeatureViewModel>() instead of pulling the field off SpotifyViewModel. Move state+methods verbatim; no behavior change.

- [ ] Inline or event-bus cross-feature triggers (loadLibrary after createPlaylist L3113/L3071, refreshQueue after onTrackChange L597).

- [ ] Do NOT extract the playback core — use handler-extraction + PlaybackTestRig there (confirmed in code). Land each extraction with its own tests.


_Acceptance:_

- [ ] Each extracted feature compiles with its screen calling viewModel<Feature>(); SpotifyViewModel shrinks per feature.

- [ ] Full suite green after each landing: `:app:assembleDebug`, `:app:testProdDebugUnitTest`, detekt.


_Risk:_ Mechanical but touches many screens and cross-feature triggers; extract read-only areas first, leave playback in place, one feature per PR.


#### [ ] `P2-VM8` — Group cold-start / ad / recovery flags into small owned state holders (do LAST)

**severity** low · **thermal** none · **effort** M · **depends on** P2-VM7


_Files:_ `app/viewmodel/SpotifyViewModel.kt`


_Steps:_

- [ ] Group the cold-start handoff into one nullable holder ColdStart(fileId: CompletableDeferred<String>, savedPosition: Long) replacing coldStartPending+coldStartFileId; resetColdStart becomes `coldStart = null`.

- [ ] Group recovery into RecoveryState(uri, attempt) replacing playbackErrorRetries+recoveringUri.

- [ ] Keep suppressRemotePause semantics identical (reconnect also uses it via resyncAfterReconnect L621) and do NOT fold it into the cold-start holder.


_Acceptance:_

- [ ] Cold-start/recovery state lives in typed holders; the onPlaybackId/onTrackChange/onReady race still resolves correctly.

- [ ] PlaybackTestRig green before/after.


_Risk:_ Riskiest item in the set — touches the fragile cold-start ordering the code warns about. Cover with the rig; do this after all low-risk extractions.


#### [ ] `P2-VM9` — (Optional) Unify the file-id 100ms poll loops with the cold-start deferred

**severity** low · **thermal** none · **effort** M · **depends on** P2-VM6


_Files:_ `app/viewmodel/SpotifyViewModel.kt`


_Steps:_

- [ ] If unifying: extract one `suspend fun awaitFileId(timeoutMs): String?` that awaits a CompletableDeferred<String> armed BEFORE the WS transition and completed by onPlaybackId (episode path also completes it from onExternalUrl), keyed by trackUri and reset per transition. Reuse from coldStartPlay + resolveAndPlay (L2487-2491) + resolveAndPlayEpisode (L2661-2666), then delete both for-loops.

- [ ] Must short-circuit if latestFileId/event.currentFileId is already set (onPlaybackId/onTrackChange race). Given the tiny payoff, an acceptable lighter fix is to extract the shared loop into awaitFileId(timeoutMs) and keep the poll mechanism.

- [ ] Cover with PlaybackTestRig.


_Acceptance:_

- [ ] One file-id wait mechanism remains; both orderings of onPlaybackId/onTrackChange resolve without losing the pre-resolved cache.

- [ ] PlaybackTestRig green.


_Risk:_ Touches the documented onPlaybackId/onTrackChange race; the deferred must be armed before the transition and tolerate an already-cached id. Low value — deprioritize.


## P2 · WS-P2-UI — UI structural de-duplication and dead-code removal (KISS)

Copy-pasted layout branches and dialogs plus dead code. No runtime cost, but they force every behavior change to be made 2-5 times (a SourcePill divergence between orientations already exists). The NowPlaying dedup is the natural home for P0-2's shared PlaybackProgress leaf.


#### [ ] `P2-UI1` — Extract shared NowPlaying leaves to collapse the portrait/landscape duplication

**severity** medium · **thermal** low · **effort** L · **depends on** P0-2


_Files:_ `app/ui/screens/NowPlayingScreen.kt`


_Steps:_

- [ ] Hoist the shared non-layout pieces above the `if (isLandscape)` split: one DisposableEffect for the AudioDeviceCallback (L602-611 / L1035-1044), one jukebox elapsed ticker (L462-468 / L888-894), and the PlaybackProgress leaf from P0-2 (reused by both orientations).

- [ ] Extract PlayerControls(...) (the 5-button transport Row, L512-591 / L938-1025) and PlayerBottomBar(...) (L623-691 / L1057-1126), plus a top-level shareTrack(context, id, label) for the three share sites (L659-666, L1095-1101, L1324-1330).

- [ ] Reconcile the existing SourcePill divergence (landscape gates on provider?.let at L650, portrait always renders at L1086) while extracting. Both branches should then differ only in Row-vs-Column arrangement + sizes.


_Acceptance:_

- [ ] Controls/progress/bottom-bar/audio-output logic exists once; both orientations render identically to before (verify expand/collapse morph + both orientations on device).

- [ ] Build green.


_Risk:_ Layout/padding regressions between orientations; extract one leaf at a time and eyeball both.


#### [ ] `P2-UI2` — Extract RadioPickerDialog for the 5 copy-pasted AccountScreen pickers and remember static lists

**severity** medium · **thermal** none · **effort** M


_Files:_ `app/ui/screens/AccountScreen.kt`


_Steps:_

- [ ] Extract one `@Composable RadioPickerDialog(title, options: List<RadioOption>, selected, onSelect: (String)->Unit, onDismiss)` where RadioOption carries value/label/optional supportingText, and route BOTH the Row click and the RadioButton onClick through the single onSelect (collapsing the doubled set()+dismiss()).

- [ ] Replace Language (163-190), Lyrics (204-252), Region (355-413, preserve the code subtitle), Left notif (454-482) and Right notif (495-523, preserve per-option descriptions) with it.

- [ ] While here, wrap the two body-level lists in remember: `val languages = remember(systemDefaultLabel){ listOf(...) }` (L147-153) and `val buttonOptions = remember(...) { listOf(...) }` (L431-435).


_Acceptance:_

- [ ] One picker composable backs all five settings; Region subtitle and notif descriptions preserved; each setting still applies.

- [ ] Build green.


_Risk:_ Keep the optional supporting-text slot or Region/notif lose their subtitles. No runtime behavior change otherwise.


#### [ ] `P2-UI3` — Delete the dead ReleaseNotesScreen and share the OkHttpClient for release-notes fetch

**severity** low · **thermal** none · **effort** S


_Files:_ `app/ui/screens/ReleaseNotesScreen.kt`, `app/util/UpdateService.kt`


_Steps:_

- [ ] Delete the unreachable full-screen ReleaseNotesScreen (L41-115) + its local load() and now-orphaned imports (ArrowBack, ErrorOutline, Refresh, Scaffold/TopAppBar-only symbols); also drop the unused `scope`/rememberCoroutineScope at L231. Keep ReleaseNotesDialog and the shared ReleaseNoteCard/MarkdownText/cleanMarkdown/LazyColumn.

- [ ] In fetchReleaseNotes (L274) reuse a single OkHttpClient instead of `new OkHttpClient()` per open — either change UpdateService.client to internal, or add a small `object Http { val client = OkHttpClient() }`.

- [ ] Optional: cache the parsed list in a module-level `private var cachedReleaseNotes: List<ReleaseNote>?` (no TTL).


_Acceptance:_

- [ ] ReleaseNotesScreen is gone (grep shows only detekt-baseline references, which are suppressions); the dialog still fetches and renders; no fresh OkHttpClient per open.

- [ ] Build green.


_Risk:_ Deletion removes a stub for a future full-screen nav route — trivially restorable from git. Sharing OkHttpClient is safe by design.


## P2 · WS-P2-COMPOSE-MISC — Compose micro-optimizations and small correctness/cleanup fixes (KISS)

A cluster of small, mostly independent fixes: over-broad state subscriptions, per-frame value-form reads, unmemoized derivations, an index-based queue key, dead subscriptions, a stale volume slider, and a bounded queue-walk. Each is low/none thermal (children already skip under strong skipping) but cheap and improves hygiene. Do as a batch of small PRs.


#### [ ] `P2-M1` — SpotifyApp root: read currentTrackUri projection instead of the whole PlaybackUiState

**severity** low · **thermal** low · **effort** S


_Files:_ `app/ui/screens/SpotifyApp.kt`


_Steps:_

- [ ] Delete `val playback by vm.playback.collectAsState()` (L99); nothing else reads it.

- [ ] Replace the only use (L107) with `val hasTrack = vm.currentTrackUri.collectAsState().value != null` (reuse the existing distinctUntilChanged projection). TrackInfo.uri is non-null so this equals track != null.

- [ ] The root then recomposes on genuine track change (~once/song) instead of every 500ms.


_Acceptance:_

- [ ] SpotifyApp no longer recomposes 2x/sec during playback; MiniPlayer visibility still flips on track appear/disappear.

- [ ] Build green.


_Risk:_ Trivial; distinctUntilChanged only dedupes, transition timing unchanged.


#### [ ] `P2-M2` — shimmerBrush: share one infinite transition and read the phase in the draw phase

**severity** low · **thermal** low · **effort** S


_Files:_ `app/ui/components/SharedComponents.kt`, `app/ui/screens/HomeScreen.kt`


_Steps:_

- [ ] Hoist a single rememberInfiniteTransition/animateFloat phase (in HomeShimmer or shared) instead of one per ShimmerBox (14 in HomeShimmer).

- [ ] Change ShimmerBox to read the phase in the DRAW phase, e.g. `Modifier.drawWithCache { onDrawBehind { drawRect(Brush.linearGradient(listOf(SpotifyGray, SpotifyElevated, SpotifyGray), start = Offset(phase.value-500f,0f), end = Offset(phase.value,0f))) } }` so boxes redraw without recomposing. Both changes are needed — a shared transition alone still recomposes each box.


_Acceptance:_

- [ ] During the home load shimmer, one animation drives all placeholders and ShimmerBox recompositions drop to ~0 (only draws); sweep looks continuous.

- [ ] Build green.


_Risk:_ Minimal, transient (loading only); verify the gradient looks continuous with a shared phase.


#### [ ] `P2-M3` — DetailScreen compose cleanup bundle (conditional collect, derivedStateOf, stripHtml memo, pagination)

**severity** low · **thermal** none · **effort** M


_Files:_ `app/ui/screens/DetailScreen.kt`


_Steps:_

- [ ] Hoist the conditional collectAsState out of the short-circuited && (L194-195): `val playingContext by vm.playingContext.collectAsState()` at the top of the item lambda, then compute isPlayingThis from it.

- [ ] Replace `val shuffling by remember { derivedStateOf { playback.isShuffling } }` (L196) with `val shuffling = playback.isShuffling`.

- [ ] Memoize the HTML parses: `val bio = remember(detail.biography) { stripHtml(detail.biography!!) }` (L433) and the two descriptions (L111/L152); the sumOf memo (L124) is optional/low-value.

- [ ] Optional: drive pagination from one screen-level snapshotFlow instead of per-item LaunchedEffect (L313-316) — the `if (_isLoadingMore.value) return` guard already prevents duplicate fetches, so this is tidiness only.


_Acceptance:_

- [ ] playingContext collector is stable across play/pause; HTML no longer re-parses on pagination emissions; behavior unchanged.

- [ ] Build green.


_Risk:_ None; identical output.


#### [ ] `P2-M4` — Search 'All' preview: slice before mapping to UnifiedResult

**severity** low · **thermal** low · **effort** S


_Files:_ `app/ui/screens/SearchScreen.kt`


_Steps:_

- [ ] In sectionFor (L455-468) slice before mapping: `results.tracks.items.take(SECTION_PREVIEW).map { it.toUnified(vm, ctx) }` for each category branch (sectionFor is only used by the ALL preview path). singleFilterRows (L470-483) keeps full mapping.

- [ ] The now-redundant `.take(SECTION_PREVIEW)` at L437 can stay or be dropped.


_Acceptance:_

- [ ] The ALL view maps only the 4 previewed items per section, not the full list; preview renders identically.

- [ ] Build green.


_Risk:_ Low — preview already shows only 4; slicing raw items first is behavior-preserving.


#### [ ] `P2-M5` — QueueScreen: key rows by a stable non-positional id

**severity** low · **thermal** none · **effort** S


_Files:_ `app/ui/screens/QueueScreen.kt`, `app/viewmodel/SpotifyViewModel.kt`


_Steps:_

- [ ] Give each queue entry a stable non-positional id when the queue is built in the VM (e.g. track.queueId) and key on that at QueueScreen L78, so consuming the front of the queue doesn't shift every row's key.

- [ ] Do NOT key on track.uri alone — the queue can hold the same URI twice and duplicate keys crash Compose; the disambiguator must be a stable non-positional id.


_Acceptance:_

- [ ] Advancing/reordering the queue reuses existing rows/images instead of disposing+recreating them; no duplicate-key crash on repeated URIs.

- [ ] Build green.


_Risk:_ Low but requires a VM change; the id must be stable and unique. Given the negligible thermal payoff this is optional.


#### [ ] `P2-M6` — Delete the unused account collectAsState subscriptions

**severity** low · **thermal** none · **effort** S


_Files:_ `app/ui/screens/HomeScreen.kt`, `app/ui/screens/LibraryScreen.kt`


_Steps:_

- [ ] Delete `val account by vm.account.collectAsState()` at HomeScreen.kt:52 and LibraryScreen.kt:86 (grep confirms `account` is referenced nowhere else in either file).


_Acceptance:_

- [ ] Both dead subscriptions removed; screens compile and render unchanged.

- [ ] Build green.


_Risk:_ None — verified unused.


#### [ ] `P2-M7` — InterludeDots: use lambda-form graphicsLayer to defer animated reads to the draw phase

**severity** low · **thermal** low · **effort** S


_Files:_ `app/ui/screens/LyricsScreen.kt`


_Steps:_

- [ ] At L664 change the value-form `Modifier.graphicsLayer(scaleX = dotScale, scaleY = dotScale, alpha = dotAlpha)` to the lambda form `Modifier.graphicsLayer { scaleX = dotScale; scaleY = dotScale; alpha = dotAlpha }` so reads defer to draw.

- [ ] Optional: drive scale+alpha from a single animateFloat since they share the tween(600)+startOffset spec, halving the animation count.


_Acceptance:_

- [ ] InterludeDots no longer recomposes per frame during interludes (only draw invalidation remains); visual output identical.

- [ ] Build green.


_Risk:_ None; pure read-phase change.


#### [ ] `P2-M8` — LyricsScreen auto-scroll: call animateScrollToItem directly in the LaunchedEffect

**severity** low · **thermal** none · **effort** S


_Files:_ `app/ui/screens/LyricsScreen.kt`


_Steps:_

- [ ] Replace the inner `scope.launch { listState.animateScrollToItem(...) }` (L348, using rememberCoroutineScope) with a direct call in the LaunchedEffect(activeIndex) body so a new activeIndex cancels the previous scroll via structured concurrency.


_Acceptance:_

- [ ] A single structured in-flight scroll; behavior unchanged (MutatorMutex already prevented stacking).

- [ ] Build green.


_Risk:_ None; behavior slightly cleaner.


#### [ ] `P2-M9` — LibraryScreen: memoize filter/search/sort derivation

**severity** low · **thermal** low · **effort** S


_Files:_ `app/ui/screens/LibraryScreen.kt`


_Steps:_

- [ ] Wrap the three derived lists (L98-113) in `remember(library, selectedFilter, searchQuery, sortMode) { ... }` returning the final sortedLibrary. All four inputs must be in the key.

- [ ] This saves recompute on unrelated recompositions (grid toggle, sort-menu open, account/libraryTotal emissions); it cannot help mid-typing (searchQuery key changes each keystroke).


_Acceptance:_

- [ ] Toggling grid/opening the sort menu no longer re-runs the filter/sort pass; filtered results unchanged.

- [ ] Build green.


_Risk:_ Low — include all four inputs in the key or the list goes stale.


#### [ ] `P2-M10` — DevicesDialog volume slider: reconcile with live remote volume when not dragging

**severity** low · **thermal** none · **effort** S


_Files:_ `app/ui/components/DevicesDialog.kt`


_Steps:_

- [ ] At L157 keep the unkeyed remember but add `var dragging by remember { false }` (set true in onValueChange, false in onValueChangeFinished after calling setSpotifyVolume).

- [ ] In the composable body add `if (!dragging) volumeValue = playback.volume.toFloat()` so external volume changes reflect without fighting an active gesture.


_Acceptance:_

- [ ] Changing volume externally (another controller / hardware keys) updates the slider when not dragging; dragging is not disrupted.

- [ ] Build green.


_Risk:_ Low — gate reconciliation on the dragging flag.


#### [ ] `P2-M11` — UpdateService.downloadApk: emit progress at 1% granularity

**severity** low · **thermal** none · **effort** S


_Files:_ `app/util/UpdateService.kt`


_Steps:_

- [ ] Track `var lastPct = -1` in the read loop (L110-121) and call onProgress only when `(bytesRead * 100 / totalBytes).toInt()` changes (update lastPct on emit), instead of every 8KB chunk (L119).


_Acceptance:_

- [ ] Progress emits in 1% steps (avoids thousands of Float state writes); progress bar still advances during download.

- [ ] Build green.


_Risk:_ None; progress advances in 1% steps.


#### [ ] `P2-M12` — Queue-skip no-uid fallback: cap the localNext walk

**severity** low · **thermal** low · **effort** S


_Files:_ `app/viewmodel/SpotifyViewModel.kt`


_Steps:_

- [ ] In skipToQueueIndex change the no-uid fallback (L1741) to `repeat(minOf(index + 1, MAX_LOCAL_WALK)) { p.localNext(); delay(300) }` with a small const (e.g. MAX_LOCAL_WALK = 5), logging when capped so the user can tap again. Keep the uid-present primary path (skipToTrack) unchanged.


_Acceptance:_

- [ ] Tapping a deep queue row with no uid fires at most MAX_LOCAL_WALK localNext() calls instead of index+1; uid-present rows still jump directly.

- [ ] Build green.


_Risk:_ Deep no-uid jumps become approximate/capped — acceptable for an already best-effort fallback (target uid is null so skipToTrack isn't possible).


#### [ ] `P2-M13` — MainActivity: replace magic delay(500)/delay(600) with a service-readiness signal (optional)

**severity** low · **thermal** none · **effort** M


_Files:_ `app/MainActivity.kt`


_Steps:_

- [ ] Expose a readiness StateFlow<Boolean> on SessionHolder/MusicPlaybackService that flips true when MusicPlaybackService.instance is set and bindable.

- [ ] Collect it to trigger wireServiceControls() (replacing delay(500) at L116) and chain autoplay off it (replacing delay(600) at L128).


_Acceptance:_

- [ ] wireServiceControls + autoplay fire off the readiness signal, not fixed sleeps; headphone cold-launch autoplay still works.

- [ ] Device-test the headphone cold-launch path (force-stop + relaunch after install per project memory).


_Risk:_ A wrong readiness signal could reintroduce the wire/autoplay race the delays hide; verify on the headphone cold-launch path before shipping. Low priority.


## P3 · WS-P3-COMMENTS — Comment hygiene and cosmetics

Zero runtime impact. Fix the four misplaced KDoc blocks that actively mislead (they document the wrong function), remove dead diagnostic scaffolding and cargo-cult suppressions, and trim pure widget-naming restatement comments. Preserve the load-bearing protocol/rationale comments (ad-skip, cold-start, DRM, wake-lock, karaoke-mask) this repo intentionally keeps.


#### [ ] `P3-1` — SpotifyViewModel: reattach 4 misplaced KDoc blocks and delete 3 cast-free @Suppress

**severity** low · **thermal** none · **effort** S


_Files:_ `app/viewmodel/SpotifyViewModel.kt`


_Steps:_

- [ ] Move each KDoc onto the function it describes: jukebox-toggle doc (L1098) -> toggleJukebox (L1127); auto-recover doc (L1361) -> recoverFromPlaybackError (L1384); ExoPlayer-lifecycle doc (L2201) -> wirePlaybackLifecycleCallbacks (L2226); podcast-episode doc (L2571-block) -> resolveAndPlayEpisode (L2642).

- [ ] Delete the three cast-free `@Suppress("UNCHECKED_CAST")` at L1768 (openQueue), L1860 (openAlbumFromCurrentTrack), L2786 (preResolveNextTrack) — none of those bodies contains a cast.

- [ ] Do NOT bulk-strip the multi-paragraph ad-skip/cold-start/DRM rationale essays — they encode protocol knowledge.


_Acceptance:_

- [ ] Each of the four functions is documented by its own KDoc; the three dead suppressions are gone; detekt green.

- [ ] Build green.


_Risk:_ None — comments/annotations only.


#### [ ] `P3-2` — MusicPlaybackService: move the orphaned KDoc onto setIdleMetadata

**severity** low · **thermal** none · **effort** S


_Files:_ `app/playback/MusicPlaybackService.kt`


_Steps:_

- [ ] Move the L663-673 KDoc ('Push idle metadata...') from above refreshStreamingMetadata to directly above `fun setIdleMetadata` (L692), which currently has none. Leave refreshStreamingMetadata's own KDoc (L674-679) in place.

- [ ] Keep all the domain 'why' comments in this file (WIFI_MODE rationale, wake-lock reasoning, PSSH/Widevine, ad-skip trick).


_Acceptance:_

- [ ] setIdleMetadata is documented; refreshStreamingMetadata keeps its correct doc.

- [ ] Build green.


_Risk:_ None.


#### [ ] `P3-3` — Delete the diagnostic-only screen ON/OFF BroadcastReceiver

**severity** low · **thermal** none · **effort** S


_Files:_ `app/playback/MusicPlaybackService.kt`


_Steps:_

- [ ] Delete screenReceiver (L140-150), its IntentFilter registration (L206-210), and the onDestroy unregister (L1205). Its only side effect is a Loki log; it was one-off scaffolding for the control-plane-lock investigation (per the no-speculative-code / no-pfusch memory rule).


_Acceptance:_

- [ ] No SCREEN_ON/OFF receiver registered; service builds and runs unchanged.

- [ ] Build green.


_Risk:_ Loses the screen-state log line; no functional impact.


#### [ ] `P3-4` — NowPlayingScreen: delete ~12 widget-naming restatement comments

**severity** low · **thermal** none · **effort** S


_Files:_ `app/ui/screens/NowPlayingScreen.kt`


_Steps:_

- [ ] Delete the pure-restatement lines: L318, L337, L344, L399, L511, L937, L943, L957, L968, L989, L1007 (and re-confirm L775 '// EQ button', L815 '// Track info + like button' at edit time).

- [ ] KEEP the rationale comments: PlayerBackground KDoc (L51-58), scrim/overlay (L187-204), ad-skip freeze (L260-262), weight(1f) ellipsize (L361-363), remote-Connect-device (L618-619).


_Acceptance:_

- [ ] Restatement labels removed; rationale comments retained; file compiles.

- [ ] Build green.


_Risk:_ None.


#### [ ] `P3-5` — LyricsScreen: delete ~7 restatement/banner comments

**severity** low · **thermal** none · **effort** S


_Files:_ `app/ui/screens/LyricsScreen.kt`


_Steps:_

- [ ] Delete the restatement/banner lines: L92, L108, L117, L125, L135, L151, L224, L258.

- [ ] KEEP the position-interpolation rationale (L65-67), the auto-scroll guard note (L344), and the base/overlay karaoke-mask reveal comments (L481/L490/L505-521).


_Acceptance:_

- [ ] Banner/label comments removed; interpolation and karaoke-reveal explanations retained.

- [ ] Build green.


_Risk:_ None.


#### [ ] `P3-6` — DetailScreen: delete 5 widget-naming restatement labels

**severity** low · **thermal** none · **effort** S


_Files:_ `app/ui/screens/DetailScreen.kt`


_Steps:_

- [ ] Delete L82, L262, L273, L524, L531.

- [ ] KEEP L538 ('Track name only (no artist...)'), the DetailHeaderMenu KDoc (L769-777), and the LazyColumn section markers (navigation aids).


_Acceptance:_

- [ ] The 5 restatement labels removed; intent comments and section markers retained.

- [ ] Build green.


_Risk:_ None.


#### [ ] `P3-7` — formatReleaseDate: capture non-null locals to drop the redundant !!

**severity** low · **thermal** none · **effort** S


_Files:_ `app/data/KotifyMappers.kt`


_Steps:_

- [ ] In formatReleaseDate (L34-63) capture non-null locals before the range check so `month` smart-casts, e.g. `val mo = parts[1].toIntOrNull(); if (... && mo != null && mo in 1..12 && ...) MONTHS[mo-1] ...`, deleting the `month!! - 1` at L44 and L54 (both provably non-throwing).


_Acceptance:_

- [ ] No `!!` in formatReleaseDate; output identical for valid/invalid dates.

- [ ] Build + detekt green.


_Risk:_ None.


---

## Quick wins (do first within their tier — S effort, high payoff)

- [ ] P0-1 Freeze/throttle the Fluid background clock (S effort, HIGHEST thermal payoff — removes the largest continuous GPU source, which currently runs full-tilt even while paused).

- [ ] P0-3 MarqueeText finite iterations (S) — stops the only per-frame animation that keeps the now-playing panel awake while paused.

- [ ] P0-4 Gate the LyricsScreen frame loop on synced availability (S) — kills continuous 60-120Hz frame production for unsynced/no-lyrics playback.

- [ ] P1-IO2 AlbumArtPalette shared Coil loader + .size(112) (S) — one-line loader swap removes a redundant full-res fetch+decode per skip.

- [ ] P1-IO3 DeezerDecryptProxy init Cipher once per stream (S) — eliminates ~5000 Blowfish key expansions per track, correctness-neutral.

- [ ] P1-IO4 loadDevices() dedupe on state push (S) — stops several redundant getDevices() network calls per track on foreign devices.

- [ ] P1-IO6 HttpClient delay() instead of Thread.sleep (S) — frees a blocked IO thread and makes 429 back-off cancellable.

- [ ] P1-IO8 MainActivity AtomicBoolean update-check dedupe (S) — stops a GitHub round-trip on every rotation/dark-mode toggle.

- [ ] P1-T1 PositionInterpolator.start() idempotent (S) — removes per-push cancel/relaunch churn and lets the 30s Connect report actually fire.

- [ ] P2-VM4 maybeUpdatePalette() extraction (S) and P2-M6 delete dead account subscriptions (S) — trivial DRY/dead-code wins.

- [ ] P3-3 Delete the diagnostic screen ON/OFF receiver (S) — removes leftover scaffolding (dead scaffolding).


---

## Appendix — all 86 verified findings (grouped)


### Thermal / CPU-GPU (16)

- **[high/therm:high]** Fluid Kawarp shader re-renders full-screen at 60fps even while paused (clock never stops)  
  `app/ui/components/FluidBackground.kt` — KawarpBackground frame loop L82-92; WarpedLayer graphicsLayer L127-149; BLUR_RADIUS L159

- **[high/therm:high]** Fluid album background re-runs a full-screen 120px blur + 2 AGSL shaders every frame and never idles when paused  
  `app/ui/components/FluidBackground.kt` — KawarpBackground frame pump L82-92; WarpedLayer graphicsLayer L127-149. Reached from NowPlayingScreen via AlbumBackdrop -> FluidAlbumBackground (NowPlayingScreen.kt L228-242).

- **[medium/therm:low]** Jukebox viz loop emits at ~8 Hz even while paused and re-forces recomposition every tick  
  `app/playback/JukeboxController.kt` — startViz() lines 311-330; JukeboxViz class lines 32-37; VIZ_TICK_MS line 49

- **[medium/therm:medium]** WaveformAnalyzer O(n^2) re-analysis re-run over the whole growing buffer every 5s during capture  
  `app/playback/JukeboxController.kt` — runSeamlessJukebox preview block lines 168-174; PREVIEW_EVERY_S line 51; growToFullTrack lines 266-278

- **[medium/therm:medium]** While playing, warp+120px-blur+grade chain redraws and reallocates 4 RenderEffects at full vsync (60fps)  
  `app/ui/components/FluidBackground.kt` — WarpedLayer graphicsLayer L127-149; warp shader snoise x4 L201-204; grade double-saturation+dither L235/L238-239/L242; BLUR_RADIUS L159

- **[medium/therm:medium]** Per-frame position-interpolation loop runs even for UNSYNCED or missing lyrics  
  `app/ui/screens/LyricsScreen.kt` — LaunchedEffect(isPlaying) while-loop, L81-89

- **[medium/therm:low]** Engine ticker polls at 2 Hz and never stops on pause or teardown  
  `KotifyClient/api/playerstatus/TrackPlaybackHandler.kt` — startTicker() L88-97 (engineTickMs=500L, L75); stopTicker() reachable only from sendStateClearImpl L676; localPauseImpl L547-555 and handleCommandImpl else/pause branch (scheduleAutoAdvance L844) never stop it; SpotifyClient.disconnect() L529-558 never cancels the handler scope (scope, TrackPlaybackHandler L70)

- **[medium/therm:medium]** Kawarp fluid background never idles: the warp clock keeps pumping frames when paused, rebuilding a 120px blur + two AGSL shaders every frame  
  `app/ui/components/FluidBackground.kt` — KawarpBackground warp clock L82-92; WarpedLayer graphicsLayer L127-149

- **[low/therm:low]** File-id 100ms poll loops duplicate the event-driven CompletableDeferred cold-start already uses  
  `app/viewmodel/SpotifyViewModel.kt` — resolveAndPlay L2487-2491 (`for (i in 1..15) { delay(100); fileId = latestFileId; if (fileId != null) break }`) and resolveAndPlayEpisode L2661-2666 (`for (i in 1..8) { delay(100); ... }`); cold-start deferred armed L1231/completed L510-513/awaited L1251

- **[low/therm:low]** Queue-skip no-uid fallback fires localNext() index+1 times at 300ms intervals  
  `app/viewmodel/SpotifyViewModel.kt` — skipToQueueIndex L1729-1745, specifically L1741 `repeat(index + 1) { p.localNext(); delay(300) }`

- **[low/therm:low]** Position ticker cancel+relaunched on every state push, resetting the 30s Connect-report counter  
  `app/viewmodel/SpotifyViewModel.kt` — startPositionTicker() L1095 called per-push from updatePlaybackFromState L1011-1012 (+ L647, L725, L1155, L2314, L2324); PositionInterpolator.start() L29-33 (`job?.cancel(); tickCount = 0; job = scope.launch{ while(true){...} }`), report gate L45 `if (isStreaming.value && tickCount % 60 == 0)`

- **[low/therm:low · PLAUSIBLE]** PARTIAL_WAKE_LOCK held through transient focus loss and past STATE_ENDED  
  `app/playback/MusicPlaybackService.kt` — onPlayWhenReadyChanged lines 271-300; onPlaybackSuppressionReasonChanged lines 302-322; acquire/release L153-198

- **[low/therm:low]** MarqueeText uses iterations = Int.MAX_VALUE — title/artist/album scroll forever, pinning a per-frame animation callback  
  `app/ui/screens/NowPlayingScreen.kt` — MarqueeText() L1384-1387 `.basicMarquee(iterations = Int.MAX_VALUE, velocity = 40.dp)`; used portrait-only at L822/L829/L837.

- **[low/therm:low]** HomeShimmer spins up 14 independent infinite animations while loading  
  `app/ui/screens/HomeScreen.kt` — HomeShimmer() lines 165-199, backed by shimmerBrush() in SharedComponents.kt lines 95-114

- **[low/therm:low]** Jukebox re-analyzes the whole growing PCM buffer repeatedly and publishes viz unconditionally every 120ms while active  
  `app/playback/JukeboxController.kt` — Preview reanalyze L168-173 (every PREVIEW_EVERY_S=5s during capture); growth reanalyze L266-275 (every REANALYZE_GROWTH_S=30s post-handoff); startViz L311-330 (VIZ_TICK_MS=120ms); startLoopGuard L294-308 (1s); capture loop L156-185 (1s).

- **[low/therm:medium]** Control-plane PARTIAL_WAKE_LOCK + WIFI_MODE_FULL held for the whole play session (intentional, correctly balanced)  
  `app/playback/MusicPlaybackService.kt` — acquireControlPlaneLocks L152-182; releaseControlPlaneLocks L184-198; toggled in onPlayWhenReadyChanged L271-278; released in onDestroy L1206 and via onTaskRemoved->stopSelf L1140-1146.


### Compose recomposition (23)

- **[high/therm:medium]** rememberSmoothPositionMs returns Long, leaking its per-frame state write into the caller's recompose scope (mini-player recomposes every frame while playing)  
  `app/ui/components/SharedComponents.kt` — rememberSmoothPositionMs L164-182 (`return displayed` L181). Callers: MiniPlayer.kt L149-151; NowPlayingScreen.kt L471-472/L505 and L897-898/L931.

- **[medium/therm:low]** rememberSmoothPositionMs read at NowPlayingScreen top scope recomposes the ENTIRE screen every display frame while playing  
  `app/ui/screens/NowPlayingScreen.kt` — Portrait: L897-898 read inside the portrait Column content lambda (698-1129). Landscape: L471-472 inside the right Column content lambda (338-694). Helper: SharedComponents.kt L165-182.

- **[medium/therm:low]** MiniPlayerContent recomposes every frame while the mini bar is visible (same smoothPos-at-top-scope bug)  
  `app/ui/components/MiniPlayer.kt` — MiniPlayerContent() L91-162; read at L149 inside `if (playback.durationMs > 0)`, within the outer Column content lambda (L107).

- **[medium/therm:low]** rememberSmoothPositionMs returns a raw Long, recomposing the whole consumer every display frame during playback  
  `app/ui/components/SharedComponents.kt` — rememberSmoothPositionMs L164-182 (withFrameNanos loop L174-179, `return displayed` L181). Consumers: MiniPlayer.kt MiniPlayerContent L149; NowPlayingScreen.kt L471-472 and L897-898.

- **[medium/therm:medium]** Per-frame smoothPosition write forces the whole synced-lyrics list to recompose every frame  
  `app/ui/screens/LyricsScreen.kt` — SyncedLyricsView L335-339 (composition read) driven by the frame loop L81-89

- **[medium/therm:none]** Library pagination driven by LaunchedEffect inside each lazy item; silently stops when a filter/search is active  
  `app/ui/screens/LibraryScreen.kt` — Grid items lines 261-263 and list items lines 273-275

- **[medium/therm:medium]** rememberSmoothPositionMs returns a raw Long, forcing per-frame recomposition of the mini-player and now-playing subtrees while playing  
  `app/ui/components/SharedComponents.kt` — rememberSmoothPositionMs() L164-182; callers MiniPlayer.kt L149-151, NowPlayingScreen.kt L471-474 & L505 (landscape), L897-900 & L931 (portrait)

- **[low/therm:low]** NowPlayingScreen reads the whole PlaybackUiState at top scope, so every 500ms position tick re-executes the entire screen  
  `app/ui/screens/NowPlayingScreen.kt` — NowPlayingScreen L258 `val playback by vm.playback.collectAsState()`; PlayerBackground L61; MiniPlayerContent MiniPlayer.kt L95.

- **[low/therm:low]** jukeboxViz (and jukebox elapsed timer) read at NowPlayingScreen top scope recompose the whole screen every ~120ms / 1s while jukebox is active  
  `app/ui/screens/NowPlayingScreen.kt` — jukeboxViz read L460 (landscape) / L886 (portrait); jukebox elapsed LaunchedEffect L462-468 / L888-894, label read L506 / L932.

- **[low/therm:low]** animateColorAsState (animatedPrimary / animatedCardBg) read at top scope drives per-frame full-tree recompose during the 800ms tween on every track change  
  `app/ui/screens/NowPlayingScreen.kt` — NowPlayingScreen L278; PlayerBackground L64-65; MiniPlayer.kt L64-67 (animatedCardBg) and L103 (animatedPrimary).

- **[low/therm:low]** App root collects the full playback StateFlow only to test track != null, recomposing SpotifyApp every 500ms  
  `app/ui/screens/SpotifyApp.kt` — L99 `val playback by vm.playback.collectAsState()`; used only at L107 `hasTrack = playback.track != null` (then hasTrack at L112, L158, L239).

- **[low/therm:low]** shimmerBrush spins a separate infiniteTransition per ShimmerBox (~14 in HomeShimmer), each recomposing per frame  
  `app/ui/components/SharedComponents.kt` — shimmerBrush L95-109 (rememberInfiniteTransition L97 + animateFloat L98-103, animated float read in composition to build the Brush L104-108); ShimmerBox L111-114. Fanned out by HomeScreen.kt HomeShimmer L164-198.

- **[low/therm:low]** InterludeDots animates via value-form graphicsLayer, recomposing every frame during interludes  
  `app/ui/screens/LyricsScreen.kt` — InterludeDots L638-668 (animated reads consumed at L664)

- **[low/therm:none]** Conditional collectAsState() inside a short-circuited && re-creates the collector on play/pause  
  `app/ui/screens/DetailScreen.kt` — Action-buttons item, L194-195

- **[low/therm:none]** Auto-scroll launches in a detached scope, escaping LaunchedEffect cancellation  
  `app/ui/screens/LyricsScreen.kt` — SyncedLyricsView L346-354

- **[low/therm:none]** Uncached Html.fromHtml parse (and O(n) duration sum) in composition  
  `app/ui/screens/DetailScreen.kt` — L433 stripHtml(biography); L111 & L152 stripHtml(description); L124 sumOf

- **[low/therm:low]** Library filter + search + sort run over the whole list on every recomposition (no remember)  
  `app/ui/screens/LibraryScreen.kt` — LibraryScreen(), lines 98-113 (filteredLibrary / searchedLibrary / sortedLibrary)

- **[low/therm:low]** Search 'All' view maps every result item to UnifiedResult (up to 6-action menus) then discards all but 4  
  `app/ui/screens/SearchScreen.kt` — CategorizedResults() lines 431-440; sectionFor() lines 455-468; SECTION_PREVIEW=4 line 451

- **[low/therm:none]** QueueScreen keys rows by list index, defeating row/image reuse as the queue advances  
  `app/ui/screens/QueueScreen.kt` — LazyColumn itemsIndexed key, line 78

- **[low/therm:none · PLAUSIBLE]** AccountScreen is one monolithic composable, so toggling any single setting recomposes the entire settings screen and re-allocates its static option lists  
  `app/ui/screens/AccountScreen.kt` — AccountScreen() body lines 44-647; unremembered lists at 147-153 (languages) and 431-435 (buttonOptions)

- **[low/therm:none]** DevicesDialog volume slider seeds from remember{} with no key, so it goes stale against remote volume changes  
  `app/ui/components/DevicesDialog.kt` — line 157 var volumeValue by remember { mutableFloatStateOf(playback.volume.toFloat()) }

- **[low/therm:none]** UpdateService.downloadApk emits onProgress every 8 KB chunk  
  `app/util/UpdateService.kt` — downloadApk() read loop L110-121; onProgress(bytesRead.toFloat() / totalBytes) L119

- **[low/therm:low]** SyncedLyricsView reads the per-frame smoothPosition in its body, recomposing the lyrics LazyColumn every frame  
  `app/ui/screens/LyricsScreen.kt` — SyncedLyricsView L335-339


### Audio / DSP (7)

- **[medium/therm:medium]** Full-track FFT + O(n²) self-similarity re-run repeatedly per track, ~19 of them purely for a cosmetic histogram  
  `app/playback/JukeboxController.kt` — runSeamlessJukebox preview loop L168-174; handOffToEngine L194; growToFullTrack L266-274; WaveformAnalyzer.analyze L42-104

- **[medium/therm:low]** Cipher.getInstance()+init called for every decrypted Deezer block instead of once per stream  
  `app/playback/DeezerDecryptProxy.kt` — decryptBlock L213-217, called from decryptInto loop L177-190

- **[medium/therm:low]** ~63 MB PCM capture buffer allocated on first track and held for the sink's lifetime even when the jukebox is never used  
  `app/playback/JukeboxAudioTap.kt` — onConfigure L62-72 (needed = sampleRate*channels*60*MAX_MINUTES, MAX_MINUTES=6); wired unconditionally at MusicPlaybackService.kt:239

- **[low/therm:low]** JukeboxAudioTap stays in the audio chain and copies every PCM buffer even when the jukebox is off  
  `app/playback/JukeboxAudioTap.kt` — queueInput() lines 74-87; capture() lines 89-97; isActive() not overridden; install site MusicPlaybackService L233-241

- **[low/therm:low · PLAUSIBLE]** @Synchronized multi-MB snapshot copies briefly block the audio-processor thread; some redundant copies  
  `app/playback/JukeboxAudioTap.kt` — snapshotMono L41-55 / snapshotInterleaved L37-38 (@Synchronized) vs capture L89-97 (@Synchronized); callers JukeboxController L169, L193/L204, L267/L270

- **[low/therm:low]** capture() reads PCM one sample at a time via ByteBuffer.short on the audio thread  
  `app/playback/JukeboxAudioTap.kt` — capture L89-97

- **[low/therm:none]** Hann window recomputed (2048 cos) plus scratch arrays reallocated on every analyze() call  
  `app/playback/WaveformAnalyzer.kt` — analyze L53-58 (feats/energy/window/re/im/mag) and hann L172


### IO / network / lifecycle (13)

- **[medium/therm:low]** Album-art loaded at full resolution with no downsampling, no cache, and no HTTP timeout; same art re-fetched on skip  
  `app/playback/MusicPlaybackService.kt` — loadBitmap() lines 1126-1138; callers: setIdleMetadata L716, playUrl L487, playDrmUrl L887, setNextUrl prefetch L746, updateMetadata L901

- **[medium/therm:low]** AlbumArtPalette builds a fresh Coil ImageLoader per call instead of the shared singleton  
  `app/util/AlbumArtPalette.kt` — extractThemeColorsFromArt, L20 `val loader = ImageLoader(context)`

- **[medium/therm:low]** LokiLogger buffer never drains in production (LOKI_ENDPOINT empty) — unbounded queue growth  
  `app/util/LokiLogger.kt` — log() L70-90 (buffer.add L78); init()/flushJob L50-55; KotifyApp.kt L14-30 gates init on LOKI_ENDPOINT; build.gradle.kts L47 defaults LOKI_ENDPOINT to ""

- **[medium/therm:low]** MainActivity re-runs GitHub update check on every activity recreation (no configChanges)  
  `app/MainActivity.kt` — LaunchedEffect(Unit) L89-111 (updateInfo remember L86, loadPreferences L90, loadCookies L92, checkForUpdates L102-110); AndroidManifest.xml L26-31 (MainActivity has no android:configChanges)

- **[low/therm:low]** loadDevices() fires a network getDevices() on every state push while a foreign device is active  
  `app/viewmodel/SpotifyViewModel.kt` — updatePlaybackFromState L1038-1045 (`else if (state.has_active_device) { loadDevices() }`), invoked per onState push at L567-570; loadDevices L3078-3095 (`player?.getDevices()`)

- **[low/therm:low]** Palette request decodes full-resolution art though Palette only needs ~112px  
  `app/util/AlbumArtPalette.kt` — extractThemeColorsFromArt, request builder L21-24; Palette.from(bitmap).generate() L28

- **[low/therm:none]** Pagination LaunchedEffect duplicated across up to 5 near-end items  
  `app/ui/screens/DetailScreen.kt` — Tracks itemsIndexed, L313-316

- **[low/therm:none]** ReleaseNotes dialog re-fetches the GitHub releases API on every open with a brand-new OkHttpClient and no caching  
  `app/ui/screens/ReleaseNotesScreen.kt` — ReleaseNotesDialog LaunchedEffect lines 237-244; fetchReleaseNotes line 274 (new OkHttpClient())

- **[low/therm:low · PLAUSIBLE]** LokiLogger periodic 10s flush + fresh connection per ERROR (dev builds only)  
  `app/util/LokiLogger.kt` — flush loop L50-55 (FLUSH_INTERVAL_MS=10_000 L26); per-error flush L87-89; sendToLoki HttpURLConnection L155-173 (disconnect L173)

- **[low/therm:low]** Album-art load + notification-refresh block is copy-pasted across service methods and decodes full-size bitmaps via raw URL (no downsample, no cache reuse)  
  `app/playback/MusicPlaybackService.kt` — loadBitmap L1126-1138; launch+load(+post) blocks in playUrl L486-494, setIdleMetadata L715-724, setNextUrl L745-747, updateMetadata L900-906, playDrmUrl L886-894.

- **[low/therm:low]** buildNotification() recreates all 5 broadcast PendingIntents on every notification update  
  `app/playback/MusicPlaybackService.kt` — buildNotification L985-1056 (PendingIntent.getBroadcast x5 at L989-1013); called from updateNotification L978-983 on every isPlaying/playbackState/seek/metadata change.

- **[low/therm:none]** HttpClient blocks a Dispatchers.IO thread with Thread.sleep on 429 retry  
  `KotifyClient/http/HttpClient.kt` — request() L263 and getRaw() L361 — both inside withContext(Dispatchers.IO)

- **[low/therm:low]** Notification album art decoded at full resolution via raw URL stream, bypassing Coil cache and downsampling  
  `app/playback/MusicPlaybackService.kt` — loadBitmap() L1126-1138; callers L487 (playUrl), L716 (setIdleMetadata), L746 (setNextUrl prefetch), L887 (playDrmUrl), L901 (updateMetadata)


### KISS / structure (19)

- **[medium/therm:none]** SpotifyViewModel is a 3199-line god-object owning ~15 unrelated feature domains  
  `app/viewmodel/SpotifyViewModel.kt` — whole file; class decl L55-56 (@Suppress("TooManyFunctions")); 3199 lines; 50 viewModelScope.launch sites

- **[medium/therm:none]** Five near-identical RadioButton picker dialogs are copy-pasted inline in AccountScreen (~250 lines of duplication)  
  `app/ui/screens/AccountScreen.kt` — Language 163-190, Lyrics 204-252, Region 355-413, Left notif 454-482, Right notif 495-523

- **[medium/therm:none]** God-object ViewModel: ~18 unrelated responsibilities in one 3199-line class  
  `src/app/src/main/java/ch/snepilatch/app/viewmodel/SpotifyViewModel.kt` — whole class SpotifyViewModel, L56-3199 (@Suppress("TooManyFunctions") L55)

- **[medium/therm:none]** Two byte-identical stream-commit helpers + ~6 copies of the DRM stop→play→commit tail  
  `src/app/src/main/java/ch/snepilatch/app/viewmodel/SpotifyViewModel.kt` — commitRecoveredStream L1481-1485 & commitEpisodeStream L2636-2640 (identical bodies); DRM load+commit tail at L1327-1337, L1673-1684, L1468-1477, L2513-2528, L2614-2622, L2692-2703

- **[medium/therm:none]** resolveAndPlay is a ~190-line, deeply-nested method with a duplicated load tail  
  `src/app/src/main/java/ch/snepilatch/app/viewmodel/SpotifyViewModel.kt` — resolveAndPlay L2380-2570; Spotify-CDN branch L2464-2535

- **[medium/therm:low]** NowPlayingScreen portrait and landscape branches duplicate ~400 lines of controls, progress, bottom bar and effects  
  `app/ui/screens/NowPlayingScreen.kt` — Landscape L303-695 vs Portrait L696-1129. Duplicates: jukebox ticker L462-468 vs L888-894; smooth/seek/slider L469-503 vs L895-929; time Row L504-507 vs L930-933; transport Row L512-591 vs L938-1025; AudioDeviceCallback DisposableEffect L602-611 vs L1035-1044; audioIcon/remoteDevice/outIcon L612-622 vs L1045-1056; bottom bar L623-691 vs L1057-1126. Share intent triplicated L659-666, L1095-1101, L1324-1330.

- **[low/therm:none]** updateNotification rebuilds metadata+bitmap and 5 PendingIntents every call; several callers double-build metadata  
  `app/playback/MusicPlaybackService.kt` — updateNotification() lines 978-983; buildNotification() 985-1056; paired double-calls at 481-482, 491-492, 336-337, 687-688, 712-713, 719-720, 881-882, 890-891, 903-904

- **[low/therm:none]** Pointless derivedStateOf wrapper on a trivial field read  
  `app/ui/screens/DetailScreen.kt` — L196

- **[low/therm:none]** Unused account collectAsState subscriptions (dead code)  
  `app/ui/screens/HomeScreen.kt` — HomeScreen.kt line 52; identical dead subscription in LibraryScreen.kt line 86

- **[low/therm:none]** ReleaseNotesScreen full-screen composable is dead code — only ReleaseNotesDialog is wired up  
  `app/ui/screens/ReleaseNotesScreen.kt` — ReleaseNotesScreen() lines 41-115 (plus its local load())

- **[low/therm:none]** MainActivity uses fixed delay(500)/delay(600) sleeps to race service readiness  
  `app/MainActivity.kt` — LaunchedEffect(initialized) L114-119 (delay 500 before wireServiceControls); autoplay LaunchedEffect(initialized) L125-133 (delay 600)

- **[low/therm:none]** 15 hand-rolled IO launches duplicate the boilerplate launchWithSession exists to replace  
  `src/app/src/main/java/ch/snepilatch/app/viewmodel/SpotifyViewModel.kt` — fetchLyrics L1784, refreshQueue L1801, openAlbumFromCurrentTrack L1862, openArtistFromCurrentTrack L1908, fetchCanvasForTrack L2106, loadMoreLibrary L2873, openLikedSongs L2893, loadMoreDetail L2910, openPlaylist L2971, openAlbum L2984, openArtist L2997, openShow L3014, checkDetailSaved L3035, toggleDetailSaved L3048, removeFromLibrary L3062, loadDevices L3079

- **[low/therm:none]** 5 near-identical open* detail loaders and 7 identical SharedPreferences setters ("kotify_prefs" ×10)  
  `src/app/src/main/java/ch/snepilatch/app/viewmodel/SpotifyViewModel.kt` — openLikedSongs/openPlaylist/openAlbum/openArtist/openShow L2891-3027; setPreferredAudioSource/setContentRegion/setLyricsAnimDirection/setNotificationLeftButton/setNotificationRightButton/setCanvasEnabled/setPlayerGradientBg L1973-2093

- **[low/therm:none]** `art != lastPaletteUrl` palette-update guard duplicated 3×  
  `src/app/src/main/java/ch/snepilatch/app/viewmodel/SpotifyViewModel.kt` — updatePlaybackFromState L998-1001, optimisticTapPlay L1669, resolveAndPlay L2425-2428

- **[low/therm:none]** resolve paths poll latestFileId with delay(100) loops instead of awaiting the existing deferred  
  `src/app/src/main/java/ch/snepilatch/app/viewmodel/SpotifyViewModel.kt` — resolveAndPlay L2487-2491 (1..15 ×100ms), resolveAndPlayEpisode L2661-2666 (1..8 ×100ms); cold-start deferred L1231/L1251, completed L510-513

- **[low/therm:none]** Cold-start / ad / recovery state machines coordinated by ~12 loose mutable flags  
  `src/app/src/main/java/ch/snepilatch/app/viewmodel/SpotifyViewModel.kt` — coldStartPending/coldStartFileId L142-143, suppressRemotePause L161, pendingUserPlay L123, advancePendingReconnect L167, isStreamLoading L245, adSkipStartTs L132, playbackErrorRetries L149, recoveringUri L156, savedRepeatForJukebox/jukeboxRepeatObserverStarted L108-109, authRecovering L294; resetColdStart L1349

- **[low/therm:none]** delay(300)→sync-notification block copy-pasted across three notification callbacks  
  `src/app/src/main/java/ch/snepilatch/app/viewmodel/SpotifyViewModel.kt` — wireNotificationButtonCallbacks onLikeToggle L2177-2181, onShuffleToggle L2185-2189, onRepeatToggle L2193-2197 (cf pushLikeToNotification L1941, pushTransportButtonsToNotification L1565)

- **[low/therm:none]** formatReleaseDate uses redundant non-null assertions after an in-range check  
  `app/data/KotifyMappers.kt` — formatReleaseDate L34-63 (`MONTHS[month!! - 1]` at L44 and L54).

- **[low/therm:none]** NowPlayingScreen duplicates the audio-device callback, jukebox ticker, and slider/timeline block across portrait and landscape  
  `app/ui/screens/NowPlayingScreen.kt` — landscape L459-507 & L602-611 vs portrait L885-933 & L1035-1044


### Comments (8)

- **[low/therm:none]** Four orphaned/misplaced KDoc blocks document the wrong function; ~627 comment lines  
  `app/viewmodel/SpotifyViewModel.kt` — L1098-1106 (toggleJukebox doc sits above startJukeboxRepeatGuard; real toggleJukebox at L1127 has no doc), L1361-1377 (recoverFromPlaybackError doc sits above refillRetryBudgetOnReady), L2201-2212 (lifecycle-callbacks doc sits above armAdAdvanceWatchdog), L2571-2588 (episode-path doc sits above resolveEpisodeViaSoundfinder)

- **[low/therm:none]** Screen ON/OFF BroadcastReceiver exists only to emit a diagnostic Loki log on every screen toggle  
  `app/playback/MusicPlaybackService.kt` — screenReceiver lines 140-150; register L206-210; unregister in onDestroy L1205

- **[low/therm:none]** Orphaned/duplicate KDoc block above refreshStreamingMetadata  
  `app/playback/MusicPlaybackService.kt` — lines 663-679 (two consecutive KDoc blocks before fun refreshStreamingMetadata at 680); setIdleMetadata at 692

- **[low/therm:none]** Misattached KDoc blocks (×4) + three cargo-cult @Suppress("UNCHECKED_CAST") with no cast  
  `src/app/src/main/java/ch/snepilatch/app/viewmodel/SpotifyViewModel.kt` — KDoc L1098-1101, L1361-1369, L2201-2204, L2572-2581; @Suppress("UNCHECKED_CAST") L1768, L1860, L2786

- **[low/therm:none]** MusicPlaybackService: orphaned KDoc leaves setIdleMetadata undocumented  
  `app/playback/MusicPlaybackService.kt` — L663-692

- **[low/therm:none]** NowPlayingScreen: ~12 UI-label comments that restate the code  
  `app/ui/screens/NowPlayingScreen.kt` — portrait/landscape control rows

- **[low/therm:none]** LyricsScreen: ~7 UI-label / banner comments that restate the code  
  `app/ui/screens/LyricsScreen.kt` — portrait/landscape layout

- **[low/therm:none]** DetailScreen: a handful of restatement labels (section markers otherwise fine)  
  `app/ui/screens/DetailScreen.kt` — hero + track rows


---

## Method & caveats

- Built by a 14-lens multi-agent audit (thermal/CPU, Compose recomposition across 6 screen groups, audio DSP, IO/lifecycle, KISS on the ViewModel + playback + UI, KotifyClient loops, and comment hygiene), each finding adversarially re-verified against source, then synthesized and critiqued for completeness.

- 1 finding was rejected in verification (overstated). Severities/thermal ratings were corrected downward by verifiers where auditors inflated them — the ratings here are the corrected ones.

- Thermal ratings marked `high` are **continuous** loads in normal use; most KISS/comment items are `none` (structural only).

- No fix was applied — this is a plan. Land P0 and **profile paused vs. playing** before proceeding.

# InfiniPlay

Endless, ever-varying playback of a single track: the app captures the decoded PCM while the song
plays, finds beats that sound alike, and then plays the captured audio out of order, splicing between
matched beats with short crossfades. In the tradition of Paul Lamere's Infinite Jukebox and Pithaya's
Spicetify Eternal Jukebox — but computed entirely from the waveform, since no per-track analysis API
is available to us.

Code lives in `app/src/main/java/ch/snepilatch/app/playback/`:

| File | Role |
|---|---|
| `InfiniPlayAudioTap.kt` | Captures decoded PCM from the audio pipeline (pass-through) |
| `BeatGrid.kt` | Beat detection from PCM |
| `WaveformAnalyzer.kt` | Per-frame chroma/timbre features, fine-resolution join spectra |
| `BeatGraph.kt` | The jump graph: which beat may splice to which |
| `InfiniPlayRemixProcessor.kt` | Playback: an `AudioProcessor` in ExoPlayer's sink that performs the remix |
| `InfiniPlayController.kt` | Session orchestration: capture → analyse → hand off → grow |

## Why the remix is an AudioProcessor, not a second player

The first implementation ran a separate `AudioTrack` fed by its own thread while ExoPlayer kept
playing muted. Every production incident traced back to that split: the two streams could overlap at
handoff (doubled audio), the engine replayed pre-headroom samples (sudden loudness), the mute could
be undone by unrelated code paths, and the engine's stream carried no audio-session effects (the
in-app EQ silently stopped applying).

`InfiniPlayRemixProcessor` sits inside ExoPlayer's own audio chain instead:

```
decoder → InfiniPlayAudioTap → InfiniPlayRemixProcessor → GainAudioProcessor → AudioTrack
```

One stream, one volume, one audio session. Doubling, level drift, and effect loss are structurally
impossible; pause/seek/teardown need no handling because the remix stops when the sink stops pulling.

Ordering matters: the tap must see the unmodified stream (its capture feeds the analysis), and the
gain processor must come after the remix so EQ headroom applies to remixed audio exactly as to normal
playback.

media3 detail: the sink re-reads a processor's `isActive()` only on a pipeline flush. The processor
therefore joins the chain when the infiniplay session starts (`engaged` — the capture pass's seek
provides the flush) and passes audio through until a snapshot arrives. Activating it later would
require a flush at takeover, and a seek to the current position is a no-op.

## Beat grid (`BeatGrid`)

Spectral-flux onset envelope → autocorrelation for the tempo → phase search for the grid offset.
Constant tempo is assumed, which holds for the produced music this feature targets.

Two precision details, both introduced after audible failures:

- **Fractional period.** The autocorrelation lag grid is one hop (~11.6 ms) coarse. Rounding the
  period to a whole lag accumulates error beat by beat — by mid-track the grid sat hundreds of
  milliseconds beside the real beats and splices landed audibly "rushed". Parabolic interpolation of
  the autocorrelation peak gives a sub-sample period; beats are laid with per-beat rounding.
- **Onset snapping.** Each nominal beat then snaps to the strongest onset within ±3 hops (~35 ms),
  absorbing residual drift and the track's own micro-timing individually.

## Jump graph (`BeatGraph`)

Candidates are beat pairs; each surviving pair becomes a branch the player may take. A pair must pass
**all** of the following. Each rule exists because its absence was audible:

| Rule | Why |
|---|---|
| Same bar position (`(i−j) mod 4 == 0`) | Cutting beat 3 → beat 1 drops/inserts beats mid-bar; heard as the band stumbling |
| Minimum jump of 4 bars | Short hops land inside the phrase that just played; heard as immediate repetition |
| Intro excluded, both ends | Landing in the intro sounds like a restart; jumping *out* of its sparse texture into the full arrangement never blends |
| Beat level within 4 dB (beat + 2 followers) | Shape features are level-blind; without this a quiet breakdown branched into a full-scale chorus (+10 dB seams) |
| Kick-band level within 6 dB | Broadband level can match across a drop boundary while the low end steps 10 dB |
| Onset level within 4 dB | The join replaces the source beat's opening with the destination's; beat averages can agree while the first 150 ms differ by 8 dB |
| Spectral join continuity | Fine-resolution (8192-pt) spectra of the audio leading into the cut vs. the audio landing after it; the join must be nearly as continuous as the original transition at that boundary (absolute floor + relative slack). Coarse spectra blur adjacent harmonics and cannot see a wrong-chord landing |

Scoring: `contextDistance` — the join judged across a window around the cut (offsets −1, 0, +1, +2
with weights 0.5, 1.0, 1.0, 0.75), because what the listener evaluates is whether the beats *after*
the landing point continue what was playing. Features per beat: frame chroma/timbre averaged across
the beat, plus z-scored loudness and a 4-slot kick pattern (the shape features are deliberately
level-blind, so dynamics must be added explicitly).

Selection: **no branch-count target.** The vetoes gate quality; selection keeps the globally best
edges up to n/4 by score. An earlier design loosened a similarity threshold until a target count of
beats could branch — which meant every candidate a veto removed was replaced by a worse one that had
been over the line. Quality rules must reduce the graph, never degrade it.

Post-processing, following the reference implementation:

- **De-sequencing** — consecutive beats may not share a jump distance (the stutter-in-place artefact).
- **Reachability → last branch point** — the last beat from which the song can still branch onward.
  Branches past it are dropped; the player must never run into the outro, finish, and restart.
- **Landing runway** — destinations within 2 bars of the last branch point are pruned (and the player
  enforces a 4 s guard). Landing just under the forced-branch zone chains two jumps back to back.
- **End jump** — the best backward branch near the last branch point, pre-selected with the same
  scoring as every other edge, so the forced end-of-song jump is never a blind cut.

## Playback (`InfiniPlayRemixProcessor`)

- A splice may only start when the playhead stands exactly on a matched source — the crossfade's
  outgoing side has to *be* the matched audio. Linear runs stop precisely on the next source or the
  last branch point, whichever comes first.
- Most sources are played through: 35 % take probability, 2.5 s cooldown.
- Past the last branch point the coin flip no longer applies — a branch is forced. Forced jumps relax
  the freshness rule (a quality-checked edge that repeats beats the blind fallback) and fall back to
  the graph's end jump only when no legal edge exists.
- **Anti-boredom:** destinations recently landed in are skipped; if every legal destination is recent,
  the splice is declined and playback progresses linearly. Among fresh candidates the least-replayed
  region wins (the same per-second counters that drive the remix map's heat display).

The splice itself:

1. **Waveform alignment** — the destination slides within ±12 ms to where its waveform correlates
   best with the outgoing audio; beat detection is only accurate to a few ms and joining out of phase
   cancels bass and smears the attack.
2. **Equal-power crossfade** (40 ms, √-weighted) — linear fades dip ~3 dB mid-fade on uncorrelated
   material.
3. **Bounded level match** — the destination's gain is nudged up to ±3 dB toward the outgoing level.

The fade is longer than one sink buffer, so it runs as a small state machine across `queueInput`
calls rather than assuming it fits in whatever the sink happens to pull.

## Session flow (`InfiniPlayController`)

Capture starts from the top of the track (the seek also flushes the remix processor into the chain).
The first analysis preview runs at 10 s and refreshes every 5 s. Handoff to the remix happens as
early as possible: once ~20 s are captured **and** the graph has at least 4 branch points. (A usable
graph cannot exist much earlier: the 10 s intro guard plus the 16-beat minimum jump distance eat
~18 s.) A track whose opening yields fewer branch points hands off at 45 s with whatever exists; a
track with zero jumps keeps capturing and tries once more when capture stalls at the full track.
There is deliberately no "play the first half normally" wait. After handoff, capture continues in
the background and richer snapshots are swapped in every 15 s of new audio until the full track is
held — the short cadence matters now that the first snapshot only spans ~20 s.

Growth simulation in the offline lab (rendering while the capture "grows" at 1x, swapping snapshots
on the controller's exact schedule) showed two properties of incremental analysis worth knowing:

- The beat grid's phase can shift between growth passes (tempo stays put), so the jump table can
  churn wholesale from one snapshot to the next. This is harmless: each snapshot is internally
  consistent, the playhead position is plain PCM frames, an in-flight crossfade stores raw frames
  rather than jump references, and the ±12 ms splice alignment absorbs the residual offset. Seam
  jolts stayed below the track's own 99th percentile throughout.
- At handoff the playhead sits at the capture edge, which is past the young snapshot's last branch
  point — so the very first splice is a forced jump back. Expected, not a bug. Until the first
  growth pass lands, the remix lives in a small window and may revisit sections; the 15 s growth
  cadence bounds how long that lasts.

## Tuning constants

The authoritative values live in the code (`BeatGraph`, `InfiniPlayRemixProcessor`, `BeatGrid`,
`InfiniPlayController`); the intent behind the non-obvious ones:

| Constant | Intent |
|---|---|
| `JUMP_PROB = 0.35`, `COOLDOWN_MS = 2500` | Remix character: mostly linear, occasional branches |
| `MIN_JUMP_BEATS = 16` | Nothing shorter than 4 bars reads as a real jump |
| `LEVEL/KICK/ONSET vetoes (4/6/4 dB)` | Calibrated against seams the listening tests rejected |
| `JOIN_COS_MIN = 0.55`, slack `0.10` | Same — the rejected seam measured 0.61, accepted ones ≥ 0.75 |
| `INTRO_GUARD_S = 10` | The song must establish itself; intro texture doesn't blend |
| `XFADE_MS = 40` | Long enough to blend, short enough not to smear the attack |

## The offline lab

All of the above was developed and validated offline, without a phone in the loop:

1. `yt-dlp` + `ffmpeg` fetch a track as 44.1 kHz/16-bit WAV.
2. A JVM harness compiles the **actual app sources** (analyzer, graph, processor) and drives the
   processor exactly as ExoPlayer's sink does — fixed-size buffers in a loop — rendering minutes of
   remix to WAV in seconds, plus a `-seams.csv` log of every splice (output position, from, to).
3. A Python suite (`numpy`/`matplotlib`) computes per-seam health (RMS step, kick-band step, spectral
   cosine, a jolt measure compared against the track's own 99th percentile) and renders per-seam
   panels (waveform zoom, spectrogram, band energy) plus a full-track overview.

Invariants the harness asserts on every run: all splices land on grid beats, no consecutive beats
share a jump distance, and the playhead never passes the last branch point ("never ran into the
outro"). When a listener reports a bad moment at a timestamp, the seam log identifies the exact
branch, and the analysis panel usually identifies the failure class — that loop drove every rule in
the table above.

The lab currently lives outside the repo (session scratchpad). If it moves in, `scripts/infiniplay-lab/`
is the intended home.

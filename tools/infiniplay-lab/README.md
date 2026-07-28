# InfiniPlay offline lab

Renders an InfiniPlay remix from a local audio file on the JVM (no phone in the loop) using the
**real** app classes (`BeatGrid`, `BeatGraph`, `InfiniPlayRemixProcessor`), then checks the result
numerically and visually. Background and methodology: [docs/infiniplay.md](../../docs/infiniplay.md).

The harness lives as an env-gated unit test, `app/src/test/.../InfiniPlayLab.kt`, so it always
compiles against the current app sources and Gradle resolves every dependency; without `LAB_IN` set
it is skipped, so normal test runs and CI are unaffected. Only `analyze.py` lives here, because the
Python side has no Gradle to lean on.

## 1. Get a test track

```sh
yt-dlp -x --audio-format wav -o track "https://..."
ffmpeg -i track.wav -ar 44100 -ac 2 -sample_fmt s16 in.wav
```

## 2. Render a remix

From `src/`:

```sh
LAB_IN=/path/to/in.wav LAB_OUT=/path/to/remix.wav LAB_SECS=180 \
  ./gradlew :app:testProdDebugUnitTest --tests "ch.snepilatch.app.playback.InfiniPlayLab"
```

- `LAB_MODE=growth` replays the device timeline instead of analysing the full file once: capture
  grows at 1x while the remix renders, handoff happens at the first usable graph, and richer
  snapshots swap in on `InfiniPlayController`'s exact schedule (the constants are read from the
  controller, so the simulation cannot drift from the device).
- The run asserts the graph invariants (all jumps on the beat grid, no consecutive same-distance
  branches, never runs into the outro of any live snapshot) and prints seam/jolt statistics.
- Next to the WAV it writes `<name>-seams.csv`: output position plus source from/to frame of every
  splice.

Run `--tests` with `-i` if you want the printed analysis in the console; Gradle hides test stdout
by default in some configurations (it shows up in the HTML test report either way).

## 3. Inspect the seams

Needs Python with numpy + matplotlib:

```sh
python tools/infiniplay-lab/analyze.py /path/to/remix.wav
```

Writes `<name>-report.txt` (per-seam RMS/kick/spectral-cosine health, ranked worst first),
`<name>-overview.png` (full-render spectrogram with seam markers) and `<name>-seam-<k>.png`
(waveform zoom + band-energy panels per seam). Note: the pre-window of a seam includes the
crossfade blend, so the reported cosine is systematically pessimistic; calibrate against the
graph-side numbers and your ears, not this alone.

## 4. Listen

```sh
ffmpeg -i remix.wav -b:a 192k remix.mp3
```

The numbers catch lurches and level jumps; whether a join makes *musical* sense is still decided
by ear. Every veto in `BeatGraph` came from a seam that measured fine and sounded wrong; see the
tuning table in the docs.

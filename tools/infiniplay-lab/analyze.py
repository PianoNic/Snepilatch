# -*- coding: utf-8 -*-
"""Seam analysis suite for InfiniPlay lab renders.

Reads a rendered WAV + its -seams.csv, computes per-seam numeric health metrics,
ranks the worst seams, and draws panels (waveform zoom, spectrogram, band energy)
so splice problems are visible, not just audible.

Usage: python analyze.py <rendered.wav> [top_n]
Outputs: <name>-report.txt, <name>-overview.png, <name>-seam-<k>.png
"""
import sys
import wave
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def read_wav(path):
    with wave.open(path, "rb") as f:
        ch = f.getnchannels()
        rate = f.getframerate()
        data = np.frombuffer(f.readframes(f.getnframes()), dtype=np.int16)
    return data.reshape(-1, ch).mean(axis=1).astype(np.float64) / 32768.0, rate


def stft_mag(x, n=1024, hop=256):
    win = np.hanning(n)
    frames = 1 + (len(x) - n) // hop
    out = np.empty((n // 2, frames))
    for i in range(frames):
        out[:, i] = np.abs(np.fft.rfft(x[i * hop:i * hop + n] * win))[: n // 2]
    return out, hop


def band_energy(x, rate, lo, hi, win_ms=20):
    """Short-window RMS of a bandpassed signal (FFT brickwall, fine for analysis)."""
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1 / rate)
    spec[(freqs < lo) | (freqs > hi)] = 0
    y = np.fft.irfft(spec, len(x))
    w = int(rate * win_ms / 1000)
    n = len(y) // w
    return np.sqrt(np.mean(y[: n * w].reshape(n, w) ** 2, axis=1) + 1e-12), w


def db(x):
    return 20 * np.log10(np.maximum(x, 1e-9))


def seam_metrics(mono, rate, pos):
    """Numeric health of the join at sample `pos` (fade midpoint approximated)."""
    w = int(rate * 0.15)
    if pos - 2 * w < 0 or pos + 2 * w >= len(mono):
        return None
    pre = mono[pos - w:pos]
    post = mono[pos:pos + w]
    rms_step_db = db(np.sqrt(np.mean(post ** 2) + 1e-12)) - db(np.sqrt(np.mean(pre ** 2) + 1e-12))
    # Spectral continuity: cosine distance between average spectra either side.
    sp_pre = np.abs(np.fft.rfft(pre * np.hanning(len(pre))))
    sp_post = np.abs(np.fft.rfft(post * np.hanning(len(post))))
    cos = float(np.dot(sp_pre, sp_post) / (np.linalg.norm(sp_pre) * np.linalg.norm(sp_post) + 1e-12))
    # Kick-band (40-120 Hz) energy step: rhythm section continuity across the join.
    kb_pre = np.sqrt(np.mean(bandpass(pre, rate, 40, 120) ** 2) + 1e-12)
    kb_post = np.sqrt(np.mean(bandpass(post, rate, 40, 120) ** 2) + 1e-12)
    kick_step_db = db(kb_post) - db(kb_pre)
    return dict(rms_step_db=rms_step_db, spectral_cos=cos, kick_step_db=kick_step_db)


def bandpass(x, rate, lo, hi):
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1 / rate)
    spec[(freqs < lo) | (freqs > hi)] = 0
    return np.fft.irfft(spec, len(x))


def badness(m):
    """Single score to rank seams: level step + kick step + spectral mismatch."""
    return abs(m["rms_step_db"]) + 0.7 * abs(m["kick_step_db"]) + 8 * (1 - m["spectral_cos"])


def seam_panel(mono, rate, pos, k, meta, name):
    fig, ax = plt.subplots(3, 1, figsize=(11, 8))
    fig.suptitle(
        f"{name} seam #{k} @ {pos / rate:.1f}s  "
        f"rms {meta['rms_step_db']:+.1f} dB  kick {meta['kick_step_db']:+.1f} dB  "
        f"spec-cos {meta['spectral_cos']:.3f}  badness {badness(meta):.2f}")
    # Waveform zoom ±120 ms around the join.
    zw = int(rate * 0.12)
    t = (np.arange(-zw, zw) / rate) * 1000
    ax[0].plot(t, mono[pos - zw:pos + zw], lw=0.5)
    ax[0].axvline(0, color="r", lw=1)
    ax[0].set_ylabel("waveform")
    ax[0].set_xlabel("ms around join")
    # Spectrogram ±1.5 s.
    sw = int(rate * 1.5)
    seg = mono[max(0, pos - sw):pos + sw]
    mag, hop = stft_mag(seg)
    ax[1].imshow(db(mag + 1e-9), origin="lower", aspect="auto",
                 extent=[-1.5, 1.5, 0, rate / 2 / 1000], cmap="magma", vmin=-70, vmax=10)
    ax[1].axvline(0, color="w", lw=0.8, ls="--")
    ax[1].set_ylim(0, 8)
    ax[1].set_ylabel("kHz")
    ax[1].set_xlabel("s around join")
    # Kick-band + full-band energy ±1.5 s.
    kb, w = band_energy(seg, rate, 40, 120)
    fb, _ = band_energy(seg, rate, 20, 16000)
    tt = (np.arange(len(kb)) * w - sw) / rate
    ax[2].plot(tt[: len(kb)], db(kb), label="kick 40-120 Hz")
    ax[2].plot(tt[: len(fb)], db(fb), label="full band", alpha=0.6)
    ax[2].axvline(0, color="r", lw=1)
    ax[2].legend(loc="lower left")
    ax[2].set_ylabel("dB")
    ax[2].set_xlabel("s around join")
    fig.tight_layout()
    fig.savefig(f"{name}-seam-{k}.png", dpi=90)
    plt.close(fig)


def main():
    wav = sys.argv[1]
    top_n = int(sys.argv[2]) if len(sys.argv) > 2 else 6
    name = wav.rsplit(".", 1)[0]
    mono, rate = read_wav(wav)
    seams = np.loadtxt(f"{name}-seams.csv", delimiter=",", skiprows=1, dtype=np.int64)
    seams = np.atleast_2d(seams)

    rows = []
    for out_frame, from_f, to_f in seams:
        m = seam_metrics(mono, rate, int(out_frame))
        if m:
            rows.append((int(out_frame), int(from_f), int(to_f), m))

    rows.sort(key=lambda r: -badness(r[3]))
    lines = [f"{len(rows)} seams analysed  ({wav})",
             f"{'out_s':>7} {'from_s':>7} {'to_s':>6} {'rms_dB':>7} {'kick_dB':>8} {'spec_cos':>9} {'badness':>8}"]
    for out_frame, from_f, to_f, m in rows:
        lines.append(f"{out_frame / rate:7.1f} {from_f / rate:7.1f} {to_f / rate:6.1f} "
                     f"{m['rms_step_db']:+7.1f} {m['kick_step_db']:+8.1f} "
                     f"{m['spectral_cos']:9.3f} {badness(m):8.2f}")
    report = "\n".join(lines)
    open(f"{name}-report.txt", "w").write(report)
    print(report)

    # Overview: full spectrogram with seam markers.
    step = max(1, len(mono) // (3000 * 256))
    mag, hop = stft_mag(mono[:: 1], n=2048, hop=2048)
    fig, ax = plt.subplots(figsize=(16, 5))
    ax.imshow(db(mag + 1e-9), origin="lower", aspect="auto",
              extent=[0, len(mono) / rate, 0, rate / 2 / 1000], cmap="magma", vmin=-70, vmax=10)
    for out_frame, _, _, m in rows:
        ax.axvline(out_frame / rate, color="cyan" if badness(m) < 4 else "red", lw=0.8, alpha=0.8)
    ax.set_ylim(0, 10)
    ax.set_xlabel("s")
    ax.set_ylabel("kHz")
    ax.set_title(f"{name}: red = worst seams, cyan = clean")
    fig.tight_layout()
    fig.savefig(f"{name}-overview.png", dpi=90)
    plt.close(fig)

    for k, (out_frame, _, _, m) in enumerate(rows[:top_n]):
        seam_panel(mono, rate, out_frame, k, m, name)
    print(f"wrote {name}-overview.png and {min(top_n, len(rows))} seam panels")


if __name__ == "__main__":
    main()

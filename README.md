<p align="center">
  <img src="./assets/snepilatch_Logo.png" width="120" alt="Snepilatch Logo">
</p>

<h1 align="center">Snepilatch</h1>

<p align="center">
  <strong>A simplicity-focused, open-source music streaming app for Android.</strong>
</p>

<p align="center">
  <a href="https://github.com/PianoNic/Snepilatch/stargazers"><img src="https://img.shields.io/github/stars/PianoNic/Snepilatch?style=flat&color=1DB954" alt="Stars"/></a>
  <a href="https://github.com/PianoNic/Snepilatch/releases"><img src="https://img.shields.io/github/v/release/PianoNic/Snepilatch?include_prereleases&color=1DB954&label=Latest" alt="Release"/></a>
  <a href="https://discord.gg/NJxKMSNYRG"><img src="https://img.shields.io/discord/1288927764787752990?color=5865F2&label=Discord&logo=discord&logoColor=white" alt="Discord"/></a>
</p>

## Features

**Playback**

- Plays locally on your phone as its own Connect device, so transport actions are never skip-capped
- Ads are consumed silently and skipped, without spending a skip
- Lossless audio option (FLAC via Qobuz or Deezer) alongside the standard stream
- Gapless-style prepared boundaries, with the next track resolved before the current one ends
- Full Connect control: transfer to and from other devices

**Sound**

- Ten-band in-app equalizer with a drag-and-drop curve editor
- Automatic gain staging so boosted bands cannot clip
- EQ headroom for anyone running an external equalizer such as Wavelet

**InfiniPlay**

- Turns any track into a never-ending remix, beat-matched and crossfaded in the audio chain
- Builds a beat graph from the waveform itself, with no per-track analysis API

**Library and browsing**

- Liked songs, playlists, albums, artists and podcasts
- Search, home feed, and queue management in a bottom drawer
- Word-level synced lyrics

**Interface**

- Fully native UI built with Jetpack Compose and Material 3
- Dynamic color theming from album art
- Gesture-based player with swipe navigation
- Canvas background animations
- English, German, Russian and Swiss German
- Automatic in-app updates

## Screenshots

![Screenshots](assets/product_mockup.png)

## Installation

Download the latest APK from the [Releases](https://github.com/PianoNic/Snepilatch/releases) page.

## Building from Source

> This project depends on a private library not included in the repository. It will not compile without it.

## Community

[Discord](https://discord.gg/NJxKMSNYRG)

## License

[MIT](LICENSE)

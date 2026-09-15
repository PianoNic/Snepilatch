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
  <a href="https://discord.gg/NJxKMSNYRG"><img src="https://img.shields.io/discord/1421178590027841618?color=1DB954&label=Discord&logo=discord&logoColor=white" alt="Discord"/></a>
</p>

<p align="center">
  <img src="./assets/product_mockup.png" alt="">
</p>

## Features

| Playback | Sound |
| --- | --- |
| <ul><li>Plays locally on your phone as its own playback device, so transport actions are never skip-capped</li><li>Ad-free listening</li><li>Choose your audio source: the standard stream, optional lossless, or YouTube Music</li><li>Transfer playback to and from your other devices</li><li>Smart shuffle</li><li>Notification and lock screen controls that follow what is actually playing</li></ul> | <ul><li>Ten-band in-app equalizer with a drag-and-drop curve editor</li><li>Automatic gain staging so boosted bands cannot clip</li><li>EQ headroom for anyone running an external equalizer such as Wavelet</li></ul> |
| **InfiniPlay** | **Offline and downloads** |
| <ul><li>Turns any track into a never-ending remix, beat-matched and crossfaded in the audio chain</li><li>Builds a beat graph from the waveform itself, with no per-track analysis API</li></ul> | <ul><li>Download tracks, albums and playlists into a folder you choose, through a queue you can pause and cancel</li><li>Keeps playing when the signal drops and hands playback back when it returns</li><li>An offline queue of its own that says so when it runs out</li></ul> |
| **Lyrics** | **Social** |
| <ul><li>Word-level synced lyrics, animated line by line</li><li>Duet lines, background vocals and songwriter credits</li><li>Tap any line to seek there</li><li>Flip the cover on the player for a mini lyrics view, or open it fullscreen</li></ul> | <ul><li>See what your friends are listening to, and play the track one of them is on in the same context</li><li>Join a jam from a link or a QR code, with its members, invites and guest controls in the queue</li><li>Sign several accounts in and switch between them</li><li>Change your display picture from the account tab</li></ul> |
| **Library and browsing** | **Interface** |
| <ul><li>Liked songs, playlists, albums, artists and podcasts</li><li>Search, home feed, and queue management in a bottom drawer</li><li>Scannable codes for the playing track</li><li>Opens shared links straight in the app</li></ul> | <ul><li>Fully native UI built with Jetpack Compose and Material 3</li><li>Dynamic color theming from album art</li><li>Gesture-based player with swipe navigation</li><li>Canvas background animations</li></ul> |
| **Yours to arrange** | **Languages and updates** |
| <ul><li>Give each swipe direction on a track row the action you want</li><li>Pick what the extra button on the player does</li><li>Pick the two buttons on the media notification</li><li>Appearance and behaviour on a settings page of their own</li></ul> | <ul><li>English, German, Russian and Swiss German</li><li>Automatic in-app updates, on the stable or the nightly channel</li></ul> |

> [!CAUTION]
> This is an unofficial client, use at your own risk or with an alternative account, as account safety cannot be guaranteed.

## Installation

Download the latest APK from the [Releases](https://github.com/PianoNic/Snepilatch/releases) page.

## Building from Source

> This project depends on a private library not included in the repository. It will not compile without it. Contact me for permission to access the source code via Discord

## Community

[Discord](https://discord.gg/NJxKMSNYRG)

## Credits

The YouTube Music audio source follows the approach shown by [Meld](https://github.com/FrancescoGrazioso/Meld) and its [Metrolist](https://github.com/MetrolistGroup/Metrolist) / [OuterTune](https://github.com/DD3Boh/OuterTune) / [InnerTune](https://github.com/z-huang/InnerTune) lineage.

No code of theirs is used; this is an independent implementation.

## License

[MIT](LICENSE)

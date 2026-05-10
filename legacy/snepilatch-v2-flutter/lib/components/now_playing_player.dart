import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:snepilatch_v2/components/full_player_modal.dart';
import 'package:snepilatch_v2/services/spotify_client.dart';

class NowPlayingPlayer extends StatefulWidget {
  const NowPlayingPlayer({super.key});

  @override
  State<NowPlayingPlayer> createState() => _NowPlayingPlayerState();
}

class _NowPlayingPlayerState extends State<NowPlayingPlayer> {
  void _onPlayerTapped() {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.only(
          topLeft: Radius.circular(12),
          topRight: Radius.circular(12),
        ),
      ),
      builder: (BuildContext context) {
        return const FullPlayerModal();
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final spotifyClient = context.watch<SpotifyClient>();
    final currentTrack = spotifyClient.currentTrack;
    final playbackProgress = spotifyClient.playbackProgress;
    final playerControlState = spotifyClient.playerControlState;
    final isPlaying = playerControlState?.playPause == 'playing';

    return Container(
      width: double.infinity,
      height: 70,
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainer,
        borderRadius: BorderRadius.only(
          topLeft: Radius.circular(12),
          topRight: Radius.circular(12),
        ),
      ),
      child: Column(
        children: [
          Expanded(
            child: Material(
              color: Colors.transparent,
              child: InkWell(
                onTap: _onPlayerTapped,
                borderRadius: BorderRadius.only(
                  topLeft: Radius.circular(12),
                  topRight: Radius.circular(12),
                ),
                child: Padding(
                  padding: EdgeInsets.all(8),
                  child: Row(
                    children: [
                      // Album Cover
                      Container(
                        width: 54,
                        height: 54,
                        decoration: BoxDecoration(
                          color: Colors.grey,
                          borderRadius: BorderRadius.circular(6),
                        ),
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(6),
                          child: currentTrack?.imageUrl != null
                              ? Image.network(
                                  currentTrack!.imageUrl!,
                                  fit: BoxFit.cover,
                                  errorBuilder: (context, error, stackTrace) {
                                    return const Center(
                                      child: Icon(Icons.music_note),
                                    );
                                  },
                                )
                              : const Center(
                                  child: Icon(Icons.music_note),
                                ),
                        ),
                      ),
                      SizedBox(width: 12),

                      // Title & Artist
                      Expanded(
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              currentTrack?.title ?? 'No track',
                              style: TextStyle(
                                fontWeight: FontWeight.bold,
                                fontSize: 14,
                              ),
                              overflow: TextOverflow.ellipsis,
                            ),
                            Text(
                              currentTrack?.artist ?? 'Unknown artist',
                              style: TextStyle(fontSize: 12),
                              overflow: TextOverflow.ellipsis,
                            ),
                          ],
                        ),
                      ),

                      // Play/Pause Button
                      IconButton(
                        icon: Icon(isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded),
                        onPressed: () {
                          print('[NowPlayingPlayer] Play/Pause button pressed');
                          spotifyClient.togglePlayPause();
                        },
                      ),

                      // Skip Button
                      IconButton(
                        icon: Icon(Icons.skip_next_rounded),
                        onPressed: () {
                          print('[NowPlayingPlayer] Skip button pressed');
                          spotifyClient.skipNext();
                        },
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          // Progress bar
          LinearProgressIndicator(
            value: playbackProgress?.progressFraction ?? 0,
            minHeight: 2,
          ),
        ],
      ),
    );
  }
}

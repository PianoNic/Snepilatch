import 'package:flutter/material.dart';
import 'dart:async';
import '../services/webview_audio_streamer.dart';
import '../services/audio_playback_service.dart';

class AudioDebugPage extends StatefulWidget {
  const AudioDebugPage({Key? key}) : super(key: key);

  @override
  _AudioDebugPageState createState() => _AudioDebugPageState();
}

class _AudioDebugPageState extends State<AudioDebugPage> {
  Timer? _updateTimer;
  final _audioStreamer = WebViewAudioStreamer.instance;
  final _playbackService = AudioPlaybackService.instance;

  @override
  void initState() {
    super.initState();
    // Update UI every 100ms
    _updateTimer = Timer.periodic(Duration(milliseconds: 100), (timer) {
      setState(() {});
    });
  }

  @override
  void dispose() {
    _updateTimer?.cancel();
    super.dispose();
  }

  String _formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(2)} MB';
  }

  Widget _buildStatusCard(String title, String value, IconData icon, Color color) {
    return Card(
      elevation: 2,
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          children: [
            Icon(icon, size: 32, color: color),
            SizedBox(height: 8),
            Text(
              title,
              style: TextStyle(fontSize: 12, color: Colors.grey[600]),
            ),
            SizedBox(height: 4),
            Text(
              value,
              style: TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.bold,
                color: color,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAudioAnalysis() {
    final server = _audioStreamer.audioServer;
    if (server == null) {
      return Center(child: Text('Audio server not initialized'));
    }

    final isSilence = server.isReceivingSilence;
    final silentPackets = server.silentPackets;
    final nonSilentPackets = server.nonSilentPackets;
    final maxAmplitude = server.maxAmplitude;
    final avgAmplitude = server.avgAmplitude;

    return Column(
      children: [
        // Audio Type Indicator
        Container(
          padding: EdgeInsets.all(20),
          margin: EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: isSilence ? Colors.red.withOpacity(0.1) : Colors.green.withOpacity(0.1),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: isSilence ? Colors.red : Colors.green,
              width: 2,
            ),
          ),
          child: Column(
            children: [
              Icon(
                isSilence ? Icons.volume_off : Icons.volume_up,
                size: 64,
                color: isSilence ? Colors.red : Colors.green,
              ),
              SizedBox(height: 12),
              Text(
                isSilence ? 'RECEIVING SILENCE' : 'RECEIVING AUDIO',
                style: TextStyle(
                  fontSize: 20,
                  fontWeight: FontWeight.bold,
                  color: isSilence ? Colors.red : Colors.green,
                ),
              ),
              if (isSilence) ...[
                SizedBox(height: 8),
                Text(
                  'This likely means DRM protection is blocking audio capture',
                  style: TextStyle(color: Colors.red[700], fontSize: 12),
                  textAlign: TextAlign.center,
                ),
              ],
            ],
          ),
        ),

        // Packet Analysis
        Padding(
          padding: EdgeInsets.symmetric(horizontal: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Packet Analysis', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
              SizedBox(height: 8),
              LinearProgressIndicator(
                value: nonSilentPackets > 0
                    ? nonSilentPackets / (nonSilentPackets + silentPackets)
                    : 0,
                backgroundColor: Colors.red[200],
                valueColor: AlwaysStoppedAnimation<Color>(Colors.green),
                minHeight: 20,
              ),
              SizedBox(height: 8),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text('Silent: $silentPackets', style: TextStyle(color: Colors.red)),
                  Text('Audio: $nonSilentPackets', style: TextStyle(color: Colors.green)),
                ],
              ),
            ],
          ),
        ),

        SizedBox(height: 16),

        // Amplitude Meters
        Padding(
          padding: EdgeInsets.symmetric(horizontal: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Audio Levels', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
              SizedBox(height: 12),
              _buildAmplitudeMeter('Max Amplitude', maxAmplitude),
              SizedBox(height: 8),
              _buildAmplitudeMeter('Avg Amplitude', avgAmplitude),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildAmplitudeMeter(String label, double value) {
    final percentage = (value * 100).clamp(0, 100);
    final color = value > 0.1
        ? Colors.green
        : value > 0.01
            ? Colors.orange
            : Colors.red;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label, style: TextStyle(fontSize: 14)),
            Text('${percentage.toStringAsFixed(2)}%', style: TextStyle(fontSize: 14, color: color)),
          ],
        ),
        SizedBox(height: 4),
        LinearProgressIndicator(
          value: value.clamp(0, 1),
          backgroundColor: Colors.grey[300],
          valueColor: AlwaysStoppedAnimation<Color>(color),
          minHeight: 8,
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final isConnected = _audioStreamer.isConnected;
    final status = _audioStreamer.status;
    final bytesReceived = _audioStreamer.bytesReceived;
    final packetsReceived = _audioStreamer.packetsReceived;

    // Playback service status
    final isPlaying = _playbackService.isPlaying;
    final isBuffering = _playbackService.isBuffering;
    final bufferSize = _playbackService.bufferSize;

    return Scaffold(
      appBar: AppBar(
        title: Text('Audio Stream Debug'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: SingleChildScrollView(
        child: Column(
          children: [
            // Connection Status
            GridView.count(
              shrinkWrap: true,
              physics: NeverScrollableScrollPhysics(),
              crossAxisCount: 2,
              padding: EdgeInsets.all(16),
              mainAxisSpacing: 16,
              crossAxisSpacing: 16,
              childAspectRatio: 1.3,
              children: [
                _buildStatusCard(
                  'Connection',
                  isConnected ? 'Connected' : 'Disconnected',
                  isConnected ? Icons.link : Icons.link_off,
                  isConnected ? Colors.green : Colors.red,
                ),
                _buildStatusCard(
                  'Status',
                  status,
                  Icons.info_outline,
                  Colors.blue,
                ),
                _buildStatusCard(
                  'Packets',
                  packetsReceived.toString(),
                  Icons.inventory_2,
                  Colors.purple,
                ),
                _buildStatusCard(
                  'Data Received',
                  _formatBytes(bytesReceived),
                  Icons.download,
                  Colors.orange,
                ),
              ],
            ),

            Divider(thickness: 1),

            // Audio Analysis Section
            _buildAudioAnalysis(),

            Divider(thickness: 1),

            // Playback Status
            Padding(
              padding: EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Playback Status', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                  SizedBox(height: 12),
                  ListTile(
                    leading: Icon(
                      isPlaying ? Icons.play_circle : Icons.pause_circle,
                      color: isPlaying ? Colors.green : Colors.orange,
                      size: 32,
                    ),
                    title: Text(isPlaying ? 'Playing' : (isBuffering ? 'Buffering...' : 'Stopped')),
                    subtitle: Text('Buffer: ${_formatBytes(bufferSize)}'),
                  ),
                ],
              ),
            ),

            // Instructions
            Container(
              margin: EdgeInsets.all(16),
              padding: EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Colors.blue.withOpacity(0.1),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.blue),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Debug Instructions:',
                    style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                  ),
                  SizedBox(height: 8),
                  Text('1. Play a song in the Spotify WebView'),
                  Text('2. Check if packets are being received'),
                  Text('3. Monitor if audio or silence is detected'),
                  Text('4. If silence is detected, DRM is likely blocking capture'),
                  SizedBox(height: 8),
                  Text(
                    'Note: Spotify uses DRM protection which prevents audio capture.',
                    style: TextStyle(color: Colors.red[700], fontStyle: FontStyle.italic),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
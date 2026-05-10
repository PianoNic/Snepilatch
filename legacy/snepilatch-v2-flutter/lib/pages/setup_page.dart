import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:snepilatch_v2/services/spotify_config.dart';

class SetupPage extends StatefulWidget {
  final VoidCallback onComplete;

  const SetupPage({super.key, required this.onComplete});

  @override
  State<SetupPage> createState() => _SetupPageState();
}

class _SetupPageState extends State<SetupPage> {
  final _clientIdController = TextEditingController();
  final _clientSecretController = TextEditingController();
  bool _saving = false;

  @override
  void dispose() {
    _clientIdController.dispose();
    _clientSecretController.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final clientId = _clientIdController.text.trim();
    final clientSecret = _clientSecretController.text.trim();

    if (clientId.isEmpty || clientSecret.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Please fill in both fields')),
      );
      return;
    }

    setState(() => _saving = true);
    await SpotifyConfig.saveCredentials(clientId, clientSecret);
    widget.onComplete();
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;

    return Scaffold(
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 48),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(Icons.music_note_rounded, size: 48, color: colorScheme.primary),
              const SizedBox(height: 16),
              Text('Welcome to Snepilatch', style: textTheme.headlineMedium),
              const SizedBox(height: 8),
              Text(
                'To get started, you need your own Spotify Developer credentials.',
                style: textTheme.bodyLarge?.copyWith(color: colorScheme.onSurfaceVariant),
              ),
              const SizedBox(height: 32),

              // Instructions
              Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('How to get credentials:', style: textTheme.titleSmall),
                    const SizedBox(height: 8),
                    Text(
                      '1. Go to developer.spotify.com/dashboard\n'
                      '2. Create a new app\n'
                      '3. Set the Redirect URI to:\n'
                      '    ${SpotifyConfig.redirectUri}\n'
                      '4. Copy the Client ID and Client Secret below',
                      style: textTheme.bodyMedium,
                    ),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            SpotifyConfig.redirectUri,
                            style: textTheme.bodySmall?.copyWith(
                              fontFamily: 'monospace',
                              color: colorScheme.primary,
                            ),
                          ),
                        ),
                        IconButton(
                          icon: const Icon(Icons.copy, size: 18),
                          onPressed: () {
                            Clipboard.setData(
                              ClipboardData(text: SpotifyConfig.redirectUri),
                            );
                            ScaffoldMessenger.of(context).showSnackBar(
                              const SnackBar(content: Text('Redirect URI copied')),
                            );
                          },
                          tooltip: 'Copy redirect URI',
                        ),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 32),

              // Client ID field
              TextField(
                controller: _clientIdController,
                decoration: const InputDecoration(
                  labelText: 'Client ID',
                  border: OutlineInputBorder(),
                ),
                autocorrect: false,
              ),
              const SizedBox(height: 16),

              // Client Secret field
              TextField(
                controller: _clientSecretController,
                decoration: const InputDecoration(
                  labelText: 'Client Secret',
                  border: OutlineInputBorder(),
                ),
                obscureText: true,
                autocorrect: false,
              ),
              const SizedBox(height: 32),

              // Save button
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: _saving ? null : _save,
                  child: _saving
                      ? const SizedBox(
                          height: 20,
                          width: 20,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Text('Save & Continue'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

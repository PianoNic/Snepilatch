import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import '../services/update_service.dart';
import '../services/storage_service.dart';

class AppUpdateDialog extends StatefulWidget {
  final UpdateInfo updateInfo;

  const AppUpdateDialog({
    super.key,
    required this.updateInfo,
  });

  static Future<void> showIfAvailable(BuildContext context) async {
    if (!Platform.isAndroid && !Platform.isIOS) return;

    try {
      final updateInfo = await UpdateService.checkForUpdates();
      if (updateInfo != null && context.mounted) {
        final dismissedVersion = await StorageService.getDismissedUpdateVersion();

        if (dismissedVersion != updateInfo.latestVersion && context.mounted) {
          showDialog(
            context: context,
            barrierDismissible: false,
            builder: (context) => AppUpdateDialog(updateInfo: updateInfo),
          );
        }
      }
    } catch (e) {
      debugPrint('Error showing update dialog: $e');
    }
  }

  @override
  State<AppUpdateDialog> createState() => _AppUpdateDialogState();
}

class _AppUpdateDialogState extends State<AppUpdateDialog> {
  bool _isDownloading = false;
  bool _isInstalling = false;
  double _downloadProgress = 0;
  String? _errorMessage;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return AlertDialog(
      title: Row(
        children: [
          Icon(
            Icons.system_update,
            color: theme.colorScheme.primary,
          ),
          const SizedBox(width: 12),
          const Expanded(
            child: Text('Update Available!'),
          ),
        ],
      ),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: theme.colorScheme.primaryContainer,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Current version',
                        style: theme.textTheme.bodySmall,
                      ),
                      Text(
                        widget.updateInfo.currentVersion,
                        style: theme.textTheme.bodyLarge?.copyWith(
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ],
                  ),
                  Icon(
                    Icons.arrow_forward,
                    color: theme.colorScheme.onPrimaryContainer,
                  ),
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Text(
                        'New version',
                        style: theme.textTheme.bodySmall,
                      ),
                      Text(
                        widget.updateInfo.latestVersion,
                        style: theme.textTheme.bodyLarge?.copyWith(
                          fontWeight: FontWeight.bold,
                          color: theme.colorScheme.primary,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            if (_isDownloading) ...[
              Text(
                'Downloading update...',
                style: theme.textTheme.bodyMedium,
              ),
              const SizedBox(height: 8),
              LinearProgressIndicator(
                value: _downloadProgress / 100,
                backgroundColor: theme.colorScheme.surfaceContainerHighest,
              ),
              const SizedBox(height: 4),
              Text(
                '${_downloadProgress.toStringAsFixed(0)}%',
                style: theme.textTheme.bodySmall,
              ),
              const SizedBox(height: 16),
            ] else if (_isInstalling) ...[
              const Row(
                children: [
                  SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                  SizedBox(width: 12),
                  Text('Installing update...'),
                ],
              ),
              const SizedBox(height: 16),
            ],
            if (_errorMessage != null) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: theme.colorScheme.errorContainer,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Row(
                  children: [
                    Icon(
                      Icons.error_outline,
                      color: theme.colorScheme.onErrorContainer,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        _errorMessage!,
                        style: TextStyle(
                          color: theme.colorScheme.onErrorContainer,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
            ],
            if (widget.updateInfo.releaseNotes.isNotEmpty &&
                !_isDownloading &&
                !_isInstalling) ...[
              Text(
                'What\'s new:',
                style: theme.textTheme.titleMedium,
              ),
              const SizedBox(height: 8),
              Container(
                constraints: const BoxConstraints(maxHeight: 200),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: theme.colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Markdown(
                  data: widget.updateInfo.releaseNotes,
                  shrinkWrap: true,
                  physics: const ScrollPhysics(),
                  styleSheet: MarkdownStyleSheet(
                    p: theme.textTheme.bodyMedium,
                    h1: theme.textTheme.titleLarge,
                    h2: theme.textTheme.titleMedium,
                    h3: theme.textTheme.titleSmall,
                    listBullet: theme.textTheme.bodyMedium,
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
      actions: [
        if (!_isDownloading && !_isInstalling) ...[
          TextButton(
            onPressed: () async {
              await StorageService.setDismissedUpdateVersion(
                widget.updateInfo.latestVersion,
              );
              if (context.mounted) {
                Navigator.of(context).pop();
              }
            },
            child: const Text('Later'),
          ),
          FilledButton(
            onPressed: _handleUpdate,
            child: const Text('Update Now'),
          ),
        ] else if (_isDownloading) ...[
          TextButton(
            onPressed: () {
              Navigator.of(context).pop();
            },
            child: const Text('Cancel'),
          ),
        ],
      ],
    );
  }

  Future<void> _handleUpdate() async {
    setState(() {
      _isDownloading = true;
      _downloadProgress = 0;
      _errorMessage = null;
    });

    try {
      final filePath = await UpdateService.downloadApk(
        widget.updateInfo.downloadUrl,
        onProgress: (received, total) {
          if (total != -1) {
            setState(() {
              _downloadProgress = (received / total) * 100;
            });
          }
        },
      );

      if (filePath != null) {
        setState(() {
          _isDownloading = false;
          _isInstalling = true;
        });

        final success = await UpdateService.installApk(filePath);

        if (!success && mounted) {
          setState(() {
            _isInstalling = false;
            _errorMessage = 'Failed to install update. Please try again.';
          });
        }
      } else {
        setState(() {
          _isDownloading = false;
          _errorMessage = 'Failed to download update. Please try again.';
        });
      }
    } catch (e) {
      setState(() {
        _isDownloading = false;
        _isInstalling = false;
        _errorMessage = 'Update failed: ${e.toString()}';
      });
    }
  }
}
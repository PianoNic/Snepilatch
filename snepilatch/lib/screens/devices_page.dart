import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';
import '../models/device.dart';

class DevicesModalContent extends StatefulWidget {
  final SpotifyController spotifyController;

  const DevicesModalContent({
    super.key,
    required this.spotifyController,
  });

  @override
  State<DevicesModalContent> createState() => _DevicesModalContentState();
}

class _DevicesModalContentState extends State<DevicesModalContent> {
  bool _isLoadingDevices = false;
  bool _isSwitching = false;

  @override
  void initState() {
    super.initState();
    _loadDevices();
  }

  Future<void> _loadDevices() async {
    setState(() {
      _isLoadingDevices = true;
    });

    // Click device button to open panel in Spotify
    await widget.spotifyController.openDevicePanel();

    // Then refresh devices
    await widget.spotifyController.refreshDevices();

    if (mounted) {
      setState(() {
        _isLoadingDevices = false;
      });
    }
  }

  Future<void> _switchDevice(Device device) async {
    if (device.isActive || _isSwitching) return;

    setState(() {
      _isSwitching = true;
    });

    final success = await widget.spotifyController.switchDevice(device.id);

    if (mounted) {
      setState(() {
        _isSwitching = false;
      });

      if (success) {
        // Refresh devices to update active status
        await _loadDevices();

        // Close modal after successful switch
        if (mounted) {
          Navigator.pop(context);
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('Switched to ${device.name}'),
              duration: const Duration(seconds: 2),
            ),
          );
        }
      } else {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Failed to switch device'),
              duration: Duration(seconds: 2),
            ),
          );
        }
      }
    }
  }

  IconData _getDeviceIcon(String deviceType) {
    switch (deviceType.toLowerCase()) {
      case 'browser':
        return Icons.language;
      case 'phone':
      case 'mobile':
        return Icons.phone_android;
      case 'speaker':
        return Icons.speaker;
      case 'computer':
      case 'desktop':
      case 'laptop':
        return Icons.computer;
      case 'watch':
        return Icons.watch;
      case 'tablet':
        return Icons.tablet;
      case 'tv':
        return Icons.tv;
      default:
        return Icons.devices;
    }
  }

  String _formatDeviceType(String deviceType) {
    return deviceType[0].toUpperCase() + deviceType.substring(1);
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.spotifyController,
      builder: (context, child) {
        final devices = widget.spotifyController.devices
            .where((d) => d.name != 'Unknown Device')
            .toList();
        final activeDeviceId = widget.spotifyController.activeDeviceId;

        return DraggableScrollableSheet(
          expand: false,
          initialChildSize: 0.6,
          minChildSize: 0.4,
          maxChildSize: 0.95,
          builder: (context, scrollController) {
            return Column(
              children: [
                // Header
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Connect',
                            style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            'Select a device to play music on',
                            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                              color: Theme.of(context).colorScheme.onSurfaceVariant,
                            ),
                          ),
                        ],
                      ),
                      IconButton(
                        onPressed: () {
                          Navigator.pop(context);
                        },
                        icon: const Icon(Icons.close),
                      ),
                    ],
                  ),
                ),
                // Devices list
                Expanded(
                  child: _isLoadingDevices && devices.isEmpty
                      ? Center(
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              CircularProgressIndicator(
                                color: Theme.of(context).colorScheme.primary,
                              ),
                              const SizedBox(height: 16),
                              Text(
                                'Loading devices...',
                                style: Theme.of(context).textTheme.bodyMedium,
                              ),
                            ],
                          ),
                        )
                      : devices.isEmpty
                          ? Center(
                              child: Column(
                                mainAxisAlignment: MainAxisAlignment.center,
                                children: [
                                  Icon(
                                    Icons.devices_other,
                                    size: 64,
                                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                                  ),
                                  const SizedBox(height: 16),
                                  Text(
                                    'No devices found',
                                    style: Theme.of(context).textTheme.titleMedium,
                                  ),
                                  const SizedBox(height: 8),
                                  Text(
                                    'Make sure Spotify is running on your devices',
                                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                                    ),
                                  ),
                                  const SizedBox(height: 24),
                                  ElevatedButton.icon(
                                    onPressed: _loadDevices,
                                    icon: const Icon(Icons.refresh),
                                    label: const Text('Retry'),
                                  ),
                                ],
                              ),
                            )
                          : ListView.builder(
                              controller: scrollController,
                              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                              itemCount: devices.length,
                              itemBuilder: (context, index) {
                                final device = devices[index];
                                final isActive = device.isActive && activeDeviceId == device.id;

                                return Padding(
                                  padding: const EdgeInsets.symmetric(vertical: 8),
                                  child: Card(
                                    elevation: isActive ? 4 : 1,
                                    color: isActive
                                        ? Theme.of(context).colorScheme.primary.withValues(alpha: 0.1)
                                        : Theme.of(context).colorScheme.surface,
                                    child: InkWell(
                                      onTap: isActive || _isSwitching
                                          ? null
                                          : () => _switchDevice(device),
                                      borderRadius: BorderRadius.circular(12),
                                      child: Padding(
                                        padding: const EdgeInsets.all(16),
                                        child: Row(
                                          children: [
                                            // Device icon
                                            Container(
                                              padding: const EdgeInsets.all(12),
                                              decoration: BoxDecoration(
                                                color: isActive
                                                    ? Theme.of(context).colorScheme.primary
                                                    : Theme.of(context).colorScheme.primaryContainer,
                                                borderRadius: BorderRadius.circular(8),
                                              ),
                                              child: Icon(
                                                _getDeviceIcon(device.type),
                                                color: isActive
                                                    ? Theme.of(context).colorScheme.onPrimary
                                                    : Theme.of(context).colorScheme.onPrimaryContainer,
                                                size: 24,
                                              ),
                                            ),
                                            const SizedBox(width: 16),
                                            // Device info
                                            Expanded(
                                              child: Column(
                                                crossAxisAlignment: CrossAxisAlignment.start,
                                                children: [
                                                  Text(
                                                    device.name,
                                                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                                                      fontWeight: FontWeight.w500,
                                                      color: Theme.of(context).colorScheme.onSurface,
                                                    ),
                                                  ),
                                                  const SizedBox(height: 4),
                                                  Text(
                                                    _formatDeviceType(device.type),
                                                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                                                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                                                    ),
                                                  ),
                                                  if (device.volumePercent != null)
                                                    Padding(
                                                      padding: const EdgeInsets.only(top: 4),
                                                      child: Text(
                                                        'Volume: ${device.volumePercent}%',
                                                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                                                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                                                        ),
                                                      ),
                                                    ),
                                                ],
                                              ),
                                            ),
                                            const SizedBox(width: 16),
                                            // Status indicator
                                            if (isActive)
                                              Container(
                                                padding: const EdgeInsets.symmetric(
                                                  horizontal: 12,
                                                  vertical: 6,
                                                ),
                                                decoration: BoxDecoration(
                                                  color: Theme.of(context).colorScheme.primary,
                                                  borderRadius: BorderRadius.circular(20),
                                                ),
                                                child: Row(
                                                  mainAxisSize: MainAxisSize.min,
                                                  children: [
                                                    Icon(
                                                      Icons.check_circle,
                                                      color: Theme.of(context).colorScheme.onPrimary,
                                                      size: 16,
                                                    ),
                                                    const SizedBox(width: 4),
                                                    Text(
                                                      'Active',
                                                      style: Theme.of(context).textTheme.labelSmall?.copyWith(
                                                        color: Theme.of(context).colorScheme.onPrimary,
                                                        fontWeight: FontWeight.w500,
                                                      ),
                                                    ),
                                                  ],
                                                ),
                                              )
                                            else if (_isSwitching)
                                              SizedBox(
                                                width: 20,
                                                height: 20,
                                                child: CircularProgressIndicator(
                                                  strokeWidth: 2,
                                                  valueColor: AlwaysStoppedAnimation<Color>(
                                                    Theme.of(context).colorScheme.primary,
                                                  ),
                                                ),
                                              )
                                            else
                                              Icon(
                                                Icons.arrow_forward_ios,
                                                size: 16,
                                                color: Theme.of(context).colorScheme.onSurfaceVariant,
                                              ),
                                          ],
                                        ),
                                      ),
                                    ),
                                  ),
                                );
                              },
                            ),
                ),
              ],
            );
          },
        );
      },
    );
  }
}

class DevicesPage extends StatelessWidget {
  final SpotifyController spotifyController;

  const DevicesPage({
    super.key,
    required this.spotifyController,
  });

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Container(),
    );
  }
}

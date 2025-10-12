import 'package:flutter/material.dart';
import '../models/homepage_shortcut.dart';
import '../controllers/spotify_controller.dart';

class HomepageShortcutsWidget extends StatelessWidget {
  final List<HomepageShortcut> shortcuts;
  final SpotifyController controller;

  const HomepageShortcutsWidget({
    super.key,
    required this.shortcuts,
    required this.controller,
  });

  @override
  Widget build(BuildContext context) {
    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      padding: const EdgeInsets.symmetric(horizontal: 8),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2,
        childAspectRatio: 3.2,
        crossAxisSpacing: 8,
        mainAxisSpacing: 8,
      ),
      itemCount: shortcuts.length > 8 ? 8 : shortcuts.length,
      itemBuilder: (context, index) {
        final shortcut = shortcuts[index];
        return ShortcutCard(
          shortcut: shortcut,
          controller: controller,
        );
      },
    );
  }
}

class ShortcutCard extends StatelessWidget {
  final HomepageShortcut shortcut;
  final SpotifyController controller;

  const ShortcutCard({
    super.key,
    required this.shortcut,
    required this.controller,
  });

  @override
  Widget build(BuildContext context) {
    final isDarkMode = Theme.of(context).brightness == Brightness.dark;

    return Container(
      decoration: BoxDecoration(
        color: const Color(0xFF2a2a2a),
        borderRadius: BorderRadius.circular(4),
      ),
          child: Row(
            children: [
              // Image
              ClipRRect(
                borderRadius: const BorderRadius.only(
                  topLeft: Radius.circular(4),
                  bottomLeft: Radius.circular(4),
                ),
                child: Stack(
                  children: [
                    Container(
                      width: 64,
                      height: 64,
                      decoration: BoxDecoration(
                        color: Colors.grey[800],
                      ),
                      child: shortcut.imageUrl.isNotEmpty
                          ? Image.network(
                              shortcut.imageUrl,
                              fit: BoxFit.cover,
                              errorBuilder: (context, error, stackTrace) {
                                return const Center(
                                  child: Icon(
                                    Icons.music_note,
                                    size: 24,
                                    color: Colors.white54,
                                  ),
                                );
                              },
                            )
                          : const Center(
                              child: Icon(
                                Icons.music_note,
                                size: 24,
                                color: Colors.white54,
                              ),
                            ),
                    ),
                    // Progress bar for episodes
                    if (shortcut.progressPercentage != null)
                      Positioned(
                        bottom: 0,
                        left: 0,
                        right: 0,
                        child: LinearProgressIndicator(
                          value: shortcut.progressPercentage! / 100,
                          backgroundColor: Colors.grey[700],
                          valueColor: AlwaysStoppedAnimation<Color>(
                            Theme.of(context).primaryColor,
                          ),
                          minHeight: 3,
                        ),
                      ),
                  ],
                ),
              ),

              // Title and Play Button
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          shortcut.title,
                          style: TextStyle(
                            color: isDarkMode ? Colors.white : Colors.black87,
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                          ),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                      // Play button - only interactive element
                      if (shortcut.isPlaying)
                        Container(
                          width: 32,
                          height: 32,
                          decoration: BoxDecoration(
                            color: Theme.of(context).primaryColor,
                            shape: BoxShape.circle,
                          ),
                          child: const Icon(
                            Icons.pause,
                            color: Colors.white,
                            size: 18,
                          ),
                        )
                      else
                        Material(
                          color: Colors.transparent,
                          child: InkWell(
                            borderRadius: BorderRadius.circular(16),
                            onTap: () async {
                              await controller.playHomepageItem(
                                shortcut.id,
                                shortcut.type,
                              );
                            },
                            child: Container(
                              width: 32,
                              height: 32,
                              decoration: BoxDecoration(
                                color: Theme.of(context).primaryColor,
                                shape: BoxShape.circle,
                              ),
                              child: const Icon(
                                Icons.play_arrow,
                                color: Colors.white,
                                size: 18,
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ],
          ),
    );
  }
}

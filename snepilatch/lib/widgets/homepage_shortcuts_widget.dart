import 'package:flutter/material.dart';
import '../models/homepage_shortcut.dart';
import '../controllers/spotify_controller.dart';
import '../models/homepage_item.dart';
import '../screens/album_detail_page.dart';
import '../screens/playlist_detail_page.dart';
import '../screens/artist_detail_page.dart';

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

class ShortcutCard extends StatefulWidget {
  final HomepageShortcut shortcut;
  final SpotifyController controller;

  const ShortcutCard({
    super.key,
    required this.shortcut,
    required this.controller,
  });

  @override
  State<ShortcutCard> createState() => _ShortcutCardState();
}

class _ShortcutCardState extends State<ShortcutCard> {
  bool _isHovering = false;

  @override
  Widget build(BuildContext context) {
    final isDarkMode = Theme.of(context).brightness == Brightness.dark;

    return GestureDetector(
      onTap: () {
        // Navigate to the appropriate detail page based on item type
        switch (widget.shortcut.type) {
          case 'album':
            Navigator.push(
              context,
              MaterialPageRoute(
                builder: (context) => AlbumDetailPage(
                  album: HomepageItem(
                    id: widget.shortcut.id,
                    title: widget.shortcut.title,
                    imageUrl: widget.shortcut.imageUrl,
                    href: widget.shortcut.href,
                    type: widget.shortcut.type,
                  ),
                  controller: widget.controller,
                ),
              ),
            );
            break;
          case 'playlist':
            Navigator.push(
              context,
              MaterialPageRoute(
                builder: (context) => PlaylistDetailPage(
                  playlist: HomepageItem(
                    id: widget.shortcut.id,
                    title: widget.shortcut.title,
                    imageUrl: widget.shortcut.imageUrl,
                    href: widget.shortcut.href,
                    type: widget.shortcut.type,
                  ),
                  controller: widget.controller,
                ),
              ),
            );
            break;
          case 'artist':
            Navigator.push(
              context,
              MaterialPageRoute(
                builder: (context) => ArtistDetailPage(
                  artist: HomepageItem(
                    id: widget.shortcut.id,
                    title: widget.shortcut.title,
                    imageUrl: widget.shortcut.imageUrl,
                    href: widget.shortcut.href,
                    type: widget.shortcut.type,
                  ),
                  controller: widget.controller,
                ),
              ),
            );
            break;
          default:
            // Fallback to web navigation for other types
            widget.controller.navigateToHomepageItem(widget.shortcut.href);
        }
      },
      child: MouseRegion(
        onEnter: (_) => setState(() => _isHovering = true),
        onExit: (_) => setState(() => _isHovering = false),
        child: Container(
          decoration: BoxDecoration(
            color: Color(0xFF1a1a1a),
            borderRadius: BorderRadius.circular(6),
            boxShadow: _isHovering
                ? [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.5),
                      blurRadius: 8,
                      offset: const Offset(0, 4),
                    ),
                  ]
                : [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.3),
                      blurRadius: 4,
                      offset: const Offset(0, 2),
                    ),
                  ],
          ),
          child: Row(
            children: [
              // Image
              ClipRRect(
                borderRadius: const BorderRadius.only(
                  topLeft: Radius.circular(6),
                  bottomLeft: Radius.circular(6),
                ),
                child: Stack(
                  children: [
                    Container(
                      width: 64,
                      height: 64,
                      color: Colors.transparent,
                      child: widget.shortcut.imageUrl.isNotEmpty
                          ? Stack(
                              children: [
                                Image.network(
                                  widget.shortcut.imageUrl,
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
                                ),
                                // Hover overlay
                                if (_isHovering)
                                  Container(
                                    color: Colors.black.withValues(alpha: 0.2),
                                  ),
                              ],
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
                    if (widget.shortcut.progressPercentage != null)
                      Positioned(
                        bottom: 0,
                        left: 0,
                        right: 0,
                        child: LinearProgressIndicator(
                          value: widget.shortcut.progressPercentage! / 100,
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
                          widget.shortcut.title,
                          style: TextStyle(
                            color: isDarkMode ? Colors.white : Colors.black87,
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                          ),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                        // Play button
                        if (widget.shortcut.isPlaying)
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
                                await widget.controller.playHomepageItem(
                                  widget.shortcut.id,
                                  widget.shortcut.type,
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
        ),
      ),
    );
  }
}

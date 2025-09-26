import 'package:flutter/material.dart';
import '../services/theme_service.dart';

class ThemeSettings extends StatelessWidget {
  final ThemeService themeService;

  const ThemeSettings({
    super.key,
    required this.themeService,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.all(16),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Theme Settings',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 16),

            // Theme Mode Selection
            Text(
              'Appearance',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: _buildThemeModeOption(
                    context,
                    ThemeMode.system,
                    'Auto',
                    Icons.brightness_auto,
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: _buildThemeModeOption(
                    context,
                    ThemeMode.light,
                    'Light',
                    Icons.light_mode,
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: _buildThemeModeOption(
                    context,
                    ThemeMode.dark,
                    'Dark',
                    Icons.dark_mode,
                  ),
                ),
              ],
            ),

            const SizedBox(height: 24),

            // Color Source Selection
            Text(
              'Color Source',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 8),

            // Song Cover Colors Toggle
            SwitchListTile(
              title: const Text('Use Song Cover Colors'),
              subtitle: const Text('Automatically theme based on album art'),
              value: themeService.useSongCoverColors,
              onChanged: (value) {
                themeService.setUseSongCoverColors(value);
              },
              activeTrackColor: Theme.of(context).colorScheme.primary.withValues(alpha: 0.5),
              thumbColor: WidgetStateProperty.all(Theme.of(context).colorScheme.primary),
            ),

            // Only show color selection if not using song cover colors
            if (!themeService.useSongCoverColors) ...[
              const SizedBox(height: 16),
              Text(
                'Choose Color',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 12),
              _buildColorGrid(context),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildThemeModeOption(
    BuildContext context,
    ThemeMode mode,
    String label,
    IconData icon,
  ) {
    final isSelected = themeService.themeMode == mode;
    final primaryColor = Theme.of(context).colorScheme.primary;

    return InkWell(
      onTap: () => themeService.setThemeMode(mode),
      borderRadius: BorderRadius.circular(8),
      child: Container(
        height: 72,
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 6),
        decoration: BoxDecoration(
          color: isSelected
              ? primaryColor.withValues(alpha: 0.1)
              : Colors.transparent,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(
            color: isSelected
                ? primaryColor
                : Theme.of(context).colorScheme.outline.withValues(alpha: 0.2),
          ),
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              icon,
              color: isSelected
                  ? primaryColor
                  : Theme.of(context).colorScheme.onSurface,
              size: 20,
            ),
            const SizedBox(height: 4),
            Text(
              label,
              style: TextStyle(
                color: isSelected
                    ? primaryColor
                    : Theme.of(context).colorScheme.onSurface,
                fontWeight: isSelected ? FontWeight.w500 : FontWeight.normal,
                fontSize: 12,
              ),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildColorGrid(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final availableWidth = constraints.maxWidth;
        final itemWidth = (availableWidth - 24) / 4; // 4 columns with spacing

        return Wrap(
          spacing: 8,
          runSpacing: 8,
          children: ThemeService.predefinedColors.map((color) {
            return SizedBox(
              width: itemWidth,
              child: _buildColorOption(context, color),
            );
          }).toList(),
        );
      },
    );
  }

  Widget _buildColorOption(BuildContext context, Color color) {
    final isSelected = themeService.seedColor == color;

    return InkWell(
      onTap: () => themeService.setSeedColor(color),
      borderRadius: BorderRadius.circular(8),
      child: Container(
        height: 60,
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: isSelected
              ? color.withValues(alpha: 0.1)
              : Colors.transparent,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(
            color: isSelected
                ? color
                : Theme.of(context).colorScheme.outline.withValues(alpha: 0.2),
            width: isSelected ? 2 : 1,
          ),
        ),
        child: Center(
          child: Container(
            width: 32,
            height: 32,
            decoration: BoxDecoration(
              color: color,
              shape: BoxShape.circle,
              boxShadow: [
                BoxShadow(
                  color: color.withValues(alpha: 0.3),
                  blurRadius: 4,
                  spreadRadius: 0,
                ),
              ],
            ),
            child: isSelected
                ? Icon(
                    Icons.check,
                    color: color.computeLuminance() > 0.5
                        ? Colors.black
                        : Colors.white,
                    size: 16,
                  )
                : null,
          ),
        ),
      ),
    );
  }
}
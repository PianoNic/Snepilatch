import 'package:flutter/material.dart';
import 'package:palette_generator/palette_generator.dart';

class ThemeService extends ChangeNotifier {
  Color _seedColor = Colors.deepPurple;
  ThemeMode _themeMode = ThemeMode.dark;
  PaletteGenerator? _currentPalette;
  String? _currentHexColor;

  Color get seedColor => _seedColor;
  ThemeMode get themeMode => _themeMode;
  PaletteGenerator? get currentPalette => _currentPalette;
  String? get currentHexColor => _currentHexColor;

  // Helper method to convert Color to hex string (like in Schuly)
  String _colorToHexString(Color color) {
    final r = (color.r * 255.0).round() & 0xff;
    final g = (color.g * 255.0).round() & 0xff;
    final b = (color.b * 255.0).round() & 0xff;

    final hexR = r.toRadixString(16).padLeft(2, '0');
    final hexG = g.toRadixString(16).padLeft(2, '0');
    final hexB = b.toRadixString(16).padLeft(2, '0');

    return '#$hexR$hexG$hexB'; // Return without alpha for standard hex
  }

  Future<void> updateThemeFromImageUrl(String? imageUrl) async {
    if (imageUrl == null || imageUrl.isEmpty) {
      _resetToDefaultTheme();
      return;
    }

    try {
      final PaletteGenerator paletteGenerator = await PaletteGenerator.fromImageProvider(
        NetworkImage(imageUrl),
        size: const Size(200, 200),
        maximumColorCount: 20,
      );

      _currentPalette = paletteGenerator;

      // Get the dominant color or vibrant color
      final PaletteColor? dominantColor = paletteGenerator.dominantColor;
      final PaletteColor? vibrantColor = paletteGenerator.vibrantColor;
      final PaletteColor? darkVibrantColor = paletteGenerator.darkVibrantColor;
      final PaletteColor? mutedColor = paletteGenerator.mutedColor;

      // Choose the best color for the seed (similar to Schuly's approach)
      Color newSeedColor;

      // Priority: Vibrant > Dominant > Dark Vibrant > Muted
      if (vibrantColor != null) {
        newSeedColor = vibrantColor.color;
      } else if (dominantColor != null) {
        newSeedColor = dominantColor.color;
      } else if (darkVibrantColor != null) {
        newSeedColor = darkVibrantColor.color;
      } else if (mutedColor != null) {
        newSeedColor = mutedColor.color;
      } else {
        newSeedColor = Colors.deepPurple;
      }

      _seedColor = newSeedColor;
      _currentHexColor = _colorToHexString(newSeedColor);

      notifyListeners();
    } catch (e) {
      debugPrint('Error generating palette from image: $e');
      _resetToDefaultTheme();
    }
  }

  void _resetToDefaultTheme() {
    _seedColor = Colors.deepPurple;
    _themeMode = ThemeMode.dark;
    _currentPalette = null;
    _currentHexColor = null;
    notifyListeners();
  }

  // Light theme (like Schuly)
  ThemeData lightTheme() {
    final ColorScheme colorScheme = ColorScheme.fromSeed(
      seedColor: _seedColor,
      brightness: Brightness.light,
    );

    return ThemeData(
      colorScheme: colorScheme,
      useMaterial3: true,
    );
  }

  // Dark theme (like Schuly)
  ThemeData darkTheme() {
    final ColorScheme colorScheme = ColorScheme.fromSeed(
      seedColor: _seedColor,
      brightness: Brightness.dark,
    );

    return ThemeData(
      colorScheme: colorScheme,
      useMaterial3: true,
    );
  }

  void setThemeMode(ThemeMode mode) {
    _themeMode = mode;
    notifyListeners();
  }
}
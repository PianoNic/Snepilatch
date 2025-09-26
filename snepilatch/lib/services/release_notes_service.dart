import 'dart:convert';
import 'package:http/http.dart' as http;

class ReleaseNote {
  final String version;
  final DateTime releaseDate;
  final String title;
  final String description;

  ReleaseNote({
    required this.version,
    required this.releaseDate,
    required this.title,
    required this.description,
  });
}

class ReleaseNotesService {
  static const String _githubOwner = 'PianoNic';
  static const String _githubRepo = 'Snepilatch';

  static Future<List<ReleaseNote>> fetchReleaseNotes() async {
    try {
      final url = 'https://api.github.com/repos/$_githubOwner/$_githubRepo/releases';
      final response = await http.get(
        Uri.parse(url),
        headers: {
          'Accept': 'application/vnd.github.v3+json',
          'User-Agent': 'Snepilatch-App',
        },
      );

      if (response.statusCode == 200) {
        final List<dynamic> releasesJson = jsonDecode(response.body);
        return releasesJson.map((json) => _parseGitHubRelease(json)).toList();
      } else {
        throw Exception('Failed to fetch releases: ${response.statusCode}');
      }
    } catch (e) {
      throw Exception('Error fetching release notes: $e');
    }
  }

  static ReleaseNote _parseGitHubRelease(Map<String, dynamic> json) {
    final tagName = json['tag_name'] as String? ?? '';
    final version = tagName.startsWith('v') ? tagName.substring(1) : tagName;

    return ReleaseNote(
      version: version,
      releaseDate: DateTime.parse(json['published_at'] ?? DateTime.now().toIso8601String()),
      title: json['name'] ?? 'Release $version',
      description: json['body'] ?? '',
    );
  }
}
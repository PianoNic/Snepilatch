import 'dart:convert';
import 'dart:io';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:open_file/open_file.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';

class UpdateInfo {
  final String currentVersion;
  final String latestVersion;
  final String downloadUrl;
  final String releaseNotes;
  final DateTime releaseDate;
  final String htmlUrl;

  UpdateInfo({
    required this.currentVersion,
    required this.latestVersion,
    required this.downloadUrl,
    required this.releaseNotes,
    required this.releaseDate,
    required this.htmlUrl,
  });
}

class UpdateService {
  static const String _githubApiUrl =
      'https://api.github.com/repos/PianoNic/snepilatch/releases/latest';

  static final Dio _dio = Dio();

  static Future<UpdateInfo?> checkForUpdates() async {
    try {
      final packageInfo = await PackageInfo.fromPlatform();
      final currentVersion = packageInfo.version;

      final response = await http.get(Uri.parse(_githubApiUrl));

      if (response.statusCode == 200) {
        final data = json.decode(response.body);
        final latestVersion = data['tag_name']?.replaceAll('v', '') ?? '';

        if (_isNewerVersion(currentVersion, latestVersion)) {
          String? downloadUrl;

          final assets = data['assets'] as List<dynamic>?;
          if (assets != null) {
            for (var asset in assets) {
              final name = asset['name']?.toString().toLowerCase() ?? '';
              if (name.endsWith('.apk')) {
                downloadUrl = asset['browser_download_url'];
                break;
              }
            }
          }

          if (downloadUrl != null) {
            return UpdateInfo(
              currentVersion: currentVersion,
              latestVersion: latestVersion,
              downloadUrl: downloadUrl,
              releaseNotes: data['body'] ?? '',
              releaseDate: DateTime.parse(data['published_at']),
              htmlUrl: data['html_url'] ?? '',
            );
          }
        }
      }
    } catch (e) {
      debugPrint('Error checking for updates: $e');
    }
    return null;
  }

  static bool _isNewerVersion(String current, String latest) {
    try {
      final currentParts = current.split('.').map(int.parse).toList();
      final latestParts = latest.split('.').map(int.parse).toList();

      for (var i = 0; i < 3; i++) {
        final currentPart = i < currentParts.length ? currentParts[i] : 0;
        final latestPart = i < latestParts.length ? latestParts[i] : 0;

        if (latestPart > currentPart) {
          return true;
        } else if (latestPart < currentPart) {
          return false;
        }
      }
      return false;
    } catch (e) {
      debugPrint('Error comparing versions: $e');
      return false;
    }
  }

  static Future<String?> downloadApk(
    String url, {
    Function(int, int)? onProgress,
  }) async {
    try {
      final tempDir = await getTemporaryDirectory();
      final fileName = 'snepilatch-update-${DateTime.now().millisecondsSinceEpoch}.apk';
      final filePath = '${tempDir.path}/$fileName';

      await _dio.download(
        url,
        filePath,
        onReceiveProgress: onProgress,
        options: Options(
          headers: {
            'Accept': 'application/octet-stream',
          },
        ),
      );

      return filePath;
    } catch (e) {
      debugPrint('Error downloading APK: $e');
      return null;
    }
  }

  static Future<bool> checkInstallPermission() async {
    if (Platform.isAndroid) {
      if (await Permission.requestInstallPackages.isDenied) {
        final status = await Permission.requestInstallPackages.request();
        return status.isGranted;
      }
      return true;
    }
    return false;
  }

  static Future<bool> installApk(String filePath) async {
    try {
      if (Platform.isAndroid) {
        final hasPermission = await checkInstallPermission();
        if (!hasPermission) {
          debugPrint('Install permission denied');
          return false;
        }

        final result = await OpenFile.open(
          filePath,
          type: 'application/vnd.android.package-archive',
        );

        return result.type == ResultType.done;
      }
      return false;
    } catch (e) {
      debugPrint('Error installing APK: $e');
      return false;
    }
  }

  static Future<void> cleanupOldApks() async {
    try {
      final tempDir = await getTemporaryDirectory();
      final dir = Directory(tempDir.path);

      await for (final file in dir.list()) {
        if (file is File && file.path.contains('snepilatch-update-')) {
          try {
            await file.delete();
          } catch (_) {}
        }
      }
    } catch (e) {
      debugPrint('Error cleaning up old APKs: $e');
    }
  }
}
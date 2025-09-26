import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class StorageService {
  static const _storage = FlutterSecureStorage();

  static const String dismissedUpdateVersionKey = 'dismissed_update_version';
  static const String lastUpdateCheckKey = 'last_update_check';
  static const String autoCheckUpdatesKey = 'auto_check_updates';

  static Future<void> setString(String key, String value) async {
    try {
      await _storage.write(key: key, value: value);
    } catch (e) {
      debugPrint('Error writing to storage: $e');
    }
  }

  static Future<String?> getString(String key) async {
    try {
      return await _storage.read(key: key);
    } catch (e) {
      debugPrint('Error reading from storage: $e');
      return null;
    }
  }

  static Future<void> setBool(String key, bool value) async {
    await setString(key, value.toString());
  }

  static Future<bool> getBool(String key, {bool defaultValue = false}) async {
    final value = await getString(key);
    if (value == null) return defaultValue;
    return value.toLowerCase() == 'true';
  }

  static Future<void> remove(String key) async {
    try {
      await _storage.delete(key: key);
    } catch (e) {
      debugPrint('Error deleting from storage: $e');
    }
  }

  static Future<void> clearAll() async {
    try {
      await _storage.deleteAll();
    } catch (e) {
      debugPrint('Error clearing storage: $e');
    }
  }

  static Future<void> setDismissedUpdateVersion(String version) async {
    await setString(dismissedUpdateVersionKey, version);
  }

  static Future<String?> getDismissedUpdateVersion() async {
    return await getString(dismissedUpdateVersionKey);
  }

  static Future<void> clearDismissedUpdateVersion() async {
    await remove(dismissedUpdateVersionKey);
  }

  static Future<void> setLastUpdateCheck(DateTime dateTime) async {
    await setString(lastUpdateCheckKey, dateTime.toIso8601String());
  }

  static Future<DateTime?> getLastUpdateCheck() async {
    final value = await getString(lastUpdateCheckKey);
    if (value != null) {
      try {
        return DateTime.parse(value);
      } catch (_) {}
    }
    return null;
  }

  static Future<void> setAutoCheckUpdates(bool enabled) async {
    await setBool(autoCheckUpdatesKey, enabled);
  }

  static Future<bool> getAutoCheckUpdates() async {
    return await getBool(autoCheckUpdatesKey, defaultValue: true);
  }
}
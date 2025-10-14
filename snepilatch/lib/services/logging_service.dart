import 'dart:collection';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

enum LogLevel { debug, info, warning, error }

class LogEntry {
  final DateTime timestamp;
  final String message;
  final LogLevel level;
  final String? source;
  final dynamic error;
  final StackTrace? stackTrace;

  LogEntry({
    required this.timestamp,
    required this.message,
    required this.level,
    this.source,
    this.error,
    this.stackTrace,
  });

  String get formattedTime {
    final hour = timestamp.hour.toString().padLeft(2, '0');
    final minute = timestamp.minute.toString().padLeft(2, '0');
    final second = timestamp.second.toString().padLeft(2, '0');
    final ms = timestamp.millisecond.toString().padLeft(3, '0');
    return '$hour:$minute:$second.$ms';
  }

  String get levelString {
    switch (level) {
      case LogLevel.debug:
        return 'DEBUG';
      case LogLevel.info:
        return 'INFO';
      case LogLevel.warning:
        return 'WARN';
      case LogLevel.error:
        return 'ERROR';
    }
  }

  String get levelEmoji {
    switch (level) {
      case LogLevel.debug:
        return '🐛';
      case LogLevel.info:
        return 'ℹ️';
      case LogLevel.warning:
        return '⚠️';
      case LogLevel.error:
        return '❌';
    }
  }
}

class LoggingService extends ChangeNotifier {
  static const int _maxLogEntries = 2000;
  final Queue<LogEntry> _logs = Queue<LogEntry>();
  bool _isEnabled = true;

  UnmodifiableListView<LogEntry> get logs => UnmodifiableListView(_logs);
  bool get isEnabled => _isEnabled;

  void toggleLogging() {
    _isEnabled = !_isEnabled;
    // Defer notification to avoid setState during build
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
  }

  void log(
    String message, {
    LogLevel level = LogLevel.info,
    String? source,
    dynamic error,
    StackTrace? stackTrace,
  }) {
    if (!_isEnabled) return;

    final entry = LogEntry(
      timestamp: DateTime.now(),
      message: message,
      level: level,
      source: source,
      error: error,
      stackTrace: stackTrace,
    );

    _logs.addLast(entry);

    // Keep only the last N entries to prevent memory issues
    while (_logs.length > _maxLogEntries) {
      _logs.removeFirst();
    }

    // Also print to console in debug mode
    if (kDebugMode) {
      final emoji = entry.levelEmoji;
      final logMessage = '$emoji [${entry.levelString}] ${entry.formattedTime} ${source != null ? "[$source] " : ""}$message';
      debugPrint(logMessage);
      if (error != null) {
        debugPrint('Error: $error');
      }
      if (stackTrace != null) {
        debugPrint('Stack trace:\n$stackTrace');
      }
    }

    // Defer notification to avoid setState during build
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
  }

  void debug(String message, {String? source}) {
    log(message, level: LogLevel.debug, source: source);
  }

  void info(String message, {String? source}) {
    log(message, level: LogLevel.info, source: source);
  }

  void warning(String message, {String? source}) {
    log(message, level: LogLevel.warning, source: source);
  }

  void error(String message, {String? source, dynamic error, StackTrace? stackTrace}) {
    log(message, level: LogLevel.error, source: source, error: error, stackTrace: stackTrace);
  }

  void clearLogs() {
    _logs.clear();
    // Defer notification to avoid setState during build
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
  }

  List<LogEntry> getFilteredLogs({LogLevel? levelFilter, String? searchQuery}) {
    var filtered = _logs.toList();

    if (levelFilter != null) {
      filtered = filtered.where((log) => log.level == levelFilter).toList();
    }

    if (searchQuery != null && searchQuery.isNotEmpty) {
      final query = searchQuery.toLowerCase();
      filtered = filtered.where((log) {
        return log.message.toLowerCase().contains(query) ||
            (log.source?.toLowerCase().contains(query) ?? false) ||
            (log.error?.toString().toLowerCase().contains(query) ?? false);
      }).toList();
    }

    return filtered;
  }

  String exportLogs() {
    final buffer = StringBuffer();
    buffer.writeln('=== Snepilatch App Logs Export ===');
    buffer.writeln('Exported at: ${DateTime.now()}');
    buffer.writeln('Total entries: ${_logs.length}');
    buffer.writeln('Platform: ${Platform.operatingSystem}');
    buffer.writeln('');

    for (final entry in _logs) {
      buffer.writeln('${entry.levelEmoji} [${entry.levelString}] ${entry.timestamp.toIso8601String()}');
      if (entry.source != null) {
        buffer.writeln('Source: ${entry.source}');
      }
      buffer.writeln('Message: ${entry.message}');
      if (entry.error != null) {
        buffer.writeln('Error: ${entry.error}');
      }
      if (entry.stackTrace != null) {
        buffer.writeln('Stack trace:');
        buffer.writeln(entry.stackTrace);
      }
      buffer.writeln('---');
    }

    return buffer.toString();
  }
}

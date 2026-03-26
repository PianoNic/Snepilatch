import '../services/logging_service.dart';

/// Global logger instance that can be used throughout the app
/// Usage: logger.info('message', source: 'ClassName');
class Logger {
  static LoggingService? _service;

  static void init(LoggingService service) {
    _service = service;
  }

  static LoggingService get _logger {
    if (_service == null) {
      throw StateError('Logger not initialized. Call Logger.init() first.');
    }
    return _service!;
  }

  static void debug(String message, {String? source}) {
    _logger.debug(message, source: source);
  }

  static void info(String message, {String? source}) {
    _logger.info(message, source: source);
  }

  static void warning(String message, {String? source}) {
    _logger.warning(message, source: source);
  }

  static void error(String message, {String? source, dynamic error, StackTrace? stackTrace}) {
    _logger.error(message, source: source, error: error, stackTrace: stackTrace);
  }
}

/// Convenience global logger instance - use these functions directly
void logDebug(String message, {String? source}) => Logger.debug(message, source: source);
void logInfo(String message, {String? source}) => Logger.info(message, source: source);
void logWarning(String message, {String? source}) => Logger.warning(message, source: source);
void logError(String message, {String? source, dynamic error, StackTrace? stackTrace}) =>
    Logger.error(message, source: source, error: error, stackTrace: stackTrace);

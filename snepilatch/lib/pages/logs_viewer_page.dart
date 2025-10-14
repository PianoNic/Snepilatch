import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../services/logging_service.dart';

class LogsViewerPage extends StatefulWidget {
  final LoggingService loggingService;

  const LogsViewerPage({super.key, required this.loggingService});

  @override
  State<LogsViewerPage> createState() => _LogsViewerPageState();
}

class _LogsViewerPageState extends State<LogsViewerPage> {
  LogLevel? _selectedLevel;
  String _searchQuery = '';
  final _searchController = TextEditingController();
  bool _autoScroll = true;
  final _scrollController = ScrollController();

  @override
  void dispose() {
    _searchController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  Color _getLogColor(LogLevel level) {
    switch (level) {
      case LogLevel.debug:
        return Colors.grey;
      case LogLevel.info:
        return Colors.blue;
      case LogLevel.warning:
        return Colors.orange;
      case LogLevel.error:
        return Colors.red;
    }
  }

  IconData _getLogIcon(LogLevel level) {
    switch (level) {
      case LogLevel.debug:
        return Icons.bug_report;
      case LogLevel.info:
        return Icons.info_outline;
      case LogLevel.warning:
        return Icons.warning_amber;
      case LogLevel.error:
        return Icons.error_outline;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Console Logs'),
        actions: [
          IconButton(
            icon: Icon(_autoScroll ? Icons.vertical_align_bottom : Icons.vertical_align_top),
            tooltip: _autoScroll ? 'Auto-scroll enabled' : 'Auto-scroll disabled',
            onPressed: () {
              setState(() {
                _autoScroll = !_autoScroll;
              });
            },
          ),
          PopupMenuButton<String>(
            icon: const Icon(Icons.more_vert),
            onSelected: (value) {
              switch (value) {
                case 'clear':
                  _showClearLogsDialog();
                  break;
                case 'export':
                  _exportLogs();
                  break;
                case 'toggle':
                  widget.loggingService.toggleLogging();
                  ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(
                      content: Text(
                        widget.loggingService.isEnabled ? 'Logging enabled' : 'Logging disabled',
                      ),
                    ),
                  );
                  break;
              }
            },
            itemBuilder: (context) => [
              const PopupMenuItem(
                value: 'clear',
                child: Row(
                  children: [
                    Icon(Icons.clear_all, size: 20),
                    SizedBox(width: 12),
                    Text('Clear Logs'),
                  ],
                ),
              ),
              const PopupMenuItem(
                value: 'export',
                child: Row(
                  children: [
                    Icon(Icons.download, size: 20),
                    SizedBox(width: 12),
                    Text('Export Logs'),
                  ],
                ),
              ),
              PopupMenuItem(
                value: 'toggle',
                child: Row(
                  children: [
                    Icon(
                      widget.loggingService.isEnabled ? Icons.pause : Icons.play_arrow,
                      size: 20,
                    ),
                    const SizedBox(width: 12),
                    Text(widget.loggingService.isEnabled ? 'Disable logging' : 'Enable logging'),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
      body: Column(
        children: [
          // Filters section
          Container(
            padding: const EdgeInsets.all(8.0),
            color: Theme.of(context).colorScheme.surfaceContainerHighest.withValues(alpha: 0.3),
            child: Column(
              children: [
                // Search bar
                TextField(
                  controller: _searchController,
                  decoration: InputDecoration(
                    hintText: 'Search logs...',
                    prefixIcon: const Icon(Icons.search),
                    suffixIcon: _searchQuery.isNotEmpty
                        ? IconButton(
                            icon: const Icon(Icons.clear),
                            onPressed: () {
                              setState(() {
                                _searchController.clear();
                                _searchQuery = '';
                              });
                            },
                          )
                        : null,
                    filled: true,
                    fillColor: Theme.of(context).colorScheme.surface,
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(8),
                      borderSide: BorderSide.none,
                    ),
                    contentPadding: const EdgeInsets.symmetric(horizontal: 16),
                  ),
                  onChanged: (value) {
                    setState(() {
                      _searchQuery = value;
                    });
                  },
                ),
                const SizedBox(height: 8),
                // Level filter chips
                SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Row(
                    children: [
                      FilterChip(
                        label: const Text('All'),
                        selected: _selectedLevel == null,
                        onSelected: (selected) {
                          setState(() {
                            _selectedLevel = null;
                          });
                        },
                      ),
                      const SizedBox(width: 8),
                      FilterChip(
                        label: const Text('Debug'),
                        selected: _selectedLevel == LogLevel.debug,
                        avatar: Icon(Icons.bug_report, size: 16, color: _getLogColor(LogLevel.debug)),
                        onSelected: (selected) {
                          setState(() {
                            _selectedLevel = selected ? LogLevel.debug : null;
                          });
                        },
                      ),
                      const SizedBox(width: 8),
                      FilterChip(
                        label: const Text('Info'),
                        selected: _selectedLevel == LogLevel.info,
                        avatar: Icon(Icons.info_outline, size: 16, color: _getLogColor(LogLevel.info)),
                        onSelected: (selected) {
                          setState(() {
                            _selectedLevel = selected ? LogLevel.info : null;
                          });
                        },
                      ),
                      const SizedBox(width: 8),
                      FilterChip(
                        label: const Text('Warning'),
                        selected: _selectedLevel == LogLevel.warning,
                        avatar: Icon(Icons.warning_amber, size: 16, color: _getLogColor(LogLevel.warning)),
                        onSelected: (selected) {
                          setState(() {
                            _selectedLevel = selected ? LogLevel.warning : null;
                          });
                        },
                      ),
                      const SizedBox(width: 8),
                      FilterChip(
                        label: const Text('Error'),
                        selected: _selectedLevel == LogLevel.error,
                        avatar: Icon(Icons.error_outline, size: 16, color: _getLogColor(LogLevel.error)),
                        onSelected: (selected) {
                          setState(() {
                            _selectedLevel = selected ? LogLevel.error : null;
                          });
                        },
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          // Logs list
          Expanded(
            child: AnimatedBuilder(
              animation: widget.loggingService,
              builder: (context, child) {
                final filteredLogs = widget.loggingService.getFilteredLogs(
                  levelFilter: _selectedLevel,
                  searchQuery: _searchQuery,
                );

                if (!widget.loggingService.isEnabled) {
                  return Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(Icons.pause_circle_outline, size: 64, color: Colors.grey[400]),
                        const SizedBox(height: 16),
                        Text(
                          'Logging is disabled',
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        const SizedBox(height: 8),
                        ElevatedButton.icon(
                          onPressed: () {
                            widget.loggingService.toggleLogging();
                          },
                          icon: const Icon(Icons.play_arrow),
                          label: const Text('Enable Logging'),
                        ),
                      ],
                    ),
                  );
                }

                if (filteredLogs.isEmpty) {
                  return Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(Icons.description_outlined, size: 64, color: Colors.grey[400]),
                        const SizedBox(height: 16),
                        Text(
                          _searchQuery.isNotEmpty || _selectedLevel != null
                              ? 'No logs match the filter'
                              : 'No logs yet',
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        if (_searchQuery.isNotEmpty || _selectedLevel != null) ...[
                          const SizedBox(height: 8),
                          TextButton(
                            onPressed: () {
                              setState(() {
                                _searchController.clear();
                                _searchQuery = '';
                                _selectedLevel = null;
                              });
                            },
                            child: const Text('Clear Filters'),
                          ),
                        ],
                      ],
                    ),
                  );
                }

                // Auto-scroll to bottom when new logs arrive
                if (_autoScroll) {
                  WidgetsBinding.instance.addPostFrameCallback((_) {
                    if (_scrollController.hasClients) {
                      _scrollController.jumpTo(_scrollController.position.maxScrollExtent);
                    }
                  });
                }

                return ListView.builder(
                  controller: _scrollController,
                  padding: EdgeInsets.only(
                    top: 8,
                    bottom: MediaQuery.of(context).padding.bottom + 8,
                    left: 0,
                    right: 0,
                  ),
                  itemCount: filteredLogs.length,
                  itemBuilder: (context, index) {
                    final log = filteredLogs[index];
                    final color = _getLogColor(log.level);
                    final icon = _getLogIcon(log.level);

                    return Card(
                      margin: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                      child: InkWell(
                        onTap: () => _showLogDetails(context, log),
                        onLongPress: () => _copyLogToClipboard(context, log),
                        child: Padding(
                          padding: const EdgeInsets.all(12),
                          child: Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Icon(icon, size: 20, color: color),
                              const SizedBox(width: 12),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Row(
                                      children: [
                                        Text(
                                          log.formattedTime,
                                          style: TextStyle(
                                            fontSize: 12,
                                            color: Theme.of(context).textTheme.bodySmall?.color,
                                          ),
                                        ),
                                        if (log.source != null) ...[
                                          const SizedBox(width: 8),
                                          Container(
                                            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                                            decoration: BoxDecoration(
                                              color: color.withValues(alpha: 0.2),
                                              borderRadius: BorderRadius.circular(4),
                                            ),
                                            child: Text(
                                              log.source!,
                                              style: TextStyle(
                                                fontSize: 11,
                                                color: color,
                                                fontWeight: FontWeight.bold,
                                              ),
                                            ),
                                          ),
                                        ],
                                      ],
                                    ),
                                    const SizedBox(height: 4),
                                    Text(
                                      log.message,
                                      style: const TextStyle(fontSize: 14),
                                      maxLines: 3,
                                      overflow: TextOverflow.ellipsis,
                                    ),
                                    if (log.error != null) ...[
                                      const SizedBox(height: 4),
                                      Text(
                                        'Error: ${log.error}',
                                        style: TextStyle(
                                          fontSize: 12,
                                          color: Colors.red[700],
                                        ),
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                      ),
                                    ],
                                  ],
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  void _showLogDetails(BuildContext context, LogEntry log) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Row(
          children: [
            Icon(_getLogIcon(log.level), color: _getLogColor(log.level)),
            const SizedBox(width: 12),
            Text(log.levelString),
          ],
        ),
        content: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              _buildDetailRow('Time', log.timestamp.toString()),
              if (log.source != null) _buildDetailRow('Source', log.source!),
              _buildDetailRow('Message', log.message),
              if (log.error != null) _buildDetailRow('Error', log.error.toString()),
              if (log.stackTrace != null)
                _buildDetailRow('Stack Trace', log.stackTrace.toString()),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () {
              _copyLogToClipboard(context, log);
              Navigator.of(context).pop();
            },
            child: const Text('Copy'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Close'),
          ),
        ],
      ),
    );
  }

  Widget _buildDetailRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 12),
          ),
          const SizedBox(height: 2),
          Text(
            value,
            style: const TextStyle(fontSize: 13),
          ),
          const SizedBox(height: 8),
        ],
      ),
    );
  }

  void _copyLogToClipboard(BuildContext context, LogEntry log) {
    final text = StringBuffer();
    text.writeln('[${log.levelString}] ${log.timestamp}');
    if (log.source != null) text.writeln('Source: ${log.source}');
    text.writeln('Message: ${log.message}');
    if (log.error != null) text.writeln('Error: ${log.error}');
    if (log.stackTrace != null) text.writeln('Stack Trace:\n${log.stackTrace}');

    Clipboard.setData(ClipboardData(text: text.toString()));
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Log copied to clipboard'),
        duration: Duration(seconds: 1),
      ),
    );
  }

  void _showClearLogsDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Clear All Logs'),
        content: const Text('This action cannot be undone. Are you sure you want to clear all logs?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () {
              widget.loggingService.clearLogs();
              Navigator.of(context).pop();
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Logs cleared')),
              );
            },
            child: const Text('Clear'),
          ),
        ],
      ),
    );
  }

  void _exportLogs() {
    final logs = widget.loggingService.exportLogs();
    Clipboard.setData(ClipboardData(text: logs));
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Logs exported to clipboard'),
        duration: Duration(seconds: 2),
      ),
    );
  }
}

import 'dart:convert';

class Device {
  final String id;
  final String name;
  final String type;
  final bool isActive;
  final int? volumePercent;

  Device({
    required this.id,
    required this.name,
    required this.type,
    required this.isActive,
    this.volumePercent,
  });

  Device copyWith({
    String? id,
    String? name,
    String? type,
    bool? isActive,
    int? volumePercent,
  }) {
    return Device(
      id: id ?? this.id,
      name: name ?? this.name,
      type: type ?? this.type,
      isActive: isActive ?? this.isActive,
      volumePercent: volumePercent ?? this.volumePercent,
    );
  }

  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'type': type,
    'isActive': isActive,
    'volumePercent': volumePercent,
  };

  factory Device.fromJson(Map<String, dynamic> json) {
    return Device(
      id: json['id']?.toString() ?? '',
      name: json['name']?.toString() ?? 'Unknown Device',
      type: json['type']?.toString() ?? 'unknown',
      isActive: json['isActive'] ?? false,
      volumePercent: json['volumePercent'] as int?,
    );
  }

  static List<Device> fromJsonList(String jsonString) {
    try {
      final List<dynamic> jsonList = jsonDecode(jsonString);
      return jsonList.map((json) => Device.fromJson(json as Map<String, dynamic>)).toList();
    } catch (e) {
      return [];
    }
  }

  static Device? fromJsonStringSingle(String jsonString) {
    try {
      final json = jsonDecode(jsonString);
      return Device.fromJson(json);
    } catch (e) {
      return null;
    }
  }
}

import 'dart:collection';
import 'dashboard.dart';

/// A chart-ready point, independent of the Tenun widget API.
class FarabiSeriesPoint {
  final String category;
  final double value;
  final String? series;
  final int sourceIndex;
  const FarabiSeriesPoint(this.category, this.value, this.series, this.sourceIndex);
}

/// Converts authorized Farabi rows into typed series without evaluating expressions.
class FarabiTenunDataAdapter {
  const FarabiTenunDataAdapter();

  List<FarabiSeriesPoint> points(FarabiChart chart) {
    if (!chart.renderer.startsWith('tenun.')) {
      throw ArgumentError.value(chart.renderer, 'renderer', 'Not a Tenun chart');
    }
    final categoryField = chart.binding['categoryField'];
    final valueField = chart.binding['valueField'];
    final seriesField = chart.binding['seriesField'];
    if (categoryField is! String || categoryField.isEmpty ||
        valueField is! String || valueField.isEmpty) {
      throw const FormatException('Chart requires category and value bindings');
    }
    if (seriesField != null && seriesField is! String) {
      throw const FormatException('Invalid series binding');
    }
    final result = <FarabiSeriesPoint>[];
    for (var i = 0; i < chart.rows.length; i++) {
      final row = chart.rows[i];
      final rawCategory = row[categoryField];
      final rawValue = row[valueField];
      if (rawCategory == null || rawValue == null) continue;
      final value = rawValue is num ? rawValue.toDouble() : double.tryParse(rawValue.toString());
      if (value == null || !value.isFinite) continue;
      final series = seriesField == null ? null : row[seriesField]?.toString();
      result.add(FarabiSeriesPoint(rawCategory.toString(), value, series, i));
    }
    return List.unmodifiable(result);
  }

  /// Keeps insertion order and separates series without silent aggregation.
  Map<String, List<FarabiSeriesPoint>> group(FarabiChart chart) {
    final grouped = LinkedHashMap<String, List<FarabiSeriesPoint>>();
    for (final point in points(chart)) {
      grouped.putIfAbsent(point.series ?? '', () => []).add(point);
    }
    return Map.unmodifiable({
      for (final entry in grouped.entries)
        entry.key: List<FarabiSeriesPoint>.unmodifiable(entry.value),
    });
  }
}

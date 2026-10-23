library farabi_tenun;

import 'package:farabi_dashboard/farabi_dashboard.dart';
import 'package:flutter/widgets.dart';
import 'package:tenun/tenun.dart';

/// Call once during application bootstrap before building Tenun charts.
void initializeFarabiTenun() {
  coreChartsBundle.register();
}

/// Registers real TenunChart widgets for Farabi's five core chart kinds.
void registerFarabiTenunRenderers(FarabiRendererRegistry registry) {
  Widget build(
    BuildContext context,
    FarabiChart chart,
    Map<String, List<FarabiSeriesPoint>> groups,
  ) {
    final config = farabiTenunConfig(chart, groups);
    return LayoutBuilder(builder: (context, bounds) {
      final width = bounds.maxWidth.isFinite && bounds.maxWidth > 0
          ? bounds.maxWidth
          : 400.0;
      final height = bounds.maxHeight.isFinite && bounds.maxHeight > 0
          ? bounds.maxHeight
          : 300.0;
      return TenunChart(jsonConfig: config, width: width, height: height);
    });
  }

  registerFarabiTenunAdapters(
    registry,
    bar: build,
    line: build,
    area: build,
    pie: build,
    scatter: build,
  );
}

/// Build the documented Tenun JSON chart contract.
///
/// Duplicate category+series points are rejected rather than silently
/// aggregated. NULL gaps are represented as null for cartesian series.
Map<String, dynamic> farabiTenunConfig(
  FarabiChart chart,
  Map<String, List<FarabiSeriesPoint>> groups,
) {
  const types = {
    'tenun.bar': 'bar',
    'tenun.line': 'line',
    'tenun.area': 'area',
    'tenun.pie': 'pie',
    'tenun.scatter': 'scatter',
  };
  final type = types[chart.renderer];
  if (type == null) throw ArgumentError.value(chart.renderer, 'renderer');
  final categories = <String>[];
  final seen = <String>{};
  for (final series in groups.values) {
    for (final point in series) {
      if (seen.add(point.category)) categories.add(point.category);
    }
  }
  final output = <Map<String, dynamic>>[];
  for (final entry in groups.entries) {
    final values = <String, double>{};
    for (final point in entry.value) {
      if (values.containsKey(point.category)) {
        throw FormatException('Duplicate category in series: ${entry.key}');
      }
      values[point.category] = point.value;
    }
    output.add({
      'name': entry.key.isEmpty ? chart.title : entry.key,
      'data': [for (final category in categories) values[category]],
    });
  }
  return {
    'type': type,
    'title': {'text': chart.title},
    'xAxis': {'data': categories},
    'series': output,
  };
}

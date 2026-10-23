import 'package:flutter/material.dart';
import 'dashboard.dart';
import 'tenun_data.dart';

/// Explicit Tenun boundary. Implementations can target tenun/tenun_pro
/// independently without leaking package-specific types into Farabi.
typedef FarabiSeriesWidgetBuilder = Widget Function(
  BuildContext context,
  FarabiChart chart,
  Map<String, List<FarabiSeriesPoint>> series,
);

/// Registers chart renderers only when a concrete widget factory is supplied.
/// There is intentionally no fake default chart implementation.
void registerFarabiTenunAdapters(
  FarabiRendererRegistry registry, {
  required FarabiSeriesWidgetBuilder bar,
  required FarabiSeriesWidgetBuilder line,
  required FarabiSeriesWidgetBuilder area,
  required FarabiSeriesWidgetBuilder pie,
  required FarabiSeriesWidgetBuilder scatter,
  FarabiTenunDataAdapter adapter = const FarabiTenunDataAdapter(),
}) {
  void bind(String id, FarabiSeriesWidgetBuilder builder) {
    registry.register(id, (context, chart) {
      try {
        final groups = adapter.group(chart);
        if (groups.isEmpty) return const Center(child: Text('No plottable data'));
        return builder(context, chart, groups);
      } on FormatException {
        return const Center(child: Text('Invalid chart binding'));
      }
    });
  }
  bind('tenun.bar', bar);
  bind('tenun.line', line);
  bind('tenun.area', area);
  bind('tenun.pie', pie);
  bind('tenun.scatter', scatter);
}

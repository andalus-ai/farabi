import 'package:flutter/material.dart';

typedef FarabiChartBuilder = Widget Function(BuildContext context, FarabiChart chart);

class FarabiChart {
  final String id, renderer, title, state;
  final Map<String, dynamic> placement, binding;
  final List<Map<String, dynamic>> rows;

  FarabiChart.fromJson(Map<String, dynamic> json)
      : id = json['id'] as String,
        renderer = json['renderer'] as String,
        title = json['title'] as String,
        state = json['state'] as String,
        placement = Map<String, dynamic>.from(json['placement'] as Map),
        binding = Map<String, dynamic>.from(json['binding'] as Map),
        rows = (json['rows'] as List)
            .map((row) => Map<String, dynamic>.from(row as Map))
            .toList(growable: false);
}

class FarabiDashboard {
  final String title, manifestId;
  final int schemaVersion, revision;
  final List<FarabiChart> charts;

  FarabiDashboard.fromJson(Map<String, dynamic> json)
      : title = json['title'] as String,
        manifestId = json['manifestId'] as String,
        schemaVersion = json['schemaVersion'] as int,
        revision = json['revision'] as int,
        charts = (json['charts'] as List)
            .map((item) => FarabiChart.fromJson(Map<String, dynamic>.from(item as Map)))
            .toList(growable: false) {
    if (schemaVersion != 1) {
      throw FormatException('Unsupported Farabi dashboard schema: $schemaVersion');
    }
    if (charts.map((c) => c.id).toSet().length != charts.length) {
      throw const FormatException('Duplicate chart IDs');
    }
  }
}

class FarabiRendererRegistry {
  final Map<String, FarabiChartBuilder> _builders = {};
  void register(String renderer, FarabiChartBuilder builder) {
    if (!{'tenun.bar', 'tenun.line', 'tenun.area', 'tenun.pie',
           'tenun.scatter', 'farabi.table', 'farabi.kpi'}.contains(renderer)) {
      throw ArgumentError.value(renderer, 'renderer', 'Unsupported renderer');
    }
    _builders[renderer] = builder;
  }
  Widget build(BuildContext context, FarabiChart chart) {
    if (chart.state == 'EMPTY') return const Center(child: Text('No data'));
    if (chart.state != 'READY') return const Center(child: Text('Chart unavailable'));
    final builder = _builders[chart.renderer];
    return builder == null
        ? Center(child: Text('Renderer unavailable: ${chart.renderer}'))
        : builder(context, chart);
  }
}

class FarabiDashboardView extends StatelessWidget {
  final FarabiDashboard dashboard;
  final FarabiRendererRegistry registry;
  const FarabiDashboardView({super.key, required this.dashboard, required this.registry});

  @override
  Widget build(BuildContext context) {
    final sorted = [...dashboard.charts]..sort((a, b) {
      final y = (a.placement['y'] as int).compareTo(b.placement['y'] as int);
      return y != 0 ? y : (a.placement['x'] as int).compareTo(b.placement['x'] as int);
    });
    return LayoutBuilder(builder: (context, constraints) {
      final available = constraints.maxWidth.isFinite ? constraints.maxWidth : 1200.0;
      final cell = available / 12;
      const rowHeight = 100.0;
      final bottom = sorted.fold<int>(0, (max, c) {
        final edge = (c.placement['y'] as int) + (c.placement['height'] as int);
        return edge > max ? edge : max;
      });
      return SizedBox(
        width: available,
        height: bottom * rowHeight,
        child: Stack(children: [
          for (final chart in sorted)
            Positioned(
              left: (chart.placement['x'] as int) * cell,
              top: (chart.placement['y'] as int) * rowHeight,
              width: (chart.placement['width'] as int) * cell,
              height: (chart.placement['height'] as int) * rowHeight,
              child: Card(
                child: Column(children: [
                  Padding(
                    padding: const EdgeInsets.all(8),
                    child: Text(chart.title, style: Theme.of(context).textTheme.titleMedium),
                  ),
                  Expanded(child: registry.build(context, chart)),
                ]),
              ),
            ),
        ]),
      );
    });
  }
}

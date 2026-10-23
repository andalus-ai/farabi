import 'package:flutter/material.dart';
import 'dashboard.dart';

/// Built-in, dependency-free renderers for non-Tenun chart kinds.
void registerFarabiNativeRenderers(FarabiRendererRegistry registry) {
  registry.register('farabi.table', (_, chart) => FarabiTable(chart: chart));
  registry.register('farabi.kpi', (_, chart) => FarabiKpi(chart: chart));
}

class FarabiTable extends StatelessWidget {
  final FarabiChart chart;
  const FarabiTable({super.key, required this.chart});

  @override
  Widget build(BuildContext context) {
    if (chart.rows.isEmpty) return const Center(child: Text('No data'));
    final fields = <String>{};
    for (final row in chart.rows) {
      fields.addAll(row.keys);
    }
    if (fields.isEmpty) return const Center(child: Text('No columns'));
    final columns = fields.toList(growable: false);
    return Scrollbar(
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: SingleChildScrollView(
          child: DataTable(
            columns: [for (final field in columns) DataColumn(label: Text(field))],
            rows: [
              for (final row in chart.rows)
                DataRow(cells: [
                  for (final field in columns)
                    DataCell(Text(row[field]?.toString() ?? '—')),
                ]),
            ],
          ),
        ),
      ),
    );
  }
}

class FarabiKpi extends StatelessWidget {
  final FarabiChart chart;
  const FarabiKpi({super.key, required this.chart});

  @override
  Widget build(BuildContext context) {
    final field = chart.binding['valueField'];
    if (field is! String || field.isEmpty || chart.rows.isEmpty) {
      return const Center(child: Text('No KPI value'));
    }
    final value = chart.rows.first[field];
    return Center(
      child: Semantics(
        label: '${chart.title}: ${value ?? 'No value'}',
        child: FittedBox(
          fit: BoxFit.scaleDown,
          child: Text(
            value?.toString() ?? '—',
            style: Theme.of(context).textTheme.headlineLarge,
          ),
        ),
      ),
    );
  }
}

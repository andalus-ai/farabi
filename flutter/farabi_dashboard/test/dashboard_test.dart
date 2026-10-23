import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:farabi_dashboard/farabi_dashboard.dart';

void main() {
  final payload = <String, dynamic>{
    'schemaVersion': 1,
    'manifestId': '00000000-0000-0000-0000-000000000001',
    'revision': 1,
    'title': 'Revenue',
    'charts': [
      {
        'id': 'a', 'renderer': 'tenun.bar', 'title': 'Sales',
        'state': 'READY',
        'placement': {'chartId': 'a', 'x': 0, 'y': 0, 'width': 12, 'height': 3},
        'binding': {'datasetId': 'orders', 'categoryField': 'region',
                    'valueField': 'revenue', 'seriesField': null},
        'rows': [{'region': 'APAC', 'revenue': 42}],
      },
    ],
  };

  test('parses dashboard delivery v1', () {
    final dashboard = FarabiDashboard.fromJson(payload);
    expect(dashboard.charts.single.rows.single['revenue'], 42);
  });

  testWidgets('renders registered chart', (tester) async {
    final registry = FarabiRendererRegistry()
      ..register('tenun.bar', (_, chart) => Text('Rows: ${chart.rows.length}'));
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: FarabiDashboardView(
        dashboard: FarabiDashboard.fromJson(payload), registry: registry)),
    ));
    expect(find.text('Rows: 1'), findsOneWidget);
  });
}

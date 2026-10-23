import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:farabi_dashboard/farabi_dashboard.dart';

FarabiChart makeChart(String renderer, List<Map<String, dynamic>> rows) {
  return FarabiChart.fromJson({
    'id': 'c1',
    'renderer': renderer,
    'title': 'Revenue',
    'state': rows.isEmpty ? 'EMPTY' : 'READY',
    'placement': {'chartId': 'c1', 'x': 0, 'y': 0, 'width': 12, 'height': 3},
    'binding': {
      'datasetId': 'sales',
      'categoryField': 'region',
      'valueField': 'amount',
      'seriesField': null,
    },
    'rows': rows,
  });
}

void main() {
  testWidgets('native KPI renders a bound value', (tester) async {
    final registry = FarabiRendererRegistry();
    registerFarabiNativeRenderers(registry);
    final chart = makeChart('farabi.kpi', [{'amount': 125}]);
    await tester.pumpWidget(MaterialApp(
      home: Builder(builder: (context) => Scaffold(body: registry.build(context, chart))),
    ));
    expect(find.text('125'), findsOneWidget);
  });

  testWidgets('native table renders nullable cells', (tester) async {
    final registry = FarabiRendererRegistry();
    registerFarabiNativeRenderers(registry);
    final chart = makeChart('farabi.table', [
      {'region': 'APAC', 'amount': null},
    ]);
    await tester.pumpWidget(MaterialApp(
      home: Builder(builder: (context) => Scaffold(body: registry.build(context, chart))),
    ));
    expect(find.text('APAC'), findsOneWidget);
    expect(find.text('—'), findsOneWidget);
  });

  testWidgets('empty charts do not call the renderer', (tester) async {
    final registry = FarabiRendererRegistry();
    registerFarabiNativeRenderers(registry);
    final chart = makeChart('farabi.kpi', []);
    await tester.pumpWidget(MaterialApp(
      home: Builder(builder: (context) => Scaffold(body: registry.build(context, chart))),
    ));
    expect(find.text('No data'), findsOneWidget);
  });
}

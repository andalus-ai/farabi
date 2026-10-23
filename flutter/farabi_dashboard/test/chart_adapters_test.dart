import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:farabi_dashboard/farabi_dashboard.dart';

FarabiChart makeChart({Object? value = 7}) => FarabiChart.fromJson({
  'id': 'a', 'renderer': 'tenun.bar', 'title': 'Revenue', 'state': 'READY',
  'placement': {'chartId': 'a', 'x': 0, 'y': 0, 'width': 12, 'height': 3},
  'binding': {'datasetId': 'orders', 'categoryField': 'month',
              'valueField': 'amount', 'seriesField': null},
  'rows': [{'month': 'Jan', 'amount': value}],
});

void main() {
  testWidgets('routes grouped points to a concrete chart factory', (tester) async {
    final registry = FarabiRendererRegistry();
    Widget builder(BuildContext _, FarabiChart __, Map<String, List<FarabiSeriesPoint>> data) =>
        Text('Points: ${data.values.first.length}');
    registerFarabiTenunAdapters(registry,
      bar: builder, line: builder, area: builder, pie: builder, scatter: builder);
    final chart = makeChart();
    await tester.pumpWidget(MaterialApp(
      home: Builder(builder: (context) => Scaffold(body: registry.build(context, chart)))));
    expect(find.text('Points: 1'), findsOneWidget);
  });

  testWidgets('non-numeric data does not invoke chart widget', (tester) async {
    final registry = FarabiRendererRegistry();
    Widget builder(BuildContext _, FarabiChart __, Map<String, List<FarabiSeriesPoint>> ___) =>
        const Text('SHOULD NOT RENDER');
    registerFarabiTenunAdapters(registry,
      bar: builder, line: builder, area: builder, pie: builder, scatter: builder);
    await tester.pumpWidget(MaterialApp(
      home: Builder(builder: (context) => Scaffold(body: registry.build(context, makeChart(value: 'invalid'))))));
    expect(find.text('No plottable data'), findsOneWidget);
    expect(find.text('SHOULD NOT RENDER'), findsNothing);
  });
}

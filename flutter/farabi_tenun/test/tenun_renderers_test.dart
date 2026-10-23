import 'package:farabi_dashboard/farabi_dashboard.dart';
import 'package:farabi_tenun/farabi_tenun.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tenun/tenun.dart';

FarabiChart chart(String kind) => FarabiChart.fromJson({
  'id': 'c1',
  'renderer': kind,
  'title': 'Revenue',
  'state': 'READY',
  'placement': {'chartId': 'c1', 'x': 0, 'y': 0, 'width': 12, 'height': 3},
  'binding': {'datasetId': 'orders', 'categoryField': 'month',
              'valueField': 'amount', 'seriesField': 'region'},
  'rows': [
    {'month': 'Jan', 'amount': 10, 'region': 'APAC'},
    {'month': 'Feb', 'amount': 20, 'region': 'APAC'},
  ],
});

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(initializeFarabiTenun);

  test('maps five Farabi chart kinds to Tenun JSON', () {
    for (final kind in ['bar', 'line', 'area', 'pie', 'scatter']) {
      final source = chart('tenun.$kind');
      final groups = const FarabiTenunDataAdapter().group(source);
      final json = farabiTenunConfig(source, groups);
      expect(json['type'], kind);
      expect((json['xAxis'] as Map)['data'], ['Jan', 'Feb']);
      expect(((json['series'] as List).first as Map)['data'], [10, 20]);
    }
  });

  testWidgets('renders a real TenunChart for bar', (tester) async {
    final registry = FarabiRendererRegistry();
    registerFarabiTenunRenderers(registry);
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: SizedBox(
        width: 400,
        height: 300,
        child: Builder(builder: (context) => registry.build(context, chart('tenun.bar'))),
      )),
    ));
    expect(find.byType(TenunChart), findsOneWidget);
  });
}

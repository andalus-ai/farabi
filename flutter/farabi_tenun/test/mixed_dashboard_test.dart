import 'package:farabi_dashboard/farabi_dashboard.dart';
import 'package:farabi_tenun/farabi_tenun.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tenun/tenun.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(initializeFarabiTenun);

  testWidgets('mixed dashboard builds Tenun, table and KPI widgets', (tester) async {
    Map<String, dynamic> chart(String id, String renderer, int x) => {
      'id': id, 'renderer': renderer, 'title': id,
      'state': 'READY',
      'placement': {'chartId': id, 'x': x, 'y': 0, 'width': 4, 'height': 3},
      'binding': {'datasetId': 'sales', 'categoryField': 'month', 'valueField': 'amount', 'seriesField': null},
      'rows': [{'month': 'Jan', 'amount': 12}],
    };
    final dashboard = FarabiDashboard.fromJson({
      'schemaVersion': 1,
      'manifestId': '00000000-0000-0000-0000-000000000001',
      'revision': 1,
      'title': 'Sales dashboard',
      'charts': [
        chart('bar', 'tenun.bar', 0),
        chart('table', 'farabi.table', 4),
        chart('kpi', 'farabi.kpi', 8),
      ],
    });
    final registry = FarabiRendererRegistry();
    registerFarabiNativeRenderers(registry);
    registerFarabiTenunRenderers(registry);
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: SingleChildScrollView(
        child: FarabiDashboardView(dashboard: dashboard, registry: registry),
      )),
    ));
    expect(find.byType(TenunChart), findsOneWidget);
    expect(find.byType(DataTable), findsOneWidget);
    expect(find.text('12'), findsWidgets);
  });
}

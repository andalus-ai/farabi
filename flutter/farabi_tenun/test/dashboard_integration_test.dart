import 'package:flutter_test/flutter_test.dart';
import 'package:farabi_dashboard/farabi_dashboard.dart';
import 'package:farabi_tenun/farabi_tenun.dart';

void main() {
  test('delivery data becomes Tenun chart configuration', () {
    final chart = FarabiChart.fromJson({
      'id': 'c1', 'renderer': 'tenun.bar', 'title': 'Sales',
      'state': 'READY',
      'placement': {'chartId': 'c1', 'x': 0, 'y': 0, 'width': 12, 'height': 3},
      'binding': {'datasetId': 'sales', 'categoryField': 'month', 'valueField': 'amount', 'seriesField': null},
      'rows': [{'month': 'Jan', 'amount': 10}, {'month': 'Feb', 'amount': 20}],
    });
    final groups = const FarabiTenunDataAdapter().group(chart);
    final config = farabiTenunConfig(chart, groups);
    expect(config['type'], 'bar');
    expect((config['xAxis'] as Map)['data'], ['Jan', 'Feb']);
    expect(((config['series'] as List).first as Map)['data'], [10, 20]);
  });
}

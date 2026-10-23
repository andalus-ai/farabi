import 'package:flutter_test/flutter_test.dart';
import 'package:farabi_dashboard/farabi_dashboard.dart';

FarabiChart chart(List<Map<String, dynamic>> rows, {String renderer = 'tenun.line'}) =>
    FarabiChart.fromJson({
      'id': 'series', 'renderer': renderer, 'title': 'Sales', 'state': 'READY',
      'placement': {'chartId': 'series', 'x': 0, 'y': 0, 'width': 12, 'height': 3},
      'binding': {'datasetId': 'sales', 'categoryField': 'month',
                  'valueField': 'revenue', 'seriesField': 'region'},
      'rows': rows,
    });

void main() {
  const adapter = FarabiTenunDataAdapter();
  test('maps finite numeric values and preserves source order', () {
    final points = adapter.points(chart([
      {'month': 'Jan', 'revenue': 12, 'region': 'APAC'},
      {'month': 'Feb', 'revenue': '3.5', 'region': 'APAC'},
      {'month': 'Mar', 'revenue': null, 'region': 'EMEA'},
      {'month': 'Apr', 'revenue': 'NaN', 'region': 'EMEA'},
      {'month': 'May', 'revenue': 7, 'region': 'EMEA'},
    ]));
    expect(points.map((p) => p.category), ['Jan', 'Feb', 'May']);
    expect(points.map((p) => p.value), [12, 3.5, 7]);
    expect(points.map((p) => p.sourceIndex), [0, 1, 4]);
    expect(adapter.group(chart([
      {'month': 'Jan', 'revenue': 1, 'region': 'APAC'},
      {'month': 'Jan', 'revenue': 2, 'region': 'EMEA'},
    ])).keys, ['APAC', 'EMEA']);
  });

  test('rejects non-Tenun chart', () {
    expect(() => adapter.points(chart([], renderer: 'farabi.table')), throwsArgumentError);
  });
}

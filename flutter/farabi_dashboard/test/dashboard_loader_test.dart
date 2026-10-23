import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:farabi_dashboard/farabi_dashboard.dart';

void main() {
  const id = '00000000-0000-0000-0000-000000000001';
  test('fetches a versioned dashboard document', () async {
    final client = MockClient((request) async {
      expect(request.url.path, '/api/dashboards/$id/delivery');
      return http.Response(jsonEncode({
        'schemaVersion': 1,
        'manifestId': id,
        'revision': 1,
        'title': 'Sales',
        'charts': <Object>[],
      }), 200, headers: {'content-type': 'application/json'});
    });
    final api = FarabiDashboardClient(baseUri: Uri.parse('https://example.test/api/'), client: client);
    expect((await api.fetch(id)).title, 'Sales');
    client.close();
  });

  test('does not expose forbidden response body', () async {
    final client = MockClient((_) async => http.Response('private detail', 403));
    final api = FarabiDashboardClient(baseUri: Uri.parse('https://example.test/'), client: client);
    await expectLater(api.fetch(id), throwsA(isA<FarabiDashboardException>()));
    client.close();
  });

  test('rejects insecure remote endpoint', () {
    expect(
      () => FarabiDashboardClient(
        baseUri: Uri.parse('http://example.test/'),
        client: MockClient((_) async => http.Response('{}', 200)),
      ),
      throwsArgumentError,
    );
  });
}

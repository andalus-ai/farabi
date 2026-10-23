import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'dashboard.dart';

/// Caller provides a trusted base URI and an authenticated HTTP client.
/// Identity and workspace are established by the server, never by this package.
class FarabiDashboardClient {
  final Uri baseUri;
  final http.Client client;
  FarabiDashboardClient({required this.baseUri, required this.client}) {
    if (baseUri.scheme != 'https' && baseUri.host != 'localhost' && baseUri.host != '127.0.0.1') {
      throw ArgumentError('Dashboard endpoint must use HTTPS');
    }
  }

  Future<FarabiDashboard> fetch(String manifestId) async {
    if (!RegExp(r'^[0-9a-fA-F-]{36}$').hasMatch(manifestId)) {
      throw const FormatException('Invalid manifest ID');
    }
    final basePath = baseUri.path.endsWith('/') ? baseUri.path : '${baseUri.path}/';
    final uri = baseUri.replace(
      path: '${basePath}dashboards/${Uri.encodeComponent(manifestId)}/delivery',
      query: null,
      fragment: null,
    );
    final response = await client.get(uri, headers: {'Accept': 'application/json'});
    if (response.statusCode == 401 || response.statusCode == 403) {
      throw const FarabiDashboardException('Access denied');
    }
    if (response.statusCode == 404) {
      throw const FarabiDashboardException('Dashboard not found');
    }
    if (response.statusCode != 200) {
      throw FarabiDashboardException('Dashboard request failed (${response.statusCode})');
    }
    final value = jsonDecode(response.body);
    if (value is! Map<String, dynamic>) {
      throw const FormatException('Expected dashboard JSON object');
    }
    return FarabiDashboard.fromJson(value);
  }
}

class FarabiDashboardException implements Exception {
  final String message;
  const FarabiDashboardException(this.message);
  @override
  String toString() => message;
}

/// A request-scoped widget with explicit loading, failure, and retry states.
class FarabiRemoteDashboardView extends StatefulWidget {
  final FarabiDashboardClient client;
  final String manifestId;
  final FarabiRendererRegistry registry;
  const FarabiRemoteDashboardView({
    super.key,
    required this.client,
    required this.manifestId,
    required this.registry,
  });
  @override
  State<FarabiRemoteDashboardView> createState() => _FarabiRemoteDashboardViewState();
}

class _FarabiRemoteDashboardViewState extends State<FarabiRemoteDashboardView> {
  late Future<FarabiDashboard> _request;
  @override
  void initState() {
    super.initState();
    _reload();
  }
  @override
  void didUpdateWidget(covariant FarabiRemoteDashboardView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.manifestId != widget.manifestId || oldWidget.client != widget.client) {
      _reload();
    }
  }
  void _reload() {
    _request = widget.client.fetch(widget.manifestId);
  }
  @override
  Widget build(BuildContext context) => FutureBuilder<FarabiDashboard>(
    future: _request,
    builder: (context, snapshot) {
      if (snapshot.connectionState != ConnectionState.done) {
        return const Center(child: CircularProgressIndicator());
      }
      if (snapshot.hasError) {
        return Center(child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text('Unable to load dashboard'),
            TextButton(onPressed: () => setState(_reload), child: const Text('Retry')),
          ],
        ));
      }
      return FarabiDashboardView(dashboard: snapshot.requireData, registry: widget.registry);
    },
  );
}

import 'package:batik/batik.dart';
import 'package:flutter/widgets.dart';
import 'dashboard.dart';

/// Batik CustomNode bridge for Farabi DashboardDelivery.Document JSON.
void registerFarabiBatikDashboard(FarabiRendererRegistry renderers, {
  UIComponentRegistry? registry,
}) {
  (registry ?? UIComponentRegistry.instance).registerCustom(
    'farabi.dashboard',
    (BuildContext context, CustomNode node, NodeRenderer renderer) {
      final raw = node.props['document'];
      if (raw is! Map) return const Text('Invalid dashboard document');
      try {
        final dashboard = FarabiDashboard.fromJson(Map<String, dynamic>.from(raw));
        return FarabiDashboardView(dashboard: dashboard, registry: renderers);
      } on FormatException {
        return const Text('Unsupported dashboard document');
      } on TypeError {
        return const Text('Malformed dashboard document');
      }
    },
  );
}

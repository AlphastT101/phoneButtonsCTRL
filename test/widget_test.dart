import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'package:phone_volume/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('volume_buttons/overlay');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'isOverlayEnabled') return false;
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('home screen shows the floating control state', (tester) async {
    await tester.pumpWidget(const VolumeButtonsApp());
    await tester.pumpAndSettle();

    expect(find.text('Volume Buttons'), findsOneWidget);
    expect(find.text('Floating control'), findsOneWidget);
    expect(find.text('Turned off'), findsOneWidget);
    expect(find.byType(Switch), findsOneWidget);
  });
}

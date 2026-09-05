import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rigstudio/main.dart';

void main() {
  testWidgets('app boots to the library screen', (tester) async {
    await tester.pumpWidget(const ProviderScope(child: RigStudioApp()));
    await tester.pump();
    expect(find.text('RigStudio'), findsOneWidget);
    expect(find.text('New character'), findsOneWidget);
  });
}

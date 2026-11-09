import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:uwu_mouse/platform/android/method_channel_mapper_gateway.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dev.uwumouse/control.v1');
  const gateway = MethodChannelMapperGateway();
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );

  test(
    'export identifies the chosen profile and waits for document completion',
    () async {
      MethodCall? received;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            received = call;
            return true;
          });
      expect(await gateway.exportProfileFile('profile-a'), isTrue);
      expect(received?.method, 'exportProfileFile');
      expect(received?.arguments, {'id': 'profile-a'});
    },
  );

  test(
    'import opens the native document picker and preserves cancellation',
    () async {
      MethodCall? received;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            received = call;
            return false;
          });
      expect(await gateway.importProfileFile(), isFalse);
      expect(received?.method, 'importProfileFile');
      expect(received?.arguments, isNull);
    },
  );

  test(
    'invalid document errors reach the existing interface error handler',
    () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (_) async {
            throw PlatformException(
              code: 'PROFILE_FILE',
              message: 'Archivo no válido',
            );
          });
      expect(gateway.importProfileFile(), throwsA(isA<PlatformException>()));
    },
  );
}

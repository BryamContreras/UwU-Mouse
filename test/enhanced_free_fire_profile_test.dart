import 'package:flutter_test/flutter_test.dart';
import 'package:uwu_mouse/application/models/game_models.dart';

import 'support/profile_fixtures.dart';

void main() {
  test('older profiles keep enhanced camera optimization disabled', () {
    expect(
      LocalProfile.fromWire(profileWire()).enhancedFreeFireOptimization,
      isFalse,
    );
  });

  test('the optional camera flag accepts only explicit boolean values', () {
    for (final enabled in [false, true]) {
      final wire = profileWire();
      (wire['camera']! as Map)['enhancedFreeFireOptimization'] = enabled;
      expect(LocalProfile.fromWire(wire).enhancedFreeFireOptimization, enabled);
    }
    final malformed = profileWire();
    (malformed['camera']! as Map)['enhancedFreeFireOptimization'] = 'true';
    expect(() => LocalProfile.fromWire(malformed), throwsFormatException);
  });
}

import 'package:flutter_test/flutter_test.dart';
import 'package:uwu_mouse/application/models/game_models.dart';

import 'support/profile_fixtures.dart';

void main() {
  test('legacy camera setting does not affect profile sensitivity', () {
    for (final priorValue in [false, true]) {
      final wire = displayProfileWire('external', 'camera-1');
      (wire['camera']! as Map)['enhancedFreeFireOptimization'] = priorValue;
      final profile = LocalProfile.fromWire(wire);

      expect(profile.sensitivityX, 1.0);
      expect(profile.sensitivityY, 1.25);
    }
  });
}

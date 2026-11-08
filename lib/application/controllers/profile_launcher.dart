import 'dart:async';

import '../policies/session_readiness.dart';
import '../ports/mapper_gateway.dart';

enum ProfileLaunchOutcome { launched, setupRequired, cancelled }

/// Coordinates an explicit launch through a replaceable control-plane port.
/// The host owns lifecycle validity and presentation of errors/setup screens.
class ProfileLauncher {
  const ProfileLauncher({
    required this.gateway,
    required this.readStatus,
    required this.isCurrent,
    required this.reportFailure,
    required this.describeError,
  });

  final MapperGateway gateway;
  final Future<NativeStatus> Function() readStatus;
  final bool Function() isCurrent;
  final void Function(String) reportFailure;
  final String Function(Object) describeError;

  Future<ProfileLaunchOutcome> launch(LocalProfile profile) async {
    var current = await readStatus();
    if (SessionReadiness.needsOverlayPermission(current)) {
      return ProfileLaunchOutcome.setupRequired;
    }
    // A cold launch may still be reconnecting the existing privileged process.
    for (
      var attempt = 0;
      attempt < 2 && !current.backend && SessionReadiness.mayConfigure(current);
      attempt++
    ) {
      await Future<void>.delayed(const Duration(milliseconds: 250));
      if (!isCurrent()) return ProfileLaunchOutcome.cancelled;
      current = await readStatus();
    }
    if (!isCurrent() || !SessionReadiness.mayConfigure(current)) {
      return ProfileLaunchOutcome.cancelled;
    }
    // Capture cannot be ready until the privileged backend is connected.
    if (!current.backend) {
      final reactivated = await _reactivate();
      if (!isCurrent()) return ProfileLaunchOutcome.cancelled;
      if (reactivated != null) current = reactivated;
    }
    if (!current.captureReady ||
        !current.backend ||
        SessionReadiness.needsOverlayPermission(current)) {
      return ProfileLaunchOutcome.setupRequired;
    }
    if (profile.calibrated) {
      await gateway.startProfile(profile.id);
    } else {
      await gateway.openEditor(profile.id);
    }
    return ProfileLaunchOutcome.launched;
  }

  /// Discovery belongs to this explicit tap. Backgrounding invalidates the tap,
  /// and a pairing operation begun elsewhere keeps its own authorization.
  Future<NativeStatus?> _reactivate() async {
    var ownsDiscovery = false;
    var preserveOtherOperation = false;
    var expired = false;
    Timer? timeout;
    try {
      final previous = await gateway.activationStatus();
      if (!isCurrent() || !previous.paired || previous.busy) return null;
      timeout = Timer(const Duration(seconds: 20), () => expired = true);
      await gateway.activationStartDiscovery();
      ownsDiscovery = true;
      if (!isCurrent()) return null;
      final discovered = await gateway.activationStatus();
      if (discovered.busy) {
        preserveOtherOperation = true;
        return null;
      }
      await gateway.activationConnect();
      while (isCurrent() && !expired) {
        final activation = await gateway.activationStatus();
        if (!isCurrent() || expired) return null;
        if (activation.phase == 'pairing') {
          preserveOtherOperation = true;
          return null;
        }
        if (activation.phase == 'error' || activation.phase == 'idle') {
          if (activation.message.isNotEmpty) reportFailure(activation.message);
          return null;
        }
        if (activation.ready) {
          final verified = await readStatus();
          if (isCurrent() &&
              verified.backend &&
              verified.captureReady &&
              SessionReadiness.mayConfigure(verified)) {
            return verified;
          }
          return null;
        }
        await Future<void>.delayed(const Duration(milliseconds: 250));
      }
      if (expired && isCurrent()) {
        reportFailure(
          'No se pudo reactivar a tiempo. Revisa Depuración inalámbrica.',
        );
      }
    } catch (error) {
      if (isCurrent()) {
        // The UI supplies a presenter for platform errors; this controller stays
        // independent of Flutter services and can run with a different adapter.
        reportFailure(describeError(error));
      }
    } finally {
      timeout?.cancel();
      if (ownsDiscovery && !preserveOtherOperation) {
        try {
          final latest = await gateway.activationStatus();
          if (latest.phase != 'pairing') await gateway.activationCancel();
        } catch (_) {
          // Preserve the original failure and never start after a failed check.
        }
      }
    }
    return null;
  }
}

import '../models/mapper_status.dart';

/// Shared launch/edit readiness. Capture implementations can change behind this policy.
abstract final class SessionReadiness {
  static bool mayConfigure(NativeStatus? status) =>
      status != null &&
      !status.blocked &&
      !status.recovering &&
      !status.editing &&
      status.contacts == 0;

  static bool needsOverlayPermission(NativeStatus status) =>
      !status.overlayPermission;
}

import '../models/activation_status.dart';
import '../models/game_models.dart';
import '../models/mapper_command.dart';
import '../models/mapper_status.dart';

export '../models/activation_status.dart';
export '../models/game_models.dart';
export '../models/mapper_command.dart';
export '../models/mapper_status.dart';

/// Control-plane port. UI depends on this contract, never on a transport or capture engine.
/// Input samples and touch injection remain in the native runtime.
abstract interface class MapperGateway {
  Future<String> cursorStyle();
  Future<void> setCursorStyle(String style);
  Future<NativeStatus> status();
  Future<LocalActivationStatus> activationStatus();
  Future<void> activationStartDiscovery();
  Future<void> activationPair({
    required String host,
    required int port,
    required String code,
  });
  Future<void> activationConnect({String? host, int? port});
  Future<void> activationCancel();
  Future<void> wirelessDebuggingSettings();
  Future<List<LaunchableApp>> listApps();
  Future<List<LocalProfile>> listProfiles();
  Future<LocalProfile?> selectedProfile();
  Future<LocalProfile> createProfile(String packageName, String name);
  Future<LocalProfile> selectProfile(String id);
  Future<LocalProfile> renameProfile(String id, String name);
  Future<void> deleteProfile(String id);
  Future<bool> exportProfileFile(String id);
  Future<bool> importProfileFile();
  Future<void> openEditor(String id);
  Future<void> startProfile(String id);

  /// Requests closure of an existing session in its original game; never starts one.
  Future<bool> recoverPendingSession();
  Future<void> execute(NativeCommand command);
}

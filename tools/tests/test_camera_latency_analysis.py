import copy
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from analyze_camera_latency import ACK, SUBMIT, analyze, analyze_directory, compare, main, pair_injection, pair_physical


def row(sequence, stage, observed_ms, source_ms=1000, action=-1, event_time=-1):
    return {"sequence": sequence, "stage": stage, "sourceTimeNanos": int(source_ms * 1e6),
            "observedTimeNanos": int(observed_ms * 1e6), "validTiming": observed_ms >= source_ms,
            "action": action, "eventTimeMs": event_time}


def endpoint(**overrides):
    result = {"active": True, "backend": True, "mouseCaptured": True, "captureReady": True,
              "editorVisible": False, "releaseUnverified": False, "pointerMode": "camera",
              "metricsSessionId": 41, "backendSessionId": 41, "activePackage": "game.example",
              "activeProfileId": "fixture-profile", "cameraRequestedRateHz": 250,
              "targetDisplayId": 2, "state": "Juego activo", "recoveryState": "ACTIVE",
              "relativeSamples": 100, "mouse": 100, "keys": 1, "injected": 100, "uptimeMs": 1000}
    result.update(overrides)
    return result


def fixture(rows, overwritten=0):
    before = endpoint()
    after = endpoint(relativeSamples=300, mouse=305, injected=350, uptimeMs=6000,
                     backendFocusMs=[1.0, 2.0, 3.0], backendPipeMs=[2.0, 5.0, 9.0])
    start = {"runId": "42-1000000000-1", "pid": 42, "startedTimeNanos": 1_000_000_000,
             "deadlineTimeNanos": 6_000_000_000, "enabled": True}
    stop = dict(start, enabled=False, stoppedTimeNanos=6_000_000_000, durationMs=5000,
                capacity=4096, totalRecorded=len(rows) + overwritten, storedCount=len(rows),
                overwrittenCount=overwritten, stopReason="window_expired")
    return before, after, start, stop, rows


def minimal_timing_rows():
    return [row(1, "native_capture_binder_receipt", 1001),
            row(2, "mapper_mouse_worker_latest_sample", 1002),
            row(3, SUBMIT, 1003, source_ms=1002, action=2, event_time=1002),
            row(4, ACK, 1004, source_ms=1002, action=2, event_time=1002)]


class CameraLatencyAnalysisTest(unittest.TestCase):
    def test_repeated_millisecond_and_action_pair_by_sequence_not_timestamp(self):
        rows = [row(1, SUBMIT, 1001, action=2, event_time=1000),
                row(2, "native_capture_binder_receipt", 1002, source_ms=1001),
                row(3, ACK, 1004, action=2, event_time=1000),
                row(4, SUBMIT, 1005, action=2, event_time=1000),
                row(5, ACK, 1015, action=2, event_time=1000)]
        result = pair_injection(rows)
        self.assertEqual(2, result["submitToAckMs"]["count"])
        self.assertEqual(3, result["submitToAckMs"]["minMs"])
        self.assertEqual(10, result["submitToAckMs"]["maxMs"])
        self.assertEqual(2, result["byMaskedAction"]["2"]["count"])

    def test_never_pairs_across_intervening_submit_or_identity_mismatch(self):
        rows = [row(1, SUBMIT, 1001, action=2, event_time=1000),
                row(2, SUBMIT, 1002, action=5, event_time=1000),
                row(3, ACK, 1003, action=2, event_time=1000),
                row(4, ACK, 1004, action=5, event_time=1000)]
        result = pair_injection(rows)
        self.assertEqual(0, result["submitToAckMs"]["count"])
        self.assertEqual({"submitWithoutAck": 1, "mismatchedPairs": 1, "ackWithoutSubmit": 1}, result["pairing"])

    def test_overwritten_prefix_ack_and_unfinished_suffix_are_reported(self):
        rows = [row(50, ACK, 1001, action=2, event_time=1000),
                row(51, SUBMIT, 1002, action=2, event_time=1000)]
        result = analyze(*fixture(rows, overwritten=49))
        self.assertTrue(result["probeExportValid"])
        self.assertFalse(result["fullRequestedProbeWindowRetained"])
        self.assertEqual(49, result["ring"]["overwrittenCount"])
        self.assertEqual({"ackWithoutSubmit": 1, "submitWithoutAck": 1}, result["injectionPairing"]["pairing"])

    def test_unique_physical_pair_uses_observed_difference(self):
        rows = [row(1, "native_capture_binder_receipt", 1002),
                row(2, "native_capture_binder_receipt", 1004, source_ms=1001),
                row(3, "mapper_mouse_worker_latest_sample", 1011, source_ms=1001)]
        result = analyze(*fixture(rows))
        self.assertEqual(7, result["physicalPairing"]["binderReceiptToWorkerMs"]["maxMs"])
        self.assertEqual(10, result["physicalSourceStageMs"]["mapper_mouse_worker_latest_sample"]["maxMs"])
        self.assertEqual(1, result["physicalPairing"]["pairing"]["receiptWithoutMatchedWorker"])

    def test_duplicate_source_times_are_ambiguous_and_not_guessed(self):
        rows = [row(1, "native_capture_binder_receipt", 1001),
                row(2, "native_capture_binder_receipt", 1002),
                row(3, "mapper_mouse_worker_latest_sample", 1003)]
        result = pair_physical(rows)
        self.assertEqual(0, result["binderReceiptToWorkerMs"]["count"])
        self.assertEqual(1, result["pairing"]["ambiguousWorkerRecords"])

    def test_worker_before_receipt_or_regressing_clock_is_not_a_pair(self):
        rows = [row(1, "mapper_mouse_worker_latest_sample", 1001),
                row(2, "native_capture_binder_receipt", 1002)]
        self.assertEqual(1, pair_physical(rows)["pairing"]["invalidPairs"])

    def test_invalid_physical_timing_is_excluded_separately(self):
        rows = [row(1, "mapper_mouse_worker_latest_sample", 1001, source_ms=1002)]
        result = analyze(*fixture(rows))
        stage = result["physicalSourceStageMs"]["mapper_mouse_worker_latest_sample"]
        self.assertEqual(0, stage["count"])
        self.assertEqual(1, stage["invalidTimingCount"])

    def test_synthetic_submit_stats_are_never_physical_latency(self):
        rows = [row(1, SUBMIT, 1001, action=261, event_time=1000),
                row(2, ACK, 1004, action=261, event_time=1000)]
        result = analyze(*fixture(rows))
        self.assertEqual({}, result["physicalSourceStageMs"])
        self.assertEqual(1, result["injectionPairing"]["byMaskedAction"]["5"]["count"])
        self.assertFalse(result["physicalInputConfirmed"])
        self.assertEqual([1, 2, 3], result["rollingDiagnosticsAfter"]["backendFocusMs"])
        self.assertEqual([2, 5, 9], result["rollingDiagnosticsAfter"]["backendPipeMs"])

    def test_incomplete_or_noncontiguous_export_invalidates_pair_results(self):
        for rows, stored in (([row(1, "stage", 1001)], 2),
                             ([row(1, "stage", 1001), row(3, "stage", 1002)], 2)):
            values = list(fixture(rows))
            values[3]["storedCount"] = stored
            values[3]["totalRecorded"] = stored
            result = analyze(*values)
            self.assertFalse(result["probeExportValid"])
            self.assertIsNone(result["injectionPairing"])

    def test_changed_run_or_outside_time_window_invalidates_export(self):
        values = list(fixture([row(1, "stage", 1001)]))
        values[3]["runId"] = "other-run"
        self.assertFalse(analyze(*values)["probeExportValid"])
        values = list(fixture([row(1, "stage", 6001)]))
        self.assertFalse(analyze(*values)["probeExportValid"])

    def test_early_stop_is_valid_but_not_the_full_requested_window(self):
        values = list(fixture([row(1, "stage", 1001)]))
        values[3]["stoppedTimeNanos"] = 2_000_000_000
        values[3]["stopReason"] = "requested"
        result = analyze(*values)
        self.assertTrue(result["probeExportValid"])
        self.assertTrue(result["recordingEndedBeforeDeadline"])
        self.assertFalse(result["fullRequestedProbeWindowRetained"])

    def test_session_mode_reset_or_no_workload_is_rejected(self):
        for overrides in ({"metricsSessionId": 42}, {"pointerMode": "cursor"},
                          {"injected": 99}, {"relativeSamples": 100}, {"testRunning": True}):
            values = list(fixture([]))
            values[1].update(overrides)
            result = analyze(*values)
            self.assertFalse(result["endpoints"]["stableFreshEndpoints"])
            self.assertFalse(result["comparableInternalTiming"])

    def test_comparison_checks_workload_and_profile_independently(self):
        a = analyze(*fixture([]), manifest={"workload": "camera-only", "physicalInputConfirmed": True})
        b = copy.deepcopy(a)
        b["manifest"]["workload"] = "camera-clicks"
        b["target"]["cameraRequestedRateHz"] = 500
        checks = compare([a, b])
        self.assertFalse(checks["sameDeclaredWorkload"])
        self.assertFalse(checks["sameTargetProfileRate"])
        self.assertTrue(checks["allPhysicalInputConfirmed"])

    def test_missing_comparison_metadata_is_unknown_not_confirmed_equal(self):
        a = analyze(*fixture([]))
        a["target"]["activeProfileId"] = None
        checks = compare([a, copy.deepcopy(a)])
        self.assertIsNone(checks["sameDeclaredWorkload"])
        self.assertIsNone(checks["sameTargetProfileRate"])
        self.assertIsNone(checks["sameDeclaredDeviceAndApplication"])

    def test_mislabeled_camera_only_reports_contamination_without_discarding_timing(self):
        values = list(fixture(minimal_timing_rows()))
        values[0].update(fireHoldStarts=140, fireHoldEnds=139)
        values[1].update(fireHoldStarts=190, fireHoldEnds=189, keys=236, uptimeMs=13_709)
        result = analyze(*values, manifest={"workload": "camera-only", "requestedClickRateHz": 0})
        workload = result["workloadValidation"]
        self.assertTrue(result["comparableInternalTiming"])
        self.assertFalse(workload["matchesDeclaredWorkload"])
        self.assertEqual(50, result["endpoints"]["counterDeltas"]["fireHoldStarts"])
        self.assertEqual(50, workload["m1ReleaseDelta"])
        self.assertEqual(235, workload["keyboardEventDelta"])
        self.assertAlmostEqual(50 / 12.709, workload["m1PressesPerSecond"])

    def test_controlled_camera_only_and_enabled_but_inactive_precision(self):
        values = list(fixture([]))
        for endpoint_value in values[:2]:
            endpoint_value.update(fireHoldStarts=100, fireHoldEnds=100,
                                  precisionAim={"enabled": True, "fineMotionGain": .6, "firing": False,
                                                "activeSamples": 200, "attenuatedSamples": 50, "flickSamples": 100})
        result = analyze(*values, manifest={"workload": "camera-only"})
        self.assertTrue(result["workloadValidation"]["matchesDeclaredWorkload"])
        precision = result["workloadValidation"]["precisionAim"]
        self.assertTrue(precision["sameConfiguration"])
        self.assertEqual(0, precision["counterDeltas"]["activeSamples"])
        self.assertIsNone(precision["attenuatedFractionOfActiveSamples"])
        self.assertTrue(compare([result, copy.deepcopy(result)])["samePrecisionAimConfiguration"])
        changed = copy.deepcopy(result)
        changed["workloadValidation"]["precisionAim"]["configurationAfter"]["enabled"] = False
        self.assertFalse(compare([result, changed])["samePrecisionAimConfiguration"])

    def test_held_m1_endpoint_contaminates_camera_only_even_without_new_presses(self):
        values = list(fixture([]))
        for endpoint_value in values[:2]:
            endpoint_value.update(fireHoldStarts=100, fireHoldEnds=99, precisionAim={"firing": True})
        workload = analyze(*values, manifest={"workload": "camera-only"})["workloadValidation"]
        self.assertFalse(workload["matchesDeclaredWorkload"])
        self.assertEqual(0, workload["m1PressDelta"])

    def test_pointer_edges_from_keys_do_not_prove_m1_click_workload(self):
        rows = [row(1, SUBMIT, 1001, action=261, event_time=1000),
                row(2, ACK, 1003, action=261, event_time=1000)]
        values = list(fixture(rows))
        values[0].update(fireHoldStarts=1, fireHoldEnds=1)
        values[1].update(fireHoldStarts=1, fireHoldEnds=1, keys=25)
        result = analyze(*values, manifest={"workload": "camera-clicks", "requestedClickRateHz": 10})
        workload = result["workloadValidation"]
        self.assertEqual(1, result["injectionPairing"]["byMaskedAction"]["5"]["count"])
        self.assertFalse(workload["matchesDeclaredWorkload"])
        self.assertEqual(0, workload["m1PressesPerSecond"])
        self.assertEqual(50, workload["requestedPressQuantityOverEndpointInterval"])

    def test_clicks_with_keyboard_and_precision_counters_are_reported_separately(self):
        values = list(fixture([]))
        values[0].update(fireHoldStarts=10, fireHoldEnds=10,
                         precisionAim={"activeSamples": 100, "attenuatedSamples": 50, "flickSamples": 30})
        values[1].update(fireHoldStarts=35, fireHoldEnds=35, keys=10,
                         precisionAim={"activeSamples": 200, "attenuatedSamples": 100, "flickSamples": 70})
        workload = analyze(*values, manifest={"workload": "camera-clicks"})["workloadValidation"]
        self.assertTrue(workload["matchesDeclaredWorkload"])
        self.assertTrue(workload["additionalKeyboardActivity"])
        self.assertEqual(5, workload["m1PressesPerSecond"])
        self.assertEqual(.5, workload["precisionAim"]["attenuatedFractionOfActiveSamples"])
        self.assertEqual(.4, workload["precisionAim"]["flickFractionOfActiveSamples"])

    def test_probe_tail_interval_is_explicitly_distinct_from_endpoint_click_rate(self):
        values = list(fixture([row(50, "native_capture_binder_receipt", 1002),
                               row(51, "mapper_mouse_worker_latest_sample", 1003)], overwritten=49))
        values[0].update(fireHoldStarts=0, fireHoldEnds=0)
        values[1].update(fireHoldStarts=50, fireHoldEnds=50, uptimeMs=13_500)
        result = analyze(*values, manifest={"workload": "camera-clicks"})
        self.assertEqual(12_500, result["workloadValidation"]["endpointIntervalMs"])
        self.assertEqual(4, result["workloadValidation"]["m1PressesPerSecond"])
        self.assertEqual(5000, result["probeRecordingIntervalMs"])
        self.assertEqual(1, result["retainedObservedSpanMs"])
        self.assertFalse(result["fullRequestedProbeWindowRetained"])

    def test_missing_or_reset_m1_counter_leaves_workload_unverified(self):
        values = list(fixture([]))
        self.assertIsNone(analyze(*values, manifest={"workload": "camera-only"})["workloadValidation"]["matchesDeclaredWorkload"])
        values[0].update(fireHoldStarts=10, fireHoldEnds=10)
        values[1].update(fireHoldStarts=1, fireHoldEnds=1)
        workload = analyze(*values, manifest={"workload": "camera-clicks"})["workloadValidation"]
        self.assertIsNone(workload["matchesDeclaredWorkload"])
        self.assertIsNone(workload["m1PressesPerSecond"])

    def test_directory_export_handles_utf8_bom_and_cli_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            names = ("controller-before.json", "controller-after.json", "probe-start.json", "probe-stop.json", "probe-records.json")
            for name, value in zip(names, fixture([])):
                (directory / name).write_text(json.dumps(value), encoding="utf-8-sig")
            self.assertTrue(analyze_directory(directory)["probeExportValid"])
            output = directory / "report.json"
            self.assertEqual(2, main([str(directory), "--output", str(output)]))
            self.assertEqual(1, len(json.loads(output.read_text())["trials"]))
            self.assertFalse(json.loads(output.read_text())["trials"][0]["timingAvailable"])

    def test_comparison_requires_both_physical_and_synthetic_timing_pairs(self):
        complete_rows = minimal_timing_rows()
        for rows in ([], complete_rows[:2], complete_rows[2:]):
            result = analyze(*fixture(rows))
            self.assertTrue(result["probeExportValid"])
            self.assertTrue(result["endpoints"]["stableFreshEndpoints"])
            self.assertFalse(result["timingAvailable"])
            self.assertFalse(result["comparableInternalTiming"])
        result = analyze(*fixture(complete_rows))
        self.assertTrue(result["timingAvailable"])
        self.assertTrue(result["comparableInternalTiming"])
        self.assertEqual(1, result["timingCoverage"]["physicalBinderWorkerPairs"])
        self.assertEqual(1, result["timingCoverage"]["syntheticSubmitAckPairs"])

    @unittest.skipUnless(shutil.which("powershell") or shutil.which("pwsh"), "PowerShell not installed")
    def test_capture_wrapper_rejects_existing_artifacts_before_overwriting_manifest(self):
        shell = shutil.which("powershell") or shutil.which("pwsh")
        script = Path(__file__).resolve().parents[2] / "scripts" / "Measure-CameraLatencyWindow.ps1"
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest = directory / "trial-manifest.json"
            manifest.write_text('{"label":"prior-trial"}', encoding="utf-8")
            result = subprocess.run([shell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script),
                                     "-Serial", "unused", "-Workload", "camera-only", "-TrialLabel", "new-trial",
                                     "-Destination", str(directory)], capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("destination must be empty", result.stderr)
            self.assertEqual({"label": "prior-trial"}, json.loads(manifest.read_text()))


if __name__ == "__main__":
    unittest.main()

"""Analyze bounded InputLatencyProbe exports without mixing physical and synthetic clocks.

Usage: python tools/analyze_camera_latency.py CAPTURE_DIR [CAPTURE_DIR ...] --output report.json
Captures come from scripts/Measure-InputLatency.ps1 -Instrumented. No device access.
"""

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import sys


PHYSICAL_STAGES = {
    "native_capture_binder_receipt", "native_camera_direct_dispatch",
    "native_camera_main_dispatch", "mapper_mouse_worker_latest_sample",
    "native_motion_current_receipt", "native_key_receipt",
}
SUBMIT = "injectionSubmit_syntheticComposition"
ACK = "injectionAck_syntheticComposition"
LIMITATIONS = [
    "Kernel timestamps exclude peripheral latency; worker timing uses only the latest sample of each consumed entry.",
    "Submit/ACK timing starts at synthetic touch composition, not physical input, and does not prove game processing or display presentation.",
    "Binder/worker matches require unique original source timestamps; coalesced receipts need not have their own worker record.",
    "Endpoint snapshots cannot prove uninterrupted state between endpoints. Physical input requires external confirmation.",
    "Probe recording adds overhead. Session diagnostic percentiles are rolling, overlapping stages and must not be added.",
    "If the ring overwrote rows, probe statistics describe the retained tail, not the entire requested interval.",
    "M1 press/release rates use the entire diagnostic endpoint interval, which can exceed both the probe recording and its retained tail. POINTER_DOWN/UP counts also include keys and other mapped contacts.",
]
PRECISION_COUNTERS = ("activeSamples", "attenuatedSamples", "flickSamples", "invalidSamples", "timingResets", "reversals")


def summary(values):
    values = sorted(values)
    if not values:
        return {"count": 0, "minMs": None, "p50Ms": None, "p95Ms": None,
                "p99Ms": None, "maxMs": None, "meanMs": None}
    return {"count": len(values), "minMs": values[0],
            "p50Ms": values[int((len(values) - 1) * .50)],
            "p95Ms": values[int((len(values) - 1) * .95)],
            "p99Ms": values[int((len(values) - 1) * .99)],
            "maxMs": values[-1], "meanMs": sum(values) / len(values)}


def nested(value, *keys):
    for key in keys:
        if not isinstance(value, dict) or key not in value:
            return None
        value = value[key]
    return value


def counter_delta(before, after, key):
    a, b = before.get(key), after.get(key)
    if (not isinstance(a, (int, float)) or isinstance(a, bool)
            or not isinstance(b, (int, float)) or isinstance(b, bool)):
        return None
    return b - a


def workload_checks(before, after, endpoints, manifest):
    """Counter origin is external; this classifies the declared workload, not timing validity."""
    interval = endpoints["intervalMs"]
    presses = counter_delta(before, after, "fireHoldStarts")
    releases = counter_delta(before, after, "fireHoldEnds")
    key_events = endpoints["counterDeltas"].get("keys")
    available = (interval > 0 and all(v is not None and v >= 0 for v in (presses, releases, key_events)))
    precision_before = before.get("precisionAim") or {}
    precision_after = after.get("precisionAim") or {}
    firing_before, firing_after = precision_before.get("firing"), precision_after.get("firing")
    declared = manifest.get("workload")
    reasons = []
    matches = None
    if declared in ("camera-only", "camera-clicks") and available:
        if declared == "camera-only":
            if presses or releases:
                reasons.append("M1 press/release activity in endpoint interval")
            if key_events:
                reasons.append("keyboard activity in endpoint interval")
            if firing_before is True or firing_after is True:
                reasons.append("precision filter reports M1 firing at an endpoint")
            matches = not reasons
        else:
            matches = presses > 0
            if not matches:
                reasons.append("no M1 press observed in endpoint interval")
    elif declared in ("camera-only", "camera-clicks"):
        reasons.append("M1/key counters missing, reset, or endpoint interval invalid")
    seconds = interval / 1000 if interval > 0 else None
    requested = manifest.get("requestedClickRateHz")
    requested = requested if isinstance(requested, (int, float)) and requested >= 0 else None
    config_before = {key: precision_before.get(key) for key in ("enabled", "fineMotionGain")}
    config_after = {key: precision_after.get(key) for key in ("enabled", "fineMotionGain")}
    precision_deltas = {key: counter_delta(precision_before, precision_after, key) for key in PRECISION_COUNTERS}
    active, attenuated, flicks = (precision_deltas[k] for k in ("activeSamples", "attenuatedSamples", "flickSamples"))
    return {
        "declaredWorkload": declared, "matchesDeclaredWorkload": matches,
        "reasons": reasons, "countersAvailable": available,
        "m1PressDelta": presses, "m1ReleaseDelta": releases, "keyboardEventDelta": key_events,
        "additionalKeyboardActivity": key_events > 0 if key_events is not None else None,
        "endpointIntervalMs": interval,
        "m1PressesPerSecond": presses / seconds if available else None,
        "m1ReleasesPerSecond": releases / seconds if available else None,
        "requestedClickRateHz": requested,
        "requestedPressQuantityOverEndpointInterval": requested * seconds if requested is not None and seconds is not None else None,
        "rateScope": "M1 recognized mapped hold starts/ends over the whole before/after diagnostic interval, not the shorter retained probe tail. Counts do not infer completed clicks from POINTER_* actions, and do not prove physical origin.",
        "precisionAim": {
            "configurationBefore": config_before, "configurationAfter": config_after,
            "sameConfiguration": config_before == config_after if all(v is not None for v in (*config_before.values(), *config_after.values())) else None,
            "firingBefore": firing_before, "firingAfter": firing_after,
            "lastGainBefore": precision_before.get("lastGain"), "lastGainAfter": precision_after.get("lastGain"),
            "counterDeltas": precision_deltas,
            "counterResetDetected": any(v is not None and v < 0 for v in precision_deltas.values()),
            "attenuatedFractionOfActiveSamples": attenuated / active if active is not None and active > 0 and attenuated is not None and attenuated >= 0 else None,
            "flickFractionOfActiveSamples": flicks / active if active is not None and active > 0 and flicks is not None and flicks >= 0 else None,
            "scope": "Precision counters cover the endpoint interval. Enabled with zero active-sample delta did not filter any sample during that interval; enablement alone does not imply attenuation.",
        },
    }


def endpoint_checks(before, after):
    reasons = []
    for name, value in (("before", before), ("after", after)):
        for field in ("active", "backend", "mouseCaptured"):
            if value.get(field) is not True:
                reasons.append(f"{name}.{field} must be true")
        if value.get("captureReady", value.get("accessibility")) is not True:
            reasons.append(f"{name}: capture unavailable")
        for field in ("editorVisible", "releaseUnverified", "testRunning"):
            if value.get(field) is True:
                reasons.append(f"{name}.{field} is true")
        if value.get("pointerMode", "camera") != "camera":
            reasons.append(f"{name}: camera mode not active")
    required = ("metricsSessionId", "backendSessionId", "activePackage", "targetDisplayId", "state", "recoveryState")
    for field in required:
        if before.get(field) is None or after.get(field) is None:
            reasons.append(f"missing endpoint identity: {field}")
        elif before[field] != after[field]:
            reasons.append(f"endpoint changed: {field}")
    for field in ("removedCount", "materialCount"):
        a = nested(before, "windowContext", "deviceInventory", field)
        b = nested(after, "windowContext", "deviceInventory", field)
        if a != b:
            reasons.append(f"device inventory changed: {field}")
    deltas = {}
    for field in ("relativeSamples", "mouse", "keys", "injected"):
        a, b = before.get(field), after.get(field)
        if not isinstance(a, (float, int)) or not isinstance(b, (float, int)):
            deltas[field] = None
            reasons.append(f"missing counter: {field}")
        else:
            deltas[field] = b - a
            if b < a:
                reasons.append(f"counter reset: {field}")
    for field in ("relativeSamples", "injected"):
        if deltas[field] is not None and deltas[field] <= 0:
            reasons.append(f"no fresh mapped workload: {field}")
    interval = after.get("uptimeMs", 0) - before.get("uptimeMs", 0)
    if interval <= 0:
        reasons.append("endpoint interval must be positive")
    # Missing optional click counters limit workload classification, not useful latency data.
    for field in ("fireHoldStarts", "fireHoldEnds"):
        deltas[field] = counter_delta(before, after, field)
    return {"stableFreshEndpoints": not reasons, "reasons": reasons,
            "intervalMs": interval, "counterDeltas": deltas}


def validate_export(start, stop, rows):
    errors = []
    for key in ("runId", "pid", "startedTimeNanos", "deadlineTimeNanos"):
        if start.get(key) is None or start.get(key) != stop.get(key):
            errors.append(f"probe identity changed or missing: {key}")
    if stop.get("enabled") is not False:
        errors.append("probe was not stopped before export")
    stored, total, capacity = (stop.get(k) for k in ("storedCount", "totalRecorded", "capacity"))
    if not all(isinstance(v, int) for v in (stored, total, capacity)):
        errors.append("missing integer ring metadata")
    else:
        if not 0 <= stored <= capacity or total < stored:
            errors.append("invalid ring counts")
        if stored != len(rows):
            errors.append("incomplete record export")
        if stop.get("overwrittenCount") != total - stored:
            errors.append("inconsistent overwritten count")
    if any(not isinstance(row, dict) for row in rows):
        return errors + ["each record must be an object"]
    sequences = [row.get("sequence") for row in rows]
    if any(not isinstance(s, int) or s < 1 for s in sequences):
        errors.append("invalid record sequence")
    elif sequences != sorted(set(sequences)) or any(b != a + 1 for a, b in zip(sequences, sequences[1:])):
        errors.append("record sequences must be unique, ordered and contiguous")
    lower, upper = stop.get("startedTimeNanos"), stop.get("stoppedTimeNanos")
    if not isinstance(lower, int) or not isinstance(upper, int) or upper < lower:
        errors.append("invalid probe time bounds")
    else:
        for row in rows:
            observed = row.get("observedTimeNanos")
            if not isinstance(observed, int) or not lower <= observed <= upper:
                errors.append("record observed outside stopped probe interval")
                break
    return errors


def valid_time(row):
    source, observed = row.get("sourceTimeNanos"), row.get("observedTimeNanos")
    return (isinstance(source, int) and isinstance(observed, int)
            and source > 0 and observed >= source and row.get("validTiming", True) is True)


def pair_physical(rows):
    receipts, workers = defaultdict(list), defaultdict(list)
    for row in rows:
        if row.get("stage") == "native_capture_binder_receipt":
            receipts[row.get("sourceTimeNanos")].append(row)
        elif row.get("stage") == "mapper_mouse_worker_latest_sample":
            workers[row.get("sourceTimeNanos")].append(row)
    values = []
    counts = Counter()
    used = set()
    for source, candidates in workers.items():
        original = receipts.get(source, [])
        if len(candidates) != 1 or len(original) > 1:
            counts["ambiguousWorkerRecords"] += len(candidates)
            continue
        if not original:
            counts["workerWithoutRetainedReceipt"] += 1
            continue
        receipt, worker = original[0], candidates[0]
        if (not valid_time(receipt) or not valid_time(worker)
                or receipt["sequence"] >= worker["sequence"]
                or receipt["observedTimeNanos"] > worker["observedTimeNanos"]):
            counts["invalidPairs"] += 1
            continue
        values.append((worker["observedTimeNanos"] - receipt["observedTimeNanos"]) / 1e6)
        used.add(receipt["sequence"])
    counts["receiptWithoutMatchedWorker"] = sum(len(v) for v in receipts.values()) - len(used)
    return {"binderReceiptToWorkerMs": summary(values), "pairing": dict(counts),
            "scope": "Unique same sourceTimeNanos at Binder receipt and latest-sample worker stage; excludes capture before Binder and injection after worker."}


def pair_injection(rows):
    pending = None
    values, by_action = [], defaultdict(list)
    counts = Counter()
    for row in rows:
        stage = row.get("stage")
        if stage == SUBMIT:
            if pending is not None:
                counts["submitWithoutAck"] += 1
            pending = row
        elif stage == ACK:
            if pending is None:
                counts["ackWithoutSubmit"] += 1
                continue
            submit, pending = pending, None
            # Sequential sink calls, not eventTime as a dictionary key: several frames may
            # share the same millisecond/action. Never pair across an intervening submit.
            identity = ("action", "eventTimeMs", "sourceTimeNanos")
            if any(submit.get(k) != row.get(k) for k in identity):
                counts["mismatchedPairs"] += 1
                continue
            if (not valid_time(submit) or not valid_time(row)
                    or row["sequence"] <= submit["sequence"]
                    or row["observedTimeNanos"] < submit["observedTimeNanos"]
                    or not isinstance(row.get("eventTimeMs"), int)
                    or row["eventTimeMs"] < 0
                    or row["sourceTimeNanos"] != row["eventTimeMs"] * 1_000_000):
                counts["invalidPairs"] += 1
                continue
            value = (row["observedTimeNanos"] - submit["observedTimeNanos"]) / 1e6
            values.append(value)
            by_action[str(row["action"] & 255)].append(value)
    if pending is not None:
        counts["submitWithoutAck"] += 1
    return {"submitToAckMs": summary(values),
            "byMaskedAction": {key: summary(value) for key, value in sorted(by_action.items())},
            "pairing": dict(counts), "scope": "Sequential synthetic composition submit/ACK pairs. ACK may be a rejection; records do not contain its result."}


def analyze(before, after, start, stop, rows, manifest=None):
    manifest = manifest or {}
    errors = validate_export(start, stop, rows)
    physical, synthetic, other = {}, {}, {}
    if any(not isinstance(row, dict) or not isinstance(row.get("stage"), str) for row in rows):
        raise ValueError("each record must be an object with a stage string")
    for stage in sorted({row.get("stage", "unknown") for row in rows}):
        matching = [row for row in rows if row.get("stage") == stage]
        values = [(row["observedTimeNanos"] - row["sourceTimeNanos"]) / 1e6
                  for row in matching if valid_time(row)]
        target = physical if stage in PHYSICAL_STAGES else synthetic if stage.endswith("_syntheticComposition") else other
        target[stage] = dict(summary(values), invalidTimingCount=len(matching) - len(values))
    endpoints = endpoint_checks(before, after)
    ended_early = (isinstance(stop.get("stoppedTimeNanos"), int)
                   and isinstance(stop.get("deadlineTimeNanos"), int)
                   and stop["stoppedTimeNanos"] < stop["deadlineTimeNanos"])
    complete = not errors and stop.get("overwrittenCount") == 0 and not ended_early
    workload = workload_checks(before, after, endpoints, manifest)
    physical_pairs = pair_physical(rows) if not errors else None
    injection_pairs = pair_injection(rows) if not errors else None
    physical_pair_count = physical_pairs["binderReceiptToWorkerMs"]["count"] if physical_pairs is not None else 0
    injection_pair_count = injection_pairs["submitToAckMs"]["count"] if injection_pairs is not None else 0
    timing_available = physical_pair_count > 0 and injection_pair_count > 0
    return {
        "schemaVersion": 1, "manifest": manifest, "probeExportValid": not errors,
        "probeExportErrors": errors, "endpoints": endpoints,
        "workloadValidation": workload,
        "physicalInputConfirmed": manifest.get("physicalInputConfirmed") is True,
        "timingAvailable": timing_available,
        "timingCoverage": {"physicalBinderWorkerPairs": physical_pair_count,
                           "syntheticSubmitAckPairs": injection_pair_count,
                           "scope": "At least one valid pair of each type is required for comparison; counts do not establish statistical confidence."},
        "comparableInternalTiming": not errors and endpoints["stableFreshEndpoints"] and timing_available,
        "fullRequestedProbeWindowRetained": complete,
        "recordingEndedBeforeDeadline": ended_early,
        "ring": {key: stop.get(key) for key in ("capacity", "totalRecorded", "storedCount", "overwrittenCount", "stopReason")},
        "target": {key: after.get(key) for key in ("activePackage", "targetDisplayId", "activeProfileId", "profileName", "cameraRequestedRateHz")},
        "probe": {key: stop.get(key) for key in ("runId", "pid", "durationMs", "startedTimeNanos", "stoppedTimeNanos")},
        "probeRecordingIntervalMs": (stop["stoppedTimeNanos"] - stop["startedTimeNanos"]) / 1e6 if not errors else None,
        "retainedObservedSpanMs": ((max(row["observedTimeNanos"] for row in rows) - min(row["observedTimeNanos"] for row in rows)) / 1e6) if rows and not errors else None,
        "physicalSourceStageMs": physical, "syntheticCompositionStageMs": synthetic,
        "otherStageMs": other, "physicalPairing": physical_pairs,
        "injectionPairing": injection_pairs,
        "rollingDiagnosticsAfter": {key: after.get(key) for key in (
            "queueMs", "ipcMs", "cameraDeliveryWaitMs", "backendFocusMs", "backendPipeMs",
            "backendFocusCount", "backendPipeCount", "cameraMotionFreshness", "queueDiagnostics")},
        "rollingScope": "Session rings at final snapshot; may contain earlier workload. Percentiles must not be added or subtracted to reconstruct stages.",
        "cameraFreshnessCounterDeltas": {key: counter_delta(before.get("cameraMotionFreshness") or {}, after.get("cameraMotionFreshness") or {}, key)
                                         for key in ("expiredSamples", "invalidTimeSamples")},
        "limitations": LIMITATIONS,
    }


def load_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def analyze_directory(directory):
    directory = Path(directory)
    names = ("controller-before.json", "controller-after.json", "probe-start.json", "probe-stop.json", "probe-records.json")
    before, after, start, stop, rows = [load_json(directory / name) for name in names]
    if isinstance(rows, dict):  # PowerShell can serialize one row as an object.
        rows = [rows]
    if not isinstance(rows, list):
        raise ValueError("probe-records.json must contain an array")
    manifest_path = directory / "trial-manifest.json"
    report = analyze(before, after, start, stop, rows,
                     load_json(manifest_path) if manifest_path.exists() else {})
    report["directory"] = str(directory.resolve())
    return report


def compare(reports):
    signatures = [(r["manifest"].get("workload"), r["manifest"].get("requestedClickRateHz")) for r in reports]
    comparable = all(r["comparableInternalTiming"] for r in reports)
    physical = all(r["physicalInputConfirmed"] for r in reports)
    target_signatures = [json.dumps(r["target"], sort_keys=True) for r in reports]
    device_signatures = [(r["manifest"].get("serial"), r["manifest"].get("applicationId")) for r in reports]
    target_complete = all(all(r["target"].get(key) is not None for key in
                             ("activePackage", "targetDisplayId", "activeProfileId", "cameraRequestedRateHz")) for r in reports)
    precision = [r["workloadValidation"]["precisionAim"] for r in reports]
    precision_known = all(all(v is not None for v in (*p["configurationBefore"].values(), *p["configurationAfter"].values())) for p in precision)
    precision_same = (all(p["sameConfiguration"] for p in precision)
                      and len({json.dumps(p["configurationAfter"], sort_keys=True) for p in precision}) <= 1)
    return {"sameDeclaredWorkload": len(set(signatures)) <= 1 if all(v[0] is not None for v in signatures) else None,
            "sameTargetProfileRate": len(set(target_signatures)) <= 1 if target_complete else None,
            "samePrecisionAimConfiguration": precision_same if precision_known else None,
            "sameDeclaredDeviceAndApplication": len(set(device_signatures)) <= 1 if all(all(v is not None for v in pair) for pair in device_signatures) else None,
            "allStableValidInternalTiming": comparable,
            "allPhysicalInputConfirmed": physical,
            "allFullProbeWindowsRetained": all(r["fullRequestedProbeWindowRetained"] for r in reports),
            "allDeclaredWorkloadsVerified": all(r["workloadValidation"]["matchesDeclaredWorkload"] is True for r in reports),
            "scope": "Each trial is reported independently; null checks lack metadata. Profile ID is not a settings hash. Match APK/device/profile/rate/thermal conditions before attributing a before/after difference."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directories", nargs="+", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        reports = [analyze_directory(directory) for directory in args.directories]
    except (OSError, ValueError, TypeError, KeyError) as error:
        parser.error(str(error))
    output = {"trials": reports, "comparisonChecks": compare(reports)}
    serialized = json.dumps(output, ensure_ascii=False, indent=2, allow_nan=False)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized + "\n", encoding="utf-8")
    else:
        print(serialized)
    return 0 if all(r["comparableInternalTiming"] for r in reports) else 2


if __name__ == "__main__":
    sys.exit(main())

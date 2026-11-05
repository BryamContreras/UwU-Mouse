"""Extract unannotated evidence frames from a local trial video using installed OpenCV."""
import argparse
import json
from pathlib import Path

import cv2

parser = argparse.ArgumentParser()
parser.add_argument("video", type=Path)
parser.add_argument("--interval", type=float, default=3.0)
args = parser.parse_args()
if not 0.5 <= args.interval <= 30:
    parser.error("interval must be between 0.5 and 30 seconds")
video = args.video.resolve(strict=True)
capture = cv2.VideoCapture(str(video))
if not capture.isOpened():
    raise SystemExit("Cannot open evidence video")
fps = capture.get(cv2.CAP_PROP_FPS)
duration = capture.get(cv2.CAP_PROP_FRAME_COUNT) / fps if fps > 0 else 0
if not 0 < duration <= 180:
    raise SystemExit("Expected a bounded trial video of at most 180 seconds")
destination = video.parent / "frames"
destination.mkdir(exist_ok=True)
frames = []
timestamp = 0.0
while timestamp < duration:
    capture.set(cv2.CAP_PROP_POS_MSEC, timestamp * 1000)
    success, frame = capture.read()
    if success:
        target = destination / f"at-{timestamp:06.2f}s.jpg"
        if not cv2.imwrite(str(target), frame, [cv2.IMWRITE_JPEG_QUALITY, 90]):
            raise SystemExit("Cannot write evidence frame")
        frames.append({"seconds": timestamp, "file": target.name})
    timestamp += args.interval
capture.release()
metadata = {"durationSeconds": duration, "fps": fps, "frames": frames}
(destination / "index.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
print(json.dumps(metadata))

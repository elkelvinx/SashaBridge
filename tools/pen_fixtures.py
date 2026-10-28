"""Writes the binary pen-sample fixture and its expected decoding.

The format is specified in docs/protocolo-v2.md (section "Muestras del lápiz"). The Kotlin
and Rust tests decode protocol/fixtures/pen-samples/samples.bin and compare the result with
samples.json, and the Kotlin encoder must produce the same bytes from the JSON.

Usage, from the repository root:
    python tools/pen_fixtures.py
"""

import json
import struct
from pathlib import Path

KIND = 0x50  # "P": a frame of pen samples
VERSION = 1

SAMPLES = [
    # Hovering above the screen, then touching it, pressing harder, with the side button.
    {"timeMs": 1000, "x": 1200, "y": 64000, "pressure": 0, "tiltX": -10, "tiltY": 25,
     "inContact": False, "inRange": True, "barrel": False, "eraser": False},
    {"timeMs": 1004, "x": 1300, "y": 63800, "pressure": 21845, "tiltX": -12, "tiltY": 24,
     "inContact": True, "inRange": True, "barrel": False, "eraser": False},
    {"timeMs": 1008, "x": 65535, "y": 0, "pressure": 65535, "tiltX": 90, "tiltY": -90,
     "inContact": True, "inRange": True, "barrel": True, "eraser": False},
    {"timeMs": 4294967295, "x": 0, "y": 32768, "pressure": 100, "tiltX": 0, "tiltY": 0,
     "inContact": True, "inRange": True, "barrel": False, "eraser": True},
]


def flags(sample):
    return (
        (1 if sample["inContact"] else 0)
        | (2 if sample["inRange"] else 0)
        | (4 if sample["barrel"] else 0)
        | (8 if sample["eraser"] else 0)
    )


def encode(samples):
    out = struct.pack("<BBH", KIND, VERSION, len(samples))
    for s in samples:
        out += struct.pack("<IHHHbbBB", s["timeMs"], s["x"], s["y"], s["pressure"], s["tiltX"], s["tiltY"], flags(s), 0)
    return out


if __name__ == "__main__":
    out = Path(__file__).resolve().parent.parent / "protocol" / "fixtures" / "pen-samples"
    out.mkdir(parents=True, exist_ok=True)
    (out / "samples.bin").write_bytes(encode(SAMPLES))
    (out / "samples.json").write_text(json.dumps({"samples": SAMPLES}, indent=2) + "\n", encoding="utf-8", newline="\n")

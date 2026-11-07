#!/usr/bin/env python
"""Extract the real outline geometry out of VeinMinerPlusClient.java and run it.

The plan for this round forbids writing a second copy of the algorithm to prove the
first one wrong: the geometry under test has to be the shipped one. So this script pulls
the exact method bodies out of the source file, wraps them in a tiny harness with a
BlockPos stand-in, and runs the scenarios. If the source changes shape and extraction
breaks, the script fails loudly instead of silently testing a stale copy.
"""

import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent.parent
SOURCE = PROJECT / "src/main/java/com/extrarawstyle/veinminerplus/VeinMinerPlusClient.java"
OUT_DIR = HERE

# Methods that make up the geometry, in source order. Each entry is the signature prefix
# used to find the method; the body is taken verbatim up to its matching closing brace.
GEOMETRY_METHODS = [
    "private static List<double[]> outlineSegments(",
    "private static List<double[]> withoutAimedEdges(",
    "private static List<double[]> cutXRun(",
    "private static List<double[]> cutZRun(",
    "private static boolean isOutlineCorner(",
    "private static void addRim(",
    "private static void addRimSegments(",
]

GEOMETRY_RECORDS = ["private record Corner(", "private record RimKey("]


def extract_method(text, signature):
    start = text.index(signature)
    brace = text.index("{", start)
    depth = 0
    for index in range(brace, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start:index + 1]
    raise SystemExit("unbalanced braces while extracting " + signature)


def extract_record(text, signature):
    start = text.index(signature)
    end = text.index("}", start)
    return text[start:end + 1]


def main():
    if not SOURCE.exists():
        raise SystemExit("source not found: " + str(SOURCE))
    text = SOURCE.read_text(encoding="utf-8")

    pieces = [extract_method(text, sig) for sig in GEOMETRY_METHODS]
    pieces += [extract_record(text, sig) for sig in GEOMETRY_RECORDS]

    harness = (HERE / "OutlineHarness.java").read_text(encoding="utf-8")
    # The harness supplies its own main/scenarios and its own BlockPos/Set plumbing; only the
    # geometry is injected, so the tested code is the shipped code.
    injected = harness.replace("/*__GEOMETRY__*/", "\n\n".join(pieces))
    if injected == harness:
        raise SystemExit("harness is missing the /*__GEOMETRY__*/ marker")

    generated = OUT_DIR / "OutlineGenerated.java"
    generated.write_text(injected, encoding="utf-8")
    print("extracted", len(pieces), "members from", SOURCE.name, "->", generated.name)
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/bin/bash
# Verify deps-snapshot/ against the pinned checksums in tools/deps-checksums.txt (v2.2, F04).
# Fails loudly (exit 1) on any missing or tampered artifact.
# Usage: tools/verify-deps.sh   (run from anywhere; paths resolve from this script)
set -u
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SNAP="$ROOT/deps-snapshot"
SUMS="$ROOT/tools/deps-checksums.txt"

[ -f "$SUMS" ] || { echo "ERROR: checksum file missing: $SUMS" >&2; exit 1; }
[ -d "$SNAP/jars" ] || { echo "ERROR: deps-snapshot/jars/ missing — snapshot the deps first (see README, Build)" >&2; exit 1; }
[ -d "$SNAP/aar" ] || { echo "ERROR: deps-snapshot/aar/ missing — snapshot the deps first (see README, Build)" >&2; exit 1; }

echo "== verifying deps-snapshot/ against tools/deps-checksums.txt =="
if ! (cd "$SNAP" && sha256sum -c --quiet "$SUMS"); then
    echo "ERROR: deps-snapshot verification FAILED — a dependency artifact is missing or tampered with." >&2
    echo "Re-fetch with the recovery path in README (Build) and re-run tools/verify-deps.sh." >&2
    exit 1
fi

# Warn (do not fail) about snapshot files the checksum file does not cover:
# an artifact added without re-pinning is invisible to the check above.
extra=$(cd "$SNAP" && find jars aar -type f | sort | comm -23 - <(grep -v '^#' "$SUMS" | awk '{print $2}' | sort))
if [ -n "$extra" ]; then
    echo "WARNING: files in deps-snapshot/ not covered by tools/deps-checksums.txt:" >&2
    printf '%s\n' "$extra" | sed 's/^/  /' >&2
    echo "Re-pin with: cd deps-snapshot && find jars aar -type f | sort | xargs sha256sum" >&2
fi

n=$(grep -vc '^#' "$SUMS")
echo "deps-snapshot OK: $n pinned files verified."

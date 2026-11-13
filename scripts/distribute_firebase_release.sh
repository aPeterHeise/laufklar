#!/usr/bin/env bash
# Manually distribute the exact signed APK already published on GitHub.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo 'Usage: bash scripts/distribute_firebase_release.sh v0.1.6' >&2
  exit 2
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
release_dir="$(mktemp -d "${TMPDIR:-/tmp}/laufklar-distribution.XXXXXX")"
trap 'rm -rf -- "$release_dir"' EXIT

python3 "$script_dir/prepare_firebase_release.py" \
  --repo aPeterHeise/laufklar --tag "$1" --output "$release_dir"

# Keep recipients local; pass them only to Firebase when an upload is requested.
testers="${FIREBASE_TESTERS:-}"
if [[ -z "$testers" && -f "$script_dir/../.firebase-testers" ]]; then
  testers="$(cat "$script_dir/../.firebase-testers")"
fi
if [[ -z "$testers" ]]; then
  echo 'Set FIREBASE_TESTERS or add the tester email to .firebase-testers.' >&2
  exit 1
fi
args=(appdistribution:distribute "$release_dir/laufklar-$1.apk"
  --app 1:166111565018:android:bb37d20790d50bbbe581e3
  --project peter-heise-website-2026
  --release-notes-file "$release_dir/release-notes.txt" --non-interactive)
if [[ -n "$testers" ]]; then args+=(--testers "$testers"); fi
if [[ -n "${FIREBASE_GROUPS:-}" ]]; then args+=(--groups "$FIREBASE_GROUPS"); fi
npx -y firebase-tools@latest "${args[@]}"

#!/usr/bin/env bash
# Builds a release's notes: its CHANGELOG.md section, then the install instructions.
# Usage: .github/scripts/release-notes.sh 0.2.0 > notes.md   (fails if the section is missing)
set -euo pipefail
version="$1"
root="$(cd "$(dirname "$0")/../.." && pwd)"
changes="$(awk -v v="$version" '
  /^## / { if (found) exit; if ($2 == v) { found = 1; next } }
  found { print }
' "$root/CHANGELOG.md" | sed -e '/./,$!d')"
if [ -z "$changes" ]; then
  echo "CHANGELOG.md has no section for $version" >&2
  exit 1
fi
printf "## What's new\n\n%s\n\n" "$changes"
cat "$root/.github/release-notes.md"

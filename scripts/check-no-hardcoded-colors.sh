#!/usr/bin/env bash
# Fails if a hardcoded colour literal appears outside the theme package.
#
# The SDK had 120 of these across 54 files, which is why overriding the brand colour
# used to leave half the UI rendering SPICE blue. The token set replaced them; this
# stops them growing back.
#
# Scope note: this catches Color(0x...) literals only. It cannot see named constants
# like Color.White, so a review still has to ask whether a named colour is carrying a
# token's role — see the Color.White audit in the theme migration.
set -euo pipefail

cd "$(dirname "$0")/.."

UI_DIR="sdk-android/src/main/java/com/medtroniclabs/microcoaching/ui"

# RankBadge's gold/silver/bronze are deliberately not themeable — medal metals carry
# universal meaning. This is the only sanctioned exemption.
ALLOWLIST="leaderboard/components/RankBadge.kt"

violations=$(grep -rn "Color(0x" --include="*.kt" "$UI_DIR" --exclude-dir=theme \
  | grep -v "$ALLOWLIST" || true)

if [ -n "$violations" ]; then
  echo "Hardcoded colour literals found outside ui/theme/:"
  echo "$violations"
  echo
  echo "Add a token to CoachingColors, or derive from one (see ColorBlending.kt)."
  echo "If it genuinely must not be themeable, add it to ALLOWLIST with a reason."
  exit 1
fi

echo "No hardcoded colour literals outside ui/theme/."

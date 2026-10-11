#!/bin/bash
# Plays the engine under test against the outside engines the project is measured against.
#
#   GATAXX_JAR=target/gataxx.jar \
#   TIKTAXX=path/to/tiktaxx FUNES=path/to/Funes MOONBIRD=path/to/Moonbird-1.0.0-linux-amd64 \
#   scripts/outside_engines.sh [games [seed]]
#
# Any engine whose path is not set is skipped. 100 ms per move, one thread each, hash 128 MB, six random opening plies, the same
# openings (seed) for every engine so that results are comparable between runs. Prints one result line per engine.
#
# Engine notes (see docs/tuning-log.md):
#  - TikTaxx and Funes need no special handling.
#  - Moonbird rejects the second move of any "position ... moves ..." list, so it is sent bare FENs (--fen-only2); it also
#    overruns "go movetime" by about a second in some one-sided positions, so the timeout grace is raised to 3 s.
set -u
JAR=${GATAXX_JAR:-target/gataxx.jar}
GAMES=${1:-400}
SEED=${2:-5150}
CONCURRENCY=${CONCURRENCY:-8}

run() {
  local label=$1 cmd=$2
  shift 2
  local result
  result=$(java -cp "$JAR" spamalot.gataxx.tools.Match \
      --engine1 "java -jar $JAR" --engine2 "$cmd" \
      --go1 "movetime 100" --go2 "movetime 100" \
      --games "$GAMES" --concurrency "$CONCURRENCY" --opening-plies 6 --seed "$SEED" \
      --opt1 Hash=128 "$@" 2>&1 | grep -E "^Score|^Elo|illegal|timeout" | cut -c1-240 | tr '\n' ' ')
  echo "[$label seed $SEED games $GAMES] $result"
}

[ -n "${TIKTAXX:-}" ] && run TikTaxx "$TIKTAXX"
[ -n "${FUNES:-}" ] && run Funes "$FUNES"
[ -n "${MOONBIRD:-}" ] && run Moonbird "$MOONBIRD" --opt2 Hash=128 --fen-only2 --grace 3000
exit 0

#!/bin/bash
# Retrains the pair-pattern evaluation from the engine's own games: rounds of (self-play data, then a fit on all rounds so far).
#
#   scripts/train.sh [rounds [games [nodes]]]       defaults: 3 rounds of 200000 games at 8000 nodes per move
#
# Environment (all optional):
#   JAR          engine jar to use (default target/gataxx.jar; build it first with mvn -DskipTests package)
#   OUT          output directory (default training-data/run)
#   THREADS      threads for game generation (default: all cores); the data does not depend on it
#   FIT_THREADS  threads for the fit (default THREADS); use 1 for a bit-exact repeat of a run (slow)
#   FIT_HEAP     heap for the fit (default 16g, enough for about 45M positions)
#   SEED         seed of round 1 (default 501); round r uses SEED + r - 1
#   EPOCHS, LR, LAMBDA   fit settings (defaults 6, 0.5, 0.5)
#   START_TABLE  pair table that does the searching in round 1 (default: the built-in one)
#   DRY_RUN=1    only print the commands
#
# Writes OUT/round-R.bin (the games), OUT/table-R.bin (the fit on rounds 1..R) and OUT/SHA256SUMS. Nothing under src/ is touched:
# to use a table copy it to src/main/resources/spamalot/gataxx/eval/pair-patterns.bin, rebuild, and test it as docs/training.md says.
set -euo pipefail

ROUNDS=${1:-3}
GAMES=${2:-200000}
NODES=${3:-8000}
JAR=${JAR:-target/gataxx.jar}
OUT=${OUT:-training-data/run}
THREADS=${THREADS:-$(nproc)}
FIT_THREADS=${FIT_THREADS:-$THREADS}
FIT_HEAP=${FIT_HEAP:-16g}
SEED=${SEED:-501}
EPOCHS=${EPOCHS:-6}
LR=${LR:-0.5}
LAMBDA=${LAMBDA:-0.5}

run() {
  echo "+ $*"
  if [ -z "${DRY_RUN:-}" ]; then
    "$@"
  fi
}

[ -n "${DRY_RUN:-}" ] || [ -f "$JAR" ] || { echo "no jar at $JAR: build it first (mvn -DskipTests package)" >&2; exit 1; }
mkdir -p "$OUT"

data=()
for r in $(seq 1 "$ROUNDS"); do
  if [ "$r" -eq 1 ]; then
    pair=(--pair "${START_TABLE:-builtin}")
  else
    pair=(--pair "$OUT/table-$((r - 1)).bin")
  fi
  # The search is done by the engine as it plays (features plus the pair table, no pattern table); what is recorded as the base
  # is the features alone, which is what the table has to correct.
  run java -Xmx4g -cp "$JAR" spamalot.gataxx.tools.GenData --out "$OUT/round-$r.bin" --games "$GAMES" --nodes "$NODES" \
    --threads "$THREADS" --seed $((SEED + r - 1)) --record features --pattern off "${pair[@]}" --pair-blend 1.0
  data+=("$OUT/round-$r.bin")
  joined=$(IFS=,; echo "${data[*]}")
  run java -Xmx"$FIT_HEAP" -cp "$JAR" spamalot.gataxx.tools.PairFit --data "$joined" --out "$OUT/table-$r.bin" \
    --epochs "$EPOCHS" --lr "$LR" --lambda "$LAMBDA" --threads "$FIT_THREADS"
  if [ -z "${DRY_RUN:-}" ]; then
    (cd "$OUT" && sha256sum "round-$r.bin" "table-$r.bin" >> SHA256SUMS)
  fi
done
echo "done: tables in $OUT (table-$ROUNDS.bin is the fit on all $ROUNDS rounds)"

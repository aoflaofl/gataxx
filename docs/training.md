# Training the evaluation

The engine's evaluation is the hand-made features (`FeatureEvaluator`) plus a **pair-pattern correction** (`PairEvaluator`): for each of
80 placements of two 2x2 blocks of the board, the exact contents of the eight squares select one weight, and a phase-indexed
mobility table adds two more. The weights live in `src/main/resources/spamalot/gataxx/eval/pair-patterns.bin` and are learned from
the engine's own games. `scripts/train.sh` runs the whole procedure.

## What it does

A *round* is two steps; a run is several rounds, each fitted on all the data so far.

1. **Games** (`tools.GenData`). The engine plays itself with a fixed number of nodes per move (default 8000) from random 6 to 12 ply
   openings. The search uses the current pair table and no pattern table. Every position after the opening is written with the
   search score, the final result of its game, and the **static evaluation of the hand-made features alone** (`--record features`):
   that is the base the table has to correct. Records are 35 bytes (`GenData` documents the layout).
2. **Fit** (`tools.PairFit`). Adagrad logistic regression of `sigmoid((features + table) / S)` on a target that is half the game result
   and half the squashed search score (`--lambda 0.5`); `S` is found automatically. Weights of patterns that are mirror images or
   rotations of each other are tied. A fifth of the games' worth of positions (games whose number is a multiple of 20) is held out
   to pick the best epoch. The file is written with one entry per tied class and the mobility table.

Round 1 is searched by the table that is built into the jar (or `START_TABLE`); round r > 1 by the table fitted after round r - 1.

```
mvn -DskipTests package
scripts/train.sh                  # 3 rounds, 200000 games each, 8000 nodes per move
scripts/train.sh 1 20000 2000     # a quick try
DRY_RUN=1 scripts/train.sh        # only print the commands
```

Environment variables (`JAR`, `OUT`, `THREADS`, `FIT_THREADS`, `FIT_HEAP`, `SEED`, `EPOCHS`, `LR`, `LAMBDA`, `START_TABLE`) are listed at the
top of the script. Output goes to `training-data/run/` (git-ignored): `round-R.bin`, `table-R.bin` and `SHA256SUMS`.

How long it takes (20 cores): 200,000 games of 8000 nodes take about 16-17 minutes and give 15.4 million positions (540 MB); the fit
takes 3-8 seconds per epoch on 15-46 million positions with all cores. A full three-round run is about an hour.

## Reproducibility

- `GenData` gives the same file for the same seed and settings **whatever the number of threads** (every game has its own seeded
  random generator, the search is node-limited, and games are written in game order). `GenDataTest` checks it.
- `PairFit` is bit-exact with `--threads 1` (`FIT_THREADS=1`); with several threads the parallel updates race and two runs give slightly
  different (equally good) tables. Two runs of `scripts/train.sh` with `FIT_THREADS=1` and the same seed produce identical
  checksums.
- The table embedded in 1.10.0 was made with the same procedure, but round 1 was searched by the 1.9.0 engine (which still had the
  pattern table) and rounds 2 and 3 by the engines of that moment, so running the script today gives a new table of the same kind, not
  that exact file.

## Trying a table and adopting it

A table can be tried without rebuilding: `setoption name PairFile value <file>` (or `--opt1 PairFile=<file>` in `tools.Match`). Play it
against the current engine at equal time and adopt it only if it is clearly better:

```
java -cp target/gataxx.jar spamalot.gataxx.tools.Match \
  --engine1 "java -jar target/gataxx.jar" --engine2 "java -jar target/gataxx.jar" --opt1 PairFile=training-data/run/table-3.bin \
  --go1 "movetime 50" --go2 "movetime 50" --games 1200 --concurrency 8 --opening-plies 6 --seed 1
```

The rule used so far: at least +20 Elo over 1200 games, then confirmed on a fresh seed with 2000 games, and then a check against an
outside engine (self-play gains overstate gains against independent engines). To adopt a table, copy it to
`src/main/resources/spamalot/gataxx/eval/pair-patterns.bin`, rebuild, run the tests and log the result in `docs/tuning-log.md`.

## What was learned (see `docs/tuning-log.md`)

- Pooling rounds matters more than the newest round: rounds 2 and 3 pooled without round 1 gained nothing, all three pooled gained
  +87, +26 and +16 Elo over the step before. Returns shrink, so more rounds will add little.
- The pattern table of earlier versions is not needed once the pair table is fitted on top of the features alone: it saved a third of
  the evaluation time and gained strength.
- Using fewer placements (fewer table lookups) was faster per node but lost strength at equal time, for every subset tried.
- The lookups are the cost (about 129 per evaluation in the old layout, 80 plus two now): layouts that keep the values identical
  but change the table size or the index arithmetic did not make the search faster.

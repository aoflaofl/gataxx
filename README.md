# gataxx

[![build](https://github.com/aoflaofl/gataxx/actions/workflows/build.yml/badge.svg)](https://github.com/aoflaofl/gataxx/actions/workflows/build.yml)

An [Ataxx](https://en.wikipedia.org/wiki/Ataxx) game-playing engine written in Java 21.
It runs at the command line (no GUI) and speaks the Universal Ataxx Interface (UAI),
a UCI-style protocol. Moves are found with an iterative-deepening NegaMax search with
alpha-beta pruning.

## Download

Prebuilt jars are attached to each [GitHub release](https://github.com/aoflaofl/gataxx/releases). You need a
Java 21 (or newer) runtime:

```
java -jar gataxx-1.10.0.jar
```

## Strength

Measured with the bundled match harness (see [docs/tuning-log.md](docs/tuning-log.md) for every experiment): one
thread each, default settings, random 6-ply openings, 100 ms per move unless stated.

- Version 1.10.0 beats 1.9.0 in 67% of games at a fixed 50 ms per move (+122 Elo, 2000 games) and in 69% on a game clock
  (+136 Elo, 1200 games at 3 s + 50 ms per game, no time forfeits). One change: the 49-square pattern table is gone
  (`PatternBlend 0`) and the pair table, retrained on three rounds of the engine's own games (46M positions, the hand-made
  features alone as the base it corrects), does its work. An evaluation costs about 28% less, so the search runs 4.2-4.5M nodes per
  second instead of 3.6-3.7M, and the better evaluation reaches depth 12 in about 35% less time. Dropping pair placements for
  more speed lost strength at equal time (see `PairPlacements`), and layout changes that keep the values identical (smaller
  tables, other index arithmetic) did not speed up the search: its cost is the number of table lookups.
- Version 1.9.0 beats 1.8.0 in 87% of games on a game clock (+333 Elo, 2000 games at 3 s + 50 ms per game, no time forfeits)
  and in 86% at a fixed 50 ms per move (+315 Elo, 2000 games). Three changes, in order of size: the lazy-evaluation margins
  (`PatternMargin`, new `PairMargin`) are 128 instead of 32 (+274 Elo; with the pair table in, the small margin skipped
  corrections that decide cutoffs); the pair table was retrained on three rounds of self-play by the corrected engine (about +100
  Elo, 46M positions) and now includes a phase-indexed mobility term (+28 to +35 Elo); and both table sums are computed a rank
  at a time in byte lanes (identical values, 38% more nodes per second at equal margins, +35 Elo).
- Version 1.8.0 beats 1.7.0 in 72.5% of games on a game clock (+168 Elo, 2000 games at 3 s + 50 ms per game; +196 over 400
  games with the full log kept), and is level with it at a fixed 50 ms per move (+3.5 +/- 20, 1200 games). Two changes to
  clock play: 1.7.0 rebuilt its evaluation tables for every search, so a `go` took 90-170 ms more than asked (13% of its games
  in the 400-game run were lost on time, none of 1.8.0's), and the time manager now estimates its moves left from the empty
  squares instead of assuming 30 (`TimeModel`, +50 to +73 Elo on its own, measured with the table already cached: +66 and
  +50 at 3 s + 50 ms, +73 sudden death at 1.5 s). Fixed `movetime` play is unchanged.
- Version 1.7.0 beats 1.6.0 in 84% of games (+290 Elo, 2000 games at 50 ms per move, one thread each). It comes from
  four changes found by studying a much stronger engine: a trained pair-pattern evaluation (`PairBlend`, about +150), a graded
  futility reduction (`FutilityReduction`, about +125), internal iterative reduction (`IirMinDepth`, about +65) and using the
  best move of an interrupted iteration (`PartialIterations`, about +15); the gains overlap, so they do not add up to +290.
  With `Threads` it is stronger still: against itself on one thread at equal time per move, 4 threads scored about +105 Elo
  and 8 threads about +139 (measured on the 1.5.0 search).
- Against the open-source C++ engine [TikTaxx](https://github.com/kz04px/tiktaxx), version 1.10.0 wins about 92% of games
  (+418 Elo, 400 games, 100 ms per move); 1.9.0, 1.8.0 and 1.7.0 scored +418, +413 and +418, 1.6.0 +397, 1.5.0 +342 and 1.1.0
  +165. This match is too lopsided to show small differences.
- Against the open-source C++ engine [Funes](https://github.com/Tempate/Funes), version 1.10.0 wins about 91% of games
  (+393 Elo over 1200 games, +413 over 400, 100 ms per move); 1.9.0 scored +390 and +413, 1.8.0 +311 and +312, 1.7.0 +309,
  1.6.0 +29 (level), 1.5.0 -26 to -50, 1.3.0 -158 and 1.1.0 -291.
- Against the open-source Nim engine [Moonbird](https://github.com/tsoj/Moonbird), version 1.10.0 wins about 71% of games
  (+158 Elo over 1200 games, 100 ms per move, one thread each) against a recent build (`d6a6107`, which respects `movetime` and
  accepts move lists). Moonbird was by far the strongest opponent before: 1.9.0 scored +60, 1.8.0 -252, 1.7.0 -267 against the
  1.0.0 release and 1.6.0 -353. Moonbird reaches depth 12-17 in 100 ms with fewer, heavier nodes.
- Version 1.6.0 beats 1.5.0 in 61% of games (+75.5 Elo, 2000 games at 50 ms per move): a dominated-jump cut in the main
  search (`JumpCut`) and two refits of the built-in pattern table.
- Version 1.5.0 beats 1.4.0 in 67% of games (+124.5 Elo, 1600 games at 50 ms per move), almost all of it from cutting
  dominated jumps in quiescence search (see `QuiesceJumpCut` below).
- Version 1.4.0 beats 1.3.0 by about +38 Elo (888-712, 1600 games at 50 ms per move): the same search, about 43% faster
  per node.
- Version 1.3.0 beats 1.2.0 in 59-61% of games (+46 to +76 Elo on three seeds, 800 games each at 50 ms per move). The
  gain comes from a learned table of local board patterns (see `PatternBlend` below).
- Version 1.2.0 beats 1.1.0 in 66% of games (+117 Elo, 800 games at 50 ms per move).
- For 1.1.0, TikTaxx needed roughly 7-8 times gataxx's thinking time per move to score level (not re-measured since).

These are comparisons against a few engines, not ratings.

## Build and run

```
mvn clean verify
java -jar target/gataxx.jar
```

The engine reads commands on stdin and writes replies on stdout. It also works piped:

```
printf 'position startpos\ngo depth 6\n' | java -jar target/gataxx.jar
```

## Supported UAI commands

| Command | Notes |
| --- | --- |
| `uai` | Replies `id name`, `id author`, `uaiok`. |
| `isready` | Replies `readyok` immediately, even mid-search. |
| `uainewgame` | Resets to the start position. |
| `position startpos\|fen <fen> [moves ...]` | Moves are `c3` (clone), `a1c3` (jump), `0000` (pass). Illegal input leaves the position unchanged. |
| `go` | `depth`, `nodes`, `movetime`, `wtime`/`btime`, `winc`/`binc`, `movestogo`, `infinite`. Plain `go` means `infinite`. |
| `stop` | Ends the search and prints `bestmove`. |
| `quit` | Exits. |
| `setoption name Hash value <MB>` | Transposition table size in MB (default 16, `0` disables it). The table is cleared on `uainewgame`. Unknown options are ignored with an `info string`. |
| `setoption name Tempo value <n>` | Bonus (in pieces) for the side to move in the static evaluation (default 2). |
| `setoption name QuiesceMinCaptures value <n>` | Quiescence search extends moves converting at least this many pieces (default 3; `0` turns quiescence off). |
| `setoption name QuiesceMaxPly value <n>` | Most extra plies quiescence may search (default 5, max 16). Odd values played best: 5 beat 3, 4, 6 and 7. |
| `setoption name QuiesceJumpCut value 0\|1\|2` | Which jumps quiescence tries. `1` (default) leaves out jumps to squares a clone could also reach, which convert the same pieces and leave the origin empty: 2.8x fewer nodes at depth 9 and +88 Elo. `2` cuts them only when the origin stone cannot be attacked (almost nothing); `0` keeps all. |
| `setoption name JumpCut value 0\|1\|2\|3` | The same idea in the main search, where cutting every such jump loses 75 Elo (`1`): a jump can save an attackable stone that a clone leaves behind. `3` (default) cuts only those that convert nothing (about +20 to +35 Elo); `0` keeps all. `JumpCutMinEmpties` (default 12) keeps every jump in the endgame; `JumpCutNear` and `JumpCutNearDepth` apply a different mode within a few plies of the leaves (off by default: it saves nodes but no strength). |
| `setoption name EvalSafe value <n>` | Weight of safe pieces (no empty neighbour) in 1/16 piece (default 4). `EvalEdge` (pieces on the outer ring, default 8), `EvalReach` (pieces no enemy can threaten next move, default 4), `EvalTerritory`, `EvalMobility` and `EvalExposure` (default 0) are also available. `EvalCohesion` (default -3: penalises adjacent own pieces, favouring spread-out groups) and `EvalThreat` (default 0) are in 1/64 piece, the others in 1/16. `EvalFade` (default 0, off) scales the positional weights down as the board fills; tested, no gain. |
| `d` | Prints the board and FEN. |
| `perft <n>` | Per-move node counts, for debugging. |
| `bench [depth]` | Searches 16 fixed positions to a fixed depth (default 6) and prints nodes, time, nps and a score checksum, for comparing search changes exactly. |
| `setoption name Pvs value 0\|1` | Principal variation search (default 1). |
| `setoption name Lmr value 0\|1` | Late-move reductions (default 1) with `LmrMoves` (3), `LmrMinDepth` (4) and `LmrDeepMoves` (6: moves ranked this late are reduced by two plies). |
| `setoption name Threads value <n>` | Search threads (default 1, max 64): Lazy SMP, where helper threads search the same position over a shared lock-free transposition table, skipping different iterations. The search is not deterministic with more than one thread. Against 1 thread at equal time per move (50 ms): 2 threads about +30 Elo, 4 threads about +105, 8 threads about +125. |
| `setoption name PairBlend value <n>` | Percentage (default 100, `0` = off) of the built-in pair-pattern correction added to the evaluation: for 80 placements of two 2x2 blocks (side by side, above each other, or touching at a corner) the exact contents of the eight squares select one of 65,536 learned weights, tied under the board's symmetries and trained by `tools.PairFit` on self-play outcomes and scores (about +150 Elo). `PairMargin` (default 128, in 1/16 piece) skips the correction when the plain score is that far outside the search window. `PairFile` loads another weight file instead of the built-in one. |
| `setoption name PairPlacements value <letters>` | Which placements of the pair table are summed: `h` (two blocks side by side), `v` (above each other), `d` and `a` (touching at a corner, the two diagonals); default `hvda` = all 80. A shorter set is faster per node but needs a table fitted for it (`tools.PairFit --placements`); the built-in table is for all 80, and every subset tried lost strength at equal time. |
| `setoption name FutilityReduction value <n>` | Graded futility reduction (default 24, `0` = off): from the second move on, a move whose child scores below alpha is searched `(alpha - score) / n` plies shallower (skipped if its depth runs out; re-searched at full depth if it beats alpha). About +125 Elo; 12 to 35 is a plateau. |
| `setoption name IirMinDepth value <n>` | Internal iterative reduction (default 3, `0` = off): a node with at least this much depth left and no table move is searched one ply shallower (about +65 Elo). |
| `setoption name TimeModel value 0\|1` | Clock play (`wtime`/`btime`): `1` (default) estimates our moves left from the empty squares, so the late game gets a larger share of the clock; `0` assumes a fixed 30 (about -50 Elo at 3 s + 50 ms per game, -70 sudden death). `TimeSoft` (percent of the per-move budget after which no new iteration starts, default 40) and `TimeHard` (hard limit in tenths of the budget, default 30) tune it further; raising `TimeSoft` to 100 loses. |
| `setoption name PartialIterations value 0\|1` | When time runs out mid-iteration, play the best move that iteration had completed (default 1). |
| `setoption name PatternBlend value <n>` | Percentage (default 0 = off since the pair table was retrained to replace it; 65 was the default before) of a built-in learned local-environment table mixed into the evaluation: a weight per square, by its state, its square type and its friendly/enemy neighbour counts. `PatternMargin` and `PairMargin` (default 128, in 1/16 piece) skip the table when the plain score is that far outside the search window (small margins such as 32 cost 200+ Elo once the pair table is in); `PatternFile` loads another table written by `tools.PatternFit` instead of the built-in one. |
| `setoption name NullMove value 0\|1`, `Futility` | Null-move pruning and futility pruning, with their parameters (`NullR`, `NullMinEmpties`, `FutilityMargin`, `FutilityDepth`). Both default to 0: neither helped in tests (the graded `FutilityReduction` above is a different technique and does). |

`x` moves first and is "black" (`btime`/`binc`); `o` is "white" (`wtime`/`winc`). This matches TikTaxx.
The engine replies `info depth .. score cp .. nodes .. time .. nps .. pv ..` after each
completed search depth (including `hashfull`, the table fill in permille), then `bestmove <move>`. A pass is `bestmove 0000`.

## Measuring strength: the match harness

`spamalot.gataxx.tools.Match` plays two UAI engines against each other and reports the score,
an Elo difference with a 95% margin, and the likelihood of superiority (LOS). Each random
opening is played twice with colours swapped, so a result is not skewed by who moves first.

To test a change, keep a copy of the last good jar as a baseline and play the new build against it:

```
cp target/gataxx.jar /tmp/gataxx-baseline.jar     # before changing anything
# ...make the change...
mvn clean package
java -cp target/gataxx.jar spamalot.gataxx.tools.Match \
    --engine1 "java -jar target/gataxx.jar" \
    --engine2 "java -jar /tmp/gataxx-baseline.jar" \
    --movetime 100 --games 200 --concurrency 4
```

Use `--depth N` (or `--go1 "depth 5" --go2 "depth 3"`) for deterministic fixed-depth comparisons,
and `--out games.txt` to save every game. Run `Match --help` for all options. With few games the
margin is wide: treat a result as meaningful only when the interval excludes 0. Engines that
crash, time out or play an illegal move forfeit that game (`--grace MS` widens the timeout; `--fen-only1`/`--fen-only2` send an engine bare FENs for engines that cannot parse move lists). Keep `--concurrency` below your
core count when using `--movetime`, or CPU contention will skew the results.

## Layout

- `board`: bitboard `Position`, moves, FEN, perft
- `eval`: `Evaluator`, the feature evaluator, the learned local-environment table (`LocalPatterns`, `PatternEvaluator`, resource `pattern-table.txt`) and the trained pair-pattern correction (`PairPatterns`, `PairEvaluator`, resource `pair-patterns.bin`)
- `search`: `Searcher` (NegaMax/alpha-beta, iterative deepening), limits, time management
- `uai`: the protocol front end
- `tools`: the self-play match harness (`Match`, `UaiClient`, `Elo`) and the training pipeline (`GenData` generates self-play positions, `PairFit` trains the pair patterns, `Teacher`, `Distill` and `PatternFit` fit the local-environment table to another engine's scores)
- `scripts`: `outside_engines.sh` plays the standard matches against TikTaxx, Funes and Moonbird

## License

[MIT](LICENSE).

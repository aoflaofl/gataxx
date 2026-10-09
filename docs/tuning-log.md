# Tuning log

Strength experiments, all played with `spamalot.gataxx.tools.Match` (paired random 6-ply openings, colours
swapped). "nodes" = fixed `go nodes 100000` per move, which is deterministic and independent of CPU load;
"timed" = `go movetime 50`. Elo margins are 95%. Results are relative to an earlier version of this
engine, not to any outside engine.

## Search

| Change | Test | Result |
|---|---|---|
| Transposition table (16 MB) | depth 9 from the start position | ~4x fewer nodes, ~2x faster, same scores |
| Transposition table (16 MB) | timed, 1100 games vs no table | no measurable difference (545-555) |
| One extra ply of depth | depth 6 vs 5, 400 games | +160 +/- 38 |
| Quiescence alone (stand-pat on raw material) | nodes, 400 games each | -50 to -80 Elo (worse) |
| Tempo bonus alone | nodes | no effect (a constant at fixed depth) |
| Quiescence + tempo 2 pieces, min captures 3, max ply 4 | nodes, 800 games, fresh seed | +107 +/- 25 |
| Same | timed, 800 games | +77 +/- 25 |

Other quiescence settings (min captures 2-5, max ply 2-4, tempo 1-2) gave +14 to +62. Stand-pat only works
once the static score credits the side to move with its free clone (tempo).

## Evaluation (weights in 1/16 piece)

| Change | Test | Result |
|---|---|---|
| Safe pieces (no empty neighbour), weight 4 | nodes, 800 games | +89 +/- 25 |
| Same | timed, 800 games | +101 +/- 25 |
| Safe weight 2, 3, 5, 6 | nodes, 400 games | +62 to +78 (broad optimum); 16 gives only +9 |
| Mobility (clone targets) at +-1, 2, 4, 8 | nodes | zero or harmful |
| Exposure (piece/empty adjacencies) -1 | nodes, 1200 + 1600 games | +27 +/- 20 then +13 +/- 17 (not adopted) |
| Exposure -2, +1, +2.. | nodes | clearly worse |
| Edge pieces (outer ring), weight 1 / 2 / 4 | nodes, 400 games | +56 / +76 / +110 |
| Edge weight 6 / 8 / 12 / 16 | nodes, 400 games | +184 / +173 / +196 / +96 |
| Edge weight 6 / 8 / 12 | nodes, 800 games, fresh seed | +184 / +190 / +136 (+/- 28) |
| Edge weight 8 | timed, 800 games | **+229 +/- 30** (adopted) |
| Reach (pieces no enemy can threaten next move) 2 / 4 / 8 / 12 / 16 | nodes, 400 games | +31 / +35 / +44 / +14 / -31; adds nothing once edge is in |
| Territory (squares only one side can reach) 1 / 2 / 4 | nodes, 400 games | -12 / -4 / -3 (nothing) |
| Wrong-sign runs (reach -4, edge -2, safe -8) | nodes | all strongly negative, as a sanity check |
| Tempo 24 / 40 (default 32) | nodes, 1200 games | -34 +/- 20 / -11 +/- 20 |

## Re-tuning around the edge baseline

| Change (vs edge 8 + safe 4) | Test | Result |
|---|---|---|
| Safe 0 / 2 / 8 | nodes, 400 games | -78 / -56 / -17 (4 stays) |
| Tempo 24 / 40 | nodes, 400 games | -17 / +17 (flat) |
| Quiescence min captures 2 / 4 | nodes, 400 games | -5 / -85 (3 stays) |
| Quiescence max ply 2 / 6 | nodes, 400 games | -24 / -92 (4 stays) |
| Edge 6 / 10 | nodes, 400 games | -35 / +38; edge 10 then +20 +/- 20 over 1200 games (not adopted) |
| Reach 4 | nodes, 400 games, then 1200 games fresh seed | +31, then +41 +/- 20 |
| Reach 4 | timed, 1000 games | **+46 +/- 22** (adopted) |

## Cumulative

Current defaults vs the engine at commit `4b066fb` (no table, material-only evaluation, no quiescence), timed
50 ms/move, 400 games: **346-54, +323 +/- 50 Elo**. This is still only self-play against older versions.

## Against an outside engine: TikTaxx

[TikTaxx](https://github.com/kz04px/tiktaxx) (C++, MIT, alpha-beta with null-move, LMR, futility pruning, killer
moves and a transposition table), built from source at commit `95dffff`. libataxx was compiled directly with
g++ (`-std=c++20 -O3 -march=native -DNDEBUG`, the 13 sources listed in `libs/libataxx/src/CMakeLists.txt`,
archived with `ar` to `libs/libataxx/build/src/libataxx_static.a`) because CMake was not installed, then `make`.
Run as a UAI subprocess by `Match`: `--engine2 path/to/tiktaxx`. Defaults used (Hash 128 MB, 1 thread).

Fairness checks: with `go movetime N` it uses the full N ms (measured 50/100/200 ms wall-clock), and with
`go btime/wtime` it uses time/30 of the clock of the side to move, with x on `btime`. That confirms the
colour convention this engine assumes.

| Match (100 ms/move, 1 thread each, 6-ply random openings) | Result |
|---|---|
| gataxx (current defaults, Hash 128) vs TikTaxx, 800 games | **527-1-272, +115 +/- 25 Elo** |
| TikTaxx vs the original Phase 4 engine (no table, material only), 300 games | 265-35, +352 +/- 61 |
| gataxx vs the same original engine, 50 ms/move, 400 games | 346-54, +323 +/- 50 |

Time-control sweep vs TikTaxx (same settings; ours Hash 128, 1 thread each, 6-ply random openings; TikTaxx rebuilt
with CMake-built libataxx at the same pinned commits):

| Time per move | Games | Score (gataxx first) | Elo vs TikTaxx (95%) |
|---|---|---|---|
| 25 ms | 1000 | 660-0-340 | +115 +/- 23 |
| 100 ms | 800 | 527-1-272 | +115 +/- 25 |
| 200 ms | 400 | 275-1-124 | +138 +/- 37 |
| 400 ms | 300 | 192-0-108 | +100 +/- 41 |

The lead is stable from 25 to 400 ms (all four within each other's error), so it does not come from a particular
time control. The chain through the original engine (below) had suggested near parity; the four direct matches
agree with each other, so the direct result is the better estimate.

Re-measured after the cohesion, edge-weight and reach work (current defaults; same TikTaxx build and settings):

| Time per move | Games | Score (gataxx first) | Elo vs TikTaxx (95%) | Earlier engine |
|---|---|---|---|---|
| 25 ms | 1000 | 711-0-289 | **+156 +/- 24** | +115 +/- 23 |
| 100 ms | 800 | 590-0-210 | **+180 +/- 27** | +115 +/- 25 |

The lead over TikTaxx grew by roughly 40-65 Elo (the differences are about 1-2 sigma each), consistent with
the cohesion gain measured in self-play, so the improvements carry over to an outside engine.

Re-measured with the faster search of 1.1.0 (PVS, direct quiescence move generation, edge-aware ordering; same
TikTaxx build and settings, same seeds as the rows above):

| Time per move | Games | Score (gataxx first) | Elo vs TikTaxx (95%) | 1.0.0 |
|---|---|---|---|---|
| 25 ms | 1000 | 715-0-285 | **+160 +/- 24** | +156 +/- 24 |
| 100 ms | 800 | 576-2-222 | **+165 +/- 27** | +180 +/- 27 |

The lead over TikTaxx did not change within error (+4 and -15), although 1.1.0 is about twice as fast and beats
1.0.0 by +43 +/- 24 Elo in self-play. Possible reasons: the earlier 1.0.0 numbers or the self-play gain were partly
luck (the combined difference is under two standard errors), or part of a self-play gain does not transfer to a
different opponent (self-play can overstate improvements that exploit shared weaknesses). Treat the outside-engine
numbers as the more trustworthy yardstick, and the speedup as real in nodes and time (`bench`) but modest in
strength.

Time-handicap ladder (gataxx 1.1.0 always at 25 ms/move; TikTaxx given a multiple of that; 6-ply random openings,
one thread each, Hash 128 for gataxx):

| TikTaxx time | Ratio | Games | Score (gataxx first) | Elo (95%) |
|---|---|---|---|---|
| 25 ms | 1x | 1000 | 715-0-285 | +160 +/- 24 (row above) |
| 50 ms | 2x | 400 | 251-0-149 | +91 +/- 35 |
| 100 ms | 4x | 400 | 255-0-145 | +98 +/- 35 |
| 200 ms | 8x | 300 | 146-0-154 | -9 +/- 39 |
| 400 ms | 16x | 200 | 78-0-122 | -78 +/- 49 |

A least-squares line through the five points falls about 58 Elo per doubling of TikTaxx's time and crosses zero at
about 7.5x. In other words TikTaxx needs roughly seven to eight times gataxx's thinking time per move to match it
in this setup (both engines search at similar node rates, so this is a difference in quality per node, not raw speed).

The last two imply roughly equal strength to TikTaxx, while the direct match says +115. The difference is
within the combined error (about 1.7 sigma), but different time controls were used, so treat the direct
match as the better estimate and the gap as uncertain. This compares one engine build, one time control and
one opening set; it is not a rating.

## Where do we lose? (games vs TikTaxx, 100 ms/move, 400 games: 274-126)

`scripts/analyze_regret.py` replays games and asks the engine (depth 7) how good each position was before and
after every move; regret = how much worse the played move is than the engine's best. `recheck_regret.py` repeats
the flagged moves at depth 11. The oracle is our own engine, so it shares our evaluation's blind spots, and it
makes the other engine look worse by construction; use it for our own mistakes, not for comparing engines.

- About 40 of the 126 losses were decided by the random opening within a few moves; 83 games were analysed.
- Flagged blunders (>= 3 pieces at depth 7) are concentrated with fewer than 15 empty squares (5% of moves vs
  0.1-0.2% earlier), but only 30% of them (23 of 77) survive a depth-11 check; the rest were oracle noise.
- Confirmed blunders: 23 in 20 of 83 lost games (24%) vs 9 in 9 of 83 won games (11%); 14.7 vs 4.5 per 1000
  endgame moves. 19 of 23 occurred with 8 or fewer empty squares and in 21 of 23 we were already behind.
- So endgame blunders explain at most a quarter of the non-trivial losses; 76% of lost games have no confirmed
  blunder and are decided by something earlier or more strategic that this tool cannot see.
- Hypothesis: positional terms mislead in the endgame, where only the final count matters. Re-searching the 23
  positions without positional terms avoided 2-3 more blunders (not significant). The match test (`EvalFade`:
  scale positional weights by min(empties, F)/F) refuted it: F = 6 / 10 / 15 / 25 gave -17 / -10 / -26 / -85
  Elo (+/- 34, 400 games, nodes). `EvalFade` stays in the code, default 0 (off).

## More evaluation knowledge (all vs the then-default, nodes unless stated)

| Feature (weight unit) | Weights tried | Result (Elo, +/- 34 at 400 games) |
|---|---|---|
| Corner pieces (1/16) | 4 / 8 / -4 | +5 / -5 / -45: nothing (edge covers it) |
| Second-ring pieces (1/16) | 2 / 4 / -2 | -35 / -45 / +13: nothing |
| Threat: own pieces beside squares the enemy can land on (1/16) | -1 / -2 / +1 | +47, then +15 +/- 24 on a fresh seed / -4 / -74: weak at best, dropped |
| **Cohesion**: adjacent own-piece pairs (1/16 then 1/64) | +1 / +2 | -129 / -233 (rewarding compact groups is bad) |
| Cohesion, negative weights in 1/16 | -1 / -2 | +65 (then +62 +/- 25 on a fresh seed) / -221 +/- 29: very narrow optimum |
| Cohesion in 1/64 piece | -1 / -2 / -3 / -4 / -5 / -6 | +9 / +56 / +54 / +65 / +44 / -26: plateau -2..-5, cliff beyond |
| Cohesion -3 vs -4, fresh seed, 800 games | | +64 +/- 25 / +62 +/- 25 |
| Cohesion -3, timed 50 ms, 800 games | | **+97 +/- 25** (adopted, weight -3, the middle of the plateau) |

Penalising adjacency between a side's own pieces (so groups stay spread out, with more empty squares to clone
into and fewer pieces exposed along one front) is the third large gain after edge and safe pieces. The wrong
sign is strongly negative, and the gain disappears abruptly past about -5 (and is -221 at -8), so the weight must
stay in the middle of the plateau.

## Re-check of the other settings after cohesion (vs the new default, nodes, 400 games, +/- 34)

Edge 6 / 10 / 12: +12 / +2 / -40. Safe 2 / 6: -30 / -7. Reach 0 / 8: -76 / +5. Tempo 24 / 40: -38 / +19.
Quiescence min captures 2: +14; quiescence max ply 6: -108. Nothing beat the current values, so the defaults
stay: safe 4, edge 8, reach 4, cohesion -3, tempo 32, quiescence 3 captures / 4 plies.

## Search speed and efficiency (bench: `bench [depth]`, 16 fixed positions; scoresum must not change)

Baseline at depth 6: 62.1M nodes, 6.6 s (9.4M nps), scoresum 420; at depth 7: 210.7M nodes, 20.3 s, scoresum 740.

| Change | Result |
|---|---|
| Profile (JFR) of the baseline | Quiescence was 46% self time (80% inclusive): it generated all moves and then filtered |
| Generate only moves converting >= minCaptures directly (capture count depends only on the destination) | identical nodes and scores; +37% nps (9.4M -> 12.9M) |
| Killer moves (2 per ply) above the material-swing ordering | nodes **+103%** (126M): worse |
| Killer bonus sweep with PVS (512 .. 4096) | best 47.07M vs 47.17M without killers: no gain |
| History heuristic as a tie-breaker | nodes +5%: worse |
| PVS (zero-width window after the first move) | nodes -24% (47.2M), time -23% |
| Edge destination tie-breaker in ordering (bonus 1536 of a 1024 step; plateau 1280-1792 at depth 7) | nodes a further -13%; 1536 chosen |
| Penalty for jumping off the edge / for many own neighbours at the destination | worse (45-48M and ~42.7M vs 41.7M) |
| **All together, depth 6** | **38.8M nodes (-38%), 3.0 s (-54%)**, scoresum 420 |
| **All together, depth 7** | **139.1M nodes (-34%), 9.9 s (-51%)**, scoresum 740 |
| New build vs the released 1.0.0, timed 50 ms/move, 800 games | **449-1-350, +43 +/- 24 Elo** |

Killers and history are not in the code. PVS and the ordering change were verified to return exactly the scores of the
plain search (against unpruned NegaMax at depth <= 5, with and without the table and quiescence, and over 80 random
positions).

## A second outside engine: GoTaxx

[GoTaxx](https://github.com/cpirc/gotaxx) (Go, MIT, 2019, about 1800 lines, standard library only), built with
`GOPROXY=off go build -o gotaxx ./engine` at commit `31c303d`. It speaks UAI but only understands `go movetime`
(depth, nodes and clock times are ignored) and uses exactly the movetime given. Its `main` and its UAI loop each
create a buffered reader on stdin, so piping all commands at once loses them; a GUI or `Match`, which wait for
`uaiok`, are fine. The 20-game smoke test and all 1100 games ended normally (no illegal moves or timeouts).

| Match (1 thread each, 6-ply random openings) | Score (first engine) | Elo (95%) |
|---|---|---|
| gataxx 1.1.0 vs GoTaxx, 100 ms, 400 games (Hash 128) | 366-0-34 (91.5%) | +413 +/- 61 |
| gataxx 1.1.0 vs GoTaxx, 25 ms, 400 games | 362-0-38 (90.5%) | +392 +/- 58 |
| TikTaxx vs GoTaxx, 100 ms, 300 games | 277-0-23 (92.3%) | +432 +/- 74 |

Both stronger engines beat GoTaxx by about 90%+, so the scores are saturated: the Elo formula and the error bars are
unreliable at that end, and the matches cannot separate gataxx from TikTaxx (the direct matches above can). GoTaxx
is therefore not a useful yardstick; it only confirms that gataxx plays legal, sound Ataxx against a second
independent implementation. A better second yardstick would be a stronger engine, or the same engine at a time
handicap (for example TikTaxx given several times our time per move).

## Fitting features to game outcomes (`tools.Fit`)

`Fit` replays games saved by `Match --out`, samples positions (optionally only "quiet" ones, where the side to move
has no move converting 3 or more pieces), and fits a logistic regression of the eventual result on raw feature
differences (`FeatureEvaluator.rawFeatures`), reporting coefficients, z-scores and held-out log-loss (split by game).
Data: 6000 self-play games at 20000 nodes/move (3000 distinct games; 19,649 quiet / 200,899 total positions).

Pitfalls found: (1) two identical deterministic engines play the same game from both colours, so identical games
must be deduplicated or the copies straddle the train/held-out split; (2) material, edge, corner, cohesion and
exposure are exactly linearly dependent (8*material - 3*edge - 2*corner = cohesion + exposure, as the enemy-contact
terms cancel in a difference), so the full feature set cannot be fitted without dropping one (exposure).

| Result | Value |
|---|---|
| Held-out log-loss: no information / material only / current five features / fitted five | 0.693 / 0.643 / 0.610 (one-parameter scaling) / 0.592 (all positions) |
| Fitted weights for the five current features (all positions; 1/16 piece, cohesion 1/64) | safe 17, reach 4.3, edge 8.3, cohesion -4.8, tempo 3.6 pieces |
| Match-tuned weights | safe 4, reach 4, edge 8, cohesion -3, tempo 2 pieces |
| Adding any one candidate (mobility, territory, corner, ring1, threat, dense, bites3, bitesSq) to the five | held-out loss improves by at most 0.0012 (the five themselves bring 0.05): none carries real extra signal |
| Removing reach, or cohesion, from the model | held-out loss does not worsen, yet in matches reach off costs -76 Elo and cohesion adds +64: these features help move choice, not outcome prediction |
| Playing the regression's weights: safe 12 / safe 17 / safe 12 + tempo 48 + cohesion -5 | -47 / -94 / -70 Elo (+/- 34, 400 games, nodes) |

Edge, reach and cohesion came out within about 1 sigma of the independently match-tuned values, which supports both
methods. But outcome fits over-weight tactical features (safe pieces) that the search already sees, so match tuning
remains the arbiter and the fit is a filter for candidates, not a source of playing weights. No new feature
earned a place in the evaluation.

Tempo scan (units of 1/16 piece; default 32; Elo vs default, nodes, 400 games +/- 34 unless noted): 24: -38, 40: +9
and +19, 48: +28, 56: +7, 64: -49, 72: -45; fresh seed, 800 games (+/- 24): 44: +16, 48: +10. A plateau from about 32 to 56
with at most a slight edge at 40-48, never significant; tempo stays at 32.

## A stronger outside engine: Funes

[Funes](https://github.com/Tempate/Funes) (C++, GPL-3.0, last commit 2021-01-11, 2671 lines), built at commit `dd319f1` with
`mkdir bin && cd bin && cmake -DCMAKE_BUILD_TYPE=Release .. && make` (-O3 -flto -march=native). Before building I read its
CMake file and `main.cpp`, scanned the source for network/process/file-writing calls (only a read of an openings file in
a tuner that is compiled out), and read its UAI command and `go` parsing. I did not read its search or evaluation code:
it is GPL-3.0 and this project is MIT, so nothing may be copied, and the comparison below is from the outside.

Behaviour: speaks UAI; uses exactly the `movetime` given (51/101/201 ms for 50/100/200); `go depth N` is never
answered; its search never returns for some positions (reproduced: after `b6` then `g7e5` from the start position),
which `Match` would score as a timeout loss for Funes. It did not happen in any of the 440 games played.

| Match (gataxx 1.1.0 vs Funes, 100 ms/move, 6-ply random openings, one thread each) | Result |
|---|---|
| 40 games (smoke test) | 10-30 |
| 400 games, all ended normally (0 timeouts, median 92 plies) | **63-337 (15.8%), -291 +/- 47 Elo** |

Funes is the strongest outside engine found so far, and it beats gataxx clearly (gataxx beats TikTaxx by +160).

Search depth reached in 100 ms (last completed iteration, from each engine's `info` lines; nodes in thousands):

| Position | Funes depth / nodes | gataxx depth / nodes |
|---|---|---|
| start | 15 / 433 | 7 / 436 |
| ply 16 | 13 / 340 | 5 / 425 |
| ply 26 | 11 / 248 | 5 / 365 |
| ply 40 | 12 / 266 | 4 / 933 |

Node rates are similar (5-8M/s Funes, 5-10M/s gataxx), so the gap is not raw speed or search size but how fast the tree
grows with depth: Funes needs about 2x the nodes per extra ply, gataxx 6-10x. That points at selective search
(pruning and reductions) rather than evaluation as the main difference. Funes' own `patches/*.txt` notes (public
results of its author's tests) report large gains for border/corner bonuses, a second-player bonus and an endgame
reduction, which is consistent with the edge and tempo findings here.

## Selective search: null-move pruning

Added as options `NullMove` (default 0), `NullR` (reduction, default 2) and `NullMinEmpties` (default 12). At a
zero-window node whose static score reaches beta (not after a pass, not in a mate-score window, mover not stuck,
at least `NullMinEmpties` empty squares) the side to move passes and the opponent's reply is searched to depth
`d - 1 - R`; a score >= beta prunes the node. It needs PVS to have any effect. Zugzwang should be rare in Ataxx because
the mover can nearly always clone for a gain, so the technique is plausible, but it is not exact (the exactness tests
do not apply; `NullMoveTest` checks the contract instead).

| Test | Result |
|---|---|
| bench depth 7, nodes (off 139.1M, scoresum 740) for R = 1 / 2 / 3 / 4 / 5 | 170.7M / 116.4M / **103.2M (-26%)** / 130.6M / 128.4M; scoresum 740 in all |
| `NullMinEmpties` 0 / 6 / 12 / 20 / 30 at R=3 | 103.2M / 103.2M / 103.2M / 109.0M / 113.6M nodes |
| 80 random positions, material eval, R=3: same score as plain | depth 5: 71 of 75 (95%), nodes -77%; depth 6: 46 of 69 (67%), same best move 52 of 69, nodes -41% |
| Matches vs the current engine, 100k nodes/move, 400 games (+/- 34): R=2 / R=3 / R=3 + 20-empties guard / R=4 | -12 / +10 / 0 / -12 |

Null-move pruning saves up to a quarter of the nodes but shows no measurable gain in play (a quarter of the nodes is worth
roughly +15 Elo, below what 400 games resolve), so it stays an option, off by default. It is clearly not the explanation
for the gap to Funes.

## Selective search: late-move reductions (LMR)

Options `Lmr` (default **1**), `LmrMoves` (default 3) and `LmrMinDepth` (default 4). In a zero-window node, moves the
ordering ranks `LmrMoves` or later are searched one ply shallower when the remaining depth is at least `LmrMinDepth`;
a reduced move that still beats alpha is searched again at full depth. Needs PVS. Not exact: the `bench` score checksum
changes (depth 6: 420 -> 445, depth 7: 740 -> 729), which is expected.

| Test | Result |
|---|---|
| bench depth 7 nodes (off: 139.1M, scoresum 740), full-depth moves / min depth: 4 / 3, 2 / 3, 6 / 3, 3 / 4 | 37.4M / 19.8M / 33.4M / 39.0M; scoresum 638 / 598 / 679 / 729 |
| Depth reached in 100 ms (4 positions), default vs LMR 4 / 3 vs LMR 2 / 3 | 7,5,5,3 vs 8,7,6,3 vs 9,7,7,4: only +1 to +2 plies |
| 12 bench positions, engine configuration: LMR vs plain at depth 5 / 6 | same score 10 of 12 / 8 of 12; nodes 40% / 28% of plain |
| Matches vs the current engine, 100k nodes/move, 400 games: full-depth moves 4 / 2 / 6 / (3, min depth 4) | +16 / +16 / +5 / +24 (+/- 34) |
| Timed, 50 ms/move, 800 games, fresh seed: (3, min depth 4) / 2 full-depth moves | **+27 +/- 24** (LOS 98.6%) / **+32 +/- 24** (LOS 99.6%) |

Adopted as the default with the milder setting (3 full-depth moves, min depth 4). The benchmark now takes 22.6M nodes
and 1.9 s at depth 6, 39.0M nodes and 3.1 s at depth 7 (reference checksums 445 and 729). A search of a given nominal
depth is much cheaper but reduced plies are worth less than full ones, so the strength gain (+27 to +32 Elo) is about
that of one to one and a half plies, not of the node savings.

## Selective search: two-ply reductions and futility pruning

Option `LmrDeepMoves` (default **6**): moves ranked this late or later, at remaining depth >= `LmrMinDepth` + 1, are reduced
by two plies instead of one (0 = off). Option `Futility` (default 0) with `FutilityMargin` (48) and `FutilityDepth` (2): a
move in a zero-window node at shallow depth is skipped if static score + the most the move can win (1 per clone + 2 per
converted piece, times 16) + margin x depth cannot reach alpha.

| Test | Result |
|---|---|
| bench depth 7 nodes, LMR default (39.0M, scoresum 729) vs `LmrDeepMoves` 4 / 6 / 8 / 12 | 16.2M / 17.7M / 18.9M / 21.4M; scoresum 755 / 751 / 751 / 739 |
| bench depth 7: futility margin 48 depth <= 1 / <= 2; margin 96 / 160 (depth <= 2) | 36.0M / 34.8M / 36.9M / 38.2M: only -8% to -11% |
| bench depth 7: null-move R=3 on top of LMR | 38.8M (vs 39.0M): redundant with the reductions |
| Matches vs the previous default, 100k nodes/move, 400 games (+/- 34): `LmrDeepMoves` 4 / 6 / 8 / 12; futility | +67 / +53 / +54 / +49; futility +49 (likely luck, see below) |
| Timed 50 ms, 800 games, fresh seed (+/- 25): `LmrDeepMoves` 6 / 4 | **+100** / +74 |
| Timed 50 ms, 800 games: futility alone / `LmrDeepMoves` 6 + futility | +18 (not significant) / +96 (no better than 6 alone) |

Adopted `LmrDeepMoves=6`; futility stays an option (off). The first futility reading (+49) shrank to +18 on a fresh seed,
a reminder that several settings tried in one batch will produce lucky outliers. Bench with the new defaults:
depth 6: 8.3M nodes, 0.74 s (scoresum 442); depth 7: 17.7M nodes, 1.44 s (751); depth 8: 60.5M nodes, 4.69 s (421).
For comparison the original search took 62.1M nodes / 6.6 s at depth 6 and 210.7M nodes / 20.3 s at depth 7.

## Release 1.2.0

1.2.0 vs the released 1.1.0 (rebuilt from tag `v1.1.0`), timed 50 ms/move, 800 games, 6-ply random openings, one thread each:
**530-0-270 (66.3%), +117 +/- 26 Elo**. Bench depth 7: 139.1M nodes / 9.9 s (1.1.0) against 17.7M nodes / 1.4 s (1.2.0).

## Outside engines after the search work (current main, after 1.1.0)

Same setup as before (100 ms/move, one thread each, Hash 128 for gataxx, 6-ply random openings, 400 games, same seeds):

| Opponent | 1.1.0 | Current main (late-move reductions incl. two-ply, PVS, direct quiescence moves) |
|---|---|---|
| Funes | 63-337, -291 +/- 47 | **97-303, -198 +/- 40** (+93 Elo closer) |
| TikTaxx | +165 +/- 27 (800 games) | **331-69 (82.8%), +272 +/- 45** (+107 Elo) |

Both independent opponents show about +100 Elo from the search changes made since 1.1.0, matching the self-play gain of
the two-ply reductions (+100 +/- 25 timed). Funes remains clearly stronger.

## Not yet measured

- Strength gain per extra ply near depth 8.
- A time-handicap ladder against Funes (how much extra time gataxx needs), and the effect of selective search
  (null-move pruning, late-move reductions, futility pruning) on the depth reached.

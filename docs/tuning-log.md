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

## Not yet measured

- Strength gain per extra ply near depth 8.
- Other outside engines (GoTaxx, Funes, ...), other TikTaxx settings, and TikTaxx with more than 400 ms/move.

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

## Not yet measured

- Strength gain per extra ply near depth 8.
- Anything against an engine other than earlier versions of this one.

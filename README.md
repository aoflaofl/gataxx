# gataxx

An [Ataxx](https://en.wikipedia.org/wiki/Ataxx) game-playing engine written in Java 21.
It runs at the command line (no GUI) and speaks the Universal Ataxx Interface (UAI),
a UCI-style protocol. Moves are found with an iterative-deepening NegaMax search with
alpha-beta pruning.

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
| `setoption name QuiesceMaxPly value <n>` | Most extra plies quiescence may search (default 4, max 16). |
| `setoption name EvalSafe value <n>` | Weight of safe pieces (no empty neighbour) in 1/16 piece (default 4). `EvalEdge` (pieces on the outer ring, default 8), `EvalReach` (pieces no enemy can threaten next move, default 4), `EvalTerritory`, `EvalMobility` and `EvalExposure` (default 0) are also available. `EvalCohesion` (default -3: penalises adjacent own pieces, favouring spread-out groups) and `EvalThreat` (default 0) are in 1/64 piece, the others in 1/16. `EvalFade` (default 0, off) scales the positional weights down as the board fills; tested, no gain. |
| `d` | Prints the board and FEN. |
| `perft <n>` | Per-move node counts, for debugging. |

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
crash, time out or play an illegal move forfeit that game. Keep `--concurrency` below your
core count when using `--movetime`, or CPU contention will skew the results.

## Layout

- `board`: bitboard `Position`, moves, FEN, perft
- `eval`: `Evaluator` and the material evaluator
- `search`: `Searcher` (NegaMax/alpha-beta, iterative deepening), limits, time management
- `uai`: the protocol front end
- `tools`: the self-play match harness (`Match`, `UaiClient`, `Elo`)

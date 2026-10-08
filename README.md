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
| `setoption` | Accepted and ignored (no options yet). |
| `d` | Prints the board and FEN. |
| `perft <n>` | Per-move node counts, for debugging. |

`x` moves first and is "black" (`btime`/`binc`); `o` is "white" (`wtime`/`winc`).
The engine replies `info depth .. score cp .. nodes .. time .. nps .. pv ..` after each
completed search depth, then `bestmove <move>`. A pass is `bestmove 0000`.

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

#!/usr/bin/env python3
"""Regret analysis of saved Match games (python3, drives the engine jar over UAI).

Usage: analyze_regret.py ENGINE.jar GAMES.txt OUT.json [DEPTH=7] [lost|won|all] [MAX_GAMES]
GAMES.txt is a file written by `Match --out`; "lost"/"won" are from engine1's point of view.
Follow with recheck_regret.py to confirm flagged moves at a greater depth.

For every move in selected games, ask our engine (fixed depth) for the value of the position before
the move (mover's view) and after the move (opponent's view). regret = before + after: how much worse the
played move is than the engine's best move, in centipieces (100 = one piece).
"""
import subprocess, sys, json, collections
from concurrent.futures import ProcessPoolExecutor

JAR = sys.argv[1]
GAMES = sys.argv[2]
OUT = sys.argv[3]
DEPTH = int(sys.argv[4]) if len(sys.argv) > 4 else 7
WHICH = sys.argv[5] if len(sys.argv) > 5 else "lost"   # lost | all
WORKERS = 8


class Engine:
    def __init__(self):
        self.p = subprocess.Popen(["java", "-jar", JAR], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  text=True, bufsize=1)
        self.send("uai")
        self.until("uaiok")
        self.send("setoption name Hash value 64")

    def send(self, s):
        self.p.stdin.write(s + "\n")
        self.p.stdin.flush()

    def until(self, prefix):
        while True:
            line = self.p.stdout.readline()
            if not line:
                raise RuntimeError("engine died")
            if line.startswith(prefix):
                return line

    def search(self, start_fen, moves, depth):
        """Returns (score_cp or None if game over, bestmove)."""
        self.send("position fen %s%s" % (start_fen, (" moves " + " ".join(moves)) if moves else ""))
        self.send("go depth %d" % depth)
        score = None
        while True:
            line = self.p.stdout.readline()
            if line.startswith("info depth"):
                t = line.split()
                score = int(t[t.index("cp") + 1])
            elif line.startswith("bestmove"):
                return score, line.split()[1]

    def close(self):
        try:
            self.send("quit")
            self.p.wait(timeout=5)
        except Exception:
            self.p.kill()


def parse_fen(fen):
    rows = fen.split()[0].split("/")
    grid = [[None] * 7 for _ in range(7)]
    for i, row in enumerate(rows):
        r = 6 - i
        f = 0
        for c in row:
            if c.isdigit():
                for _ in range(int(c)):
                    grid[r][f] = "."
                    f += 1
            else:
                grid[r][f] = c
                f += 1
    return grid, fen.split()[1]


def sq(s):
    return int(s[1]) - 1, ord(s[0]) - ord("a")


def apply(grid, side, move):
    """Applies a UAI move in place; returns (kind, captures)."""
    if move == "0000":
        return "pass", 0
    if len(move) == 2:
        r, f = sq(move)
        kind = "clone"
    else:
        r0, f0 = sq(move[:2])
        grid[r0][f0] = "."
        r, f = sq(move[2:])
        kind = "jump"
    grid[r][f] = side
    other = "o" if side == "x" else "x"
    caps = 0
    for dr in (-1, 0, 1):
        for df in (-1, 0, 1):
            rr, ff = r + dr, f + df
            if 0 <= rr < 7 and 0 <= ff < 7 and grid[rr][ff] == other:
                grid[rr][ff] = side
                caps += 1
    return kind, caps


def count(grid, ch):
    return sum(row.count(ch) for row in grid)


def kind_and_caps(grid, side, move):
    g = [row[:] for row in grid]
    return apply(g, side, move)


def analyze_game(task):
    gid, line = task
    start_fen, e1, result, reason, moves_s = [x.strip() for x in line.split("|")]
    moves = moves_s.split()
    e1_is_x = e1 == "engine1=x"
    eng = Engine()
    out = []
    try:
        grid, side = parse_fen(start_fen)
        for i, m in enumerate(moves):
            mover_is_e1 = (side == "x") == e1_is_x
            before, best = eng.search(start_fen, moves[:i], DEPTH)
            if before is not None and abs(before) < 9000:
                after, _ = eng.search(start_fen, moves[:i + 1], DEPTH - 1)
                if after is not None and abs(after) < 9000:
                    kind, caps = kind_and_caps(grid, side, m)
                    bkind, bcaps = kind_and_caps(grid, side, best) if best != "0000" else ("pass", 0)
                    other = "o" if side == "x" else "x"
                    out.append(dict(game=gid, ply=i, e1=mover_is_e1, side=side, move=m, best=best,
                                    before=before, after=after, regret=before + after,
                                    kind=kind, caps=caps, bkind=bkind, bcaps=bcaps,
                                    empties=sum(r.count(".") for r in grid),
                                    mine=count(grid, side), theirs=count(grid, other),
                                    fen=None))
            apply(grid, side, m) if m != "0000" else None
            side = "o" if side == "x" else "x"
    finally:
        eng.close()
    return out


def main():
    tasks = []
    for gid, line in enumerate(open(GAMES).read().splitlines()):
        parts = [x.strip() for x in line.split("|")]
        if len(parts) < 5 or parts[3] != "game over":
            continue
        e1_is_x = parts[1] == "engine1=x"
        x_won = parts[2] == "1-0"
        e1_won = (x_won == e1_is_x) and parts[2] != "1/2-1/2"
        if WHICH == "all" or (WHICH == "won" and e1_won) or (WHICH == "lost" and not e1_won):
            if len(parts[4].split()) >= 12:
                tasks.append((gid, line))
    if len(sys.argv) > 6:
        tasks = tasks[:int(sys.argv[6])]
    print("analyzing %d games at depth %d" % (len(tasks), DEPTH), flush=True)
    allrows = []
    with ProcessPoolExecutor(WORKERS) as ex:
        for n, rows in enumerate(ex.map(analyze_game, tasks)):
            allrows.extend(rows)
            if (n + 1) % 20 == 0:
                print("  %d/%d games" % (n + 1, len(tasks)), flush=True)
    json.dump(allrows, open(OUT, "w"))
    print("wrote %d move records to %s" % (len(allrows), OUT))


if __name__ == "__main__":
    main()

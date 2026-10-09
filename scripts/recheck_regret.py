"""Re-searches the moves analyze_regret.py flagged (regret >= 300) at a greater depth, to separate real
blunders from shallow-oracle noise.

Usage: recheck_regret.py ENGINE.jar GAMES.txt ANALYSIS.json DEPTH(11)
"""
import json, sys, subprocess, collections, statistics as st
from concurrent.futures import ProcessPoolExecutor
JAR, GAMES, LOST, DEPTH = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
THRESH = 300
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



def work(item):
    gid, recs = item
    line = open(GAMES).read().splitlines()[gid]
    parts = [x.strip() for x in line.split("|")]
    start_fen, moves = parts[0], parts[4].split()
    eng = Engine()
    out = []
    try:
        for r in recs:
            i = r["ply"]
            before, best = eng.search(start_fen, moves[:i], DEPTH)
            after, _ = eng.search(start_fen, moves[:i + 1], DEPTH - 1)
            if before is None or after is None:
                continue
            r2 = dict(r); r2["regret_deep"] = before + after; r2["best_deep"] = best
            out.append(r2)
    finally:
        eng.close()
    return out

if __name__ == "__main__":
    rows = json.load(open(LOST))
    flagged = collections.defaultdict(list)
    for r in rows:
        if r["e1"] and r["regret"] >= THRESH:
            flagged[r["game"]].append(r)
    items = list(flagged.items())
    print("rechecking %d flagged moves in %d games at depth %d" % (sum(len(v) for v in flagged.values()), len(items), DEPTH), flush=True)
    res = []
    with ProcessPoolExecutor(8) as ex:
        for out in ex.map(work, items):
            res.extend(out)
    json.dump(res, open(LOST.replace(".json", "_deep.json"), "w"))
    n = len(res)
    print("rechecked", n)
    for t in (150, 300):
        print("  still regret >= %dcp at depth %d: %d (%.0f%%)" % (t, DEPTH, sum(1 for r in res if r["regret_deep"] >= t), 100*sum(1 for r in res if r["regret_deep"] >= t)/n))
    print("  same best move as depth 7: %d (%.0f%%)" % (sum(1 for r in res if r["best_deep"] == r["best"]), 100*sum(1 for r in res if r["best_deep"] == r["best"])/n))
    print("  mean depth-7 regret %.0f -> deep regret %.0f" % (st.mean(r["regret"] for r in res), st.mean(r["regret_deep"] for r in res)))
    print("  played move == deep best: %d" % sum(1 for r in res if r["move"] == r["best_deep"]))

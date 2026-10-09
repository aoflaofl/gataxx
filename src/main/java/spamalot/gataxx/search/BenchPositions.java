package spamalot.gataxx.search;

/**
 * Fixed mid-game positions (from seeded random games, early to late) for the {@code bench} command, so that
 * node counts and speeds can be compared exactly between versions of the search.
 */
public final class BenchPositions {
    public static final String[] FENS = {
        "1x5/2x2o1/5o1/7/7/1o5/o5x x 2 4",
        "x5o/7/7/4x2/o6/6x/oo4x x 0 6",
        "2x4/7/7/3o3/o1o4/2oox1x/o1o3x x 0 8",
        "x5o/1x5/6o/1x2o1o/x4o1/7/2o4 x 0 10",
        "7/3o3/3x3/x1xx3/2x1oo1/1xxx3/7 x 1 12",
        "2x4/1x5/1o2o2/2o3x/5x1/1oo3x/6o x 1 14",
        "x1x2o1/1x2o2/6o/x1x2oo/x6/4x2/x3x1x x 5 16",
        "4x2/3x3/7/1x2ooo/3o1o1/xo3o1/2o3x x 2 18",
        "3xx2/x6/1xx4/3oooo/2oooo1/7/7 x 6 20",
        "2xx3/1x5/x1xo1ox/2x1o2/1o5/3oooo/2o1o1o x 4 22",
        "oox4/o1o4/4xxx/7/3xx2/o2x2x/o2x2x x 0 24",
        "o6/2x3x/1o1o2x/o2o3/1oo4/o2x2x/3x1xx x 2 26",
        "4o1x/1o1o1xx/3o2x/2o4/1oo2o1/o4oo/ooo4 x 3 29",
        "o2oo2/2o2oo/3oxo1/oo1o3/1x5/x1x3x/2xxx1x x 0 32",
        "4xo1/ooxx2o/1o1xx2/o1x3o/o2o1oo/6o/o2oo2 x 7 36",
        "1x1xo2/1xxxxxx/ox1x1xx/3x1x1/oo2xx1/oooxxxo/1oox2o x 8 40",
    };

    private BenchPositions() {}
}

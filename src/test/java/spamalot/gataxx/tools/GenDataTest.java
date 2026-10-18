package spamalot.gataxx.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenDataTest {
    @TempDir
    Path dir;

    private byte[] generate(String name, int threads) throws Exception {
        Path out = dir.resolve(name);
        GenData.main(new String[] {"--out", out.toString(), "--games", "24", "--nodes", "300", "--threads",
            String.valueOf(threads), "--seed", "5", "--record", "features", "--pattern", "off", "--pair", "builtin",
            "--pair-blend", "1.0", "--hash", "1"});
        return Files.readAllBytes(out);
    }

    @Test
    void aSeedGivesTheSameFileForAnyNumberOfThreads() throws Exception {
        byte[] one = generate("one.bin", 1);
        byte[] four = generate("four.bin", 4);
        assertTrue(one.length > 0 && one.length % GenData.RECORD_BYTES == 0);
        assertEquals(one.length, four.length);
        assertArrayEquals(one, four);
        assertArrayEquals(one, generate("again.bin", 1));
    }

    @Test
    void differentSeedsGiveDifferentGames() throws Exception {
        byte[] a = generate("a.bin", 2);
        Path out = dir.resolve("b.bin");
        GenData.main(new String[] {"--out", out.toString(), "--games", "24", "--nodes", "300", "--threads", "2", "--seed", "6",
            "--record", "features", "--pattern", "off", "--pair", "builtin", "--pair-blend", "1.0", "--hash", "1"});
        org.junit.jupiter.api.Assertions.assertFalse(java.util.Arrays.equals(a, Files.readAllBytes(out)));
    }
}

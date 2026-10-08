package spamalot.gataxx;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class MainTest {
    @Test
    void versionIsNotEmpty() {
        assertFalse(Main.version().isBlank());
    }
}

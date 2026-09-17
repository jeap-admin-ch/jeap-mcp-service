package ch.admin.bit.jeap.mcp.docs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LogSanitizerTest {

    @Test
    void leavesOrdinaryTextUnchanged() {
        assertEquals("jeap-messaging/docs/outbox.md", LogSanitizer.forLog("jeap-messaging/docs/outbox.md"));
    }

    @Test
    void neutralizesCrLf() {
        String sanitized = LogSanitizer.forLog("evil\r\nWARN  forged log line");

        assertFalse(sanitized.contains("\r"));
        assertFalse(sanitized.contains("\n"));
        assertEquals("evil??WARN  forged log line", sanitized);
    }

    @Test
    void neutralizesUnicodeLineSeparatorsNotCoveredByPCntrlAlone() {
        String sanitized = LogSanitizer.forLog("evil  forged");

        assertEquals("evil???forged", sanitized);
    }
}

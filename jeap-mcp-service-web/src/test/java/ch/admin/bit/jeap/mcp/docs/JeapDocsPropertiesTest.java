package ch.admin.bit.jeap.mcp.docs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The count-based limit policy — the single source of truth shared by the upstream over-fetch and the
 * reshape top-K. Pinned directly here so the boundary behaviour cannot drift.
 */
class JeapDocsPropertiesTest {

    // maxDocs = 5
    private final JeapDocsProperties props = TestDocsProperties.defaults();

    @Test
    void clampFallsBackToMaxDocsForNullOrNonPositive() {
        assertEquals(5, props.clamp(null), "null -> maxDocs");
        assertEquals(5, props.clamp(0), "0 -> maxDocs (a request for <1 doc is meaningless)");
        assertEquals(5, props.clamp(-3), "negative -> maxDocs");
    }

    @Test
    void clampHonoursRequestUpToMaxDocs() {
        assertEquals(1, props.clamp(1));
        assertEquals(4, props.clamp(4));
        assertEquals(5, props.clamp(5));
    }

    @Test
    void clampCapsAtMaxDocs() {
        assertEquals(5, props.clamp(6));
        assertEquals(5, props.clamp(1000), "an over-large request is capped at maxDocs");
    }

    @Test
    void overFetchIsClampedTimesThree() {
        assertEquals(15, props.overFetch(null), "maxDocs(5) * OVER_FETCH(3)");
        assertEquals(15, props.overFetch(0), "non-positive clamps to maxDocs first, then over-fetches");
        assertEquals(6, props.overFetch(2), "clamp(2) * 3");
        assertEquals(15, props.overFetch(1000), "clamp(1000)=5, then * 3");
    }

    @Test
    void clampRespectsACustomMaxDocs() {
        JeapDocsProperties capped = TestDocsProperties.withDocsRootAndMaxDocs("/jeap/src", 2);
        assertEquals(2, capped.clamp(1000));
        assertEquals(6, capped.overFetch(1000), "clamp(1000)=2, then * 3");
    }
}

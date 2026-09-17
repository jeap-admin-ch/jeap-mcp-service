package ch.admin.bit.jeap.mcp.tool;

import ch.admin.bit.jeap.mcp.metrics.McpMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The server-side ceiling on client-supplied {@code limit}/{@code depth} arguments - the single
 * safeguard against an anonymous caller forcing an unbounded upstream {@code project-rag} query.
 * Pinned directly here so the boundary behaviour cannot drift.
 */
class JeapRagPropertiesTest {

    private static final String SOME_TOOL = "jeap_find_code_examples";

    // maxLimit = 25, maxDepth = 5
    private final JeapRagProperties props = new JeapRagProperties(25, 5, 2000, 20);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final McpMetrics metrics = new McpMetrics(meterRegistry);

    @Test
    void clampLimitMapsNullOrNonPositiveToNull() {
        // null (not the non-positive value itself) is what reaches Args#put, which skips null -
        // so the key is omitted and upstream applies its own default. Forwarding 0/-3 unchanged
        // would let a caller bypass the cap entirely if upstream treats non-positive as "unlimited".
        assertNull(props.clampLimit(null, SOME_TOOL, metrics), "null -> left for upstream's own default");
        assertNull(props.clampLimit(0, SOME_TOOL, metrics), "non-positive must not reach upstream unchanged");
        assertNull(props.clampLimit(-3, SOME_TOOL, metrics));
    }

    @Test
    void clampLimitHonoursRequestUpToMax() {
        assertEquals(1, props.clampLimit(1, SOME_TOOL, metrics));
        assertEquals(24, props.clampLimit(24, SOME_TOOL, metrics));
        assertEquals(25, props.clampLimit(25, SOME_TOOL, metrics));
    }

    @Test
    void clampLimitCapsAtMaxLimit() {
        assertEquals(25, props.clampLimit(26, SOME_TOOL, metrics));
        assertEquals(25, props.clampLimit(1_000_000, SOME_TOOL, metrics), "an over-large request is capped at maxLimit");
    }

    @Test
    void clampDepthMapsNullOrNonPositiveToNull() {
        assertNull(props.clampDepth(null, SOME_TOOL, metrics), "null -> left for upstream's own default");
        assertNull(props.clampDepth(0, SOME_TOOL, metrics), "non-positive must not reach upstream unchanged");
        assertNull(props.clampDepth(-1, SOME_TOOL, metrics));
    }

    @Test
    void clampDepthHonoursRequestUpToMax() {
        assertEquals(1, props.clampDepth(1, SOME_TOOL, metrics));
        assertEquals(4, props.clampDepth(4, SOME_TOOL, metrics));
        assertEquals(5, props.clampDepth(5, SOME_TOOL, metrics));
    }

    @Test
    void clampDepthCapsAtMaxDepth() {
        assertEquals(5, props.clampDepth(6, SOME_TOOL, metrics));
        assertEquals(5, props.clampDepth(1000, SOME_TOOL, metrics), "an over-large request is capped at maxDepth");
    }

    @Test
    void respectsCustomLimitsAndDepth() {
        JeapRagProperties tight = new JeapRagProperties(2, 1, 2000, 20);
        assertEquals(2, tight.clampLimit(1000, SOME_TOOL, metrics));
        assertEquals(1, tight.clampDepth(1000, SOME_TOOL, metrics));
    }

    @Test
    void clampLimitRecordsArgumentsClampedMetricOnlyWhenActuallyCapped() {
        props.clampLimit(25, SOME_TOOL, metrics); // at the max, not clamped
        props.clampLimit(1000, SOME_TOOL, metrics); // over the max, clamped

        io.micrometer.core.instrument.Counter counter = meterRegistry.find(McpMetrics.ARGUMENTS_CLAMPED_COUNTER)
                .tag(McpMetrics.TOOL_TAG, SOME_TOOL)
                .tag(McpMetrics.ARGUMENT_TAG, "limit")
                .counter();
        assertEquals(1.0, counter.count(), "only the over-the-max call should count as clamped");
    }

    @Test
    void clampDepthRecordsArgumentsClampedMetricOnlyWhenActuallyCapped() {
        props.clampDepth(5, SOME_TOOL, metrics); // at the max, not clamped
        props.clampDepth(1000, SOME_TOOL, metrics); // over the max, clamped

        io.micrometer.core.instrument.Counter counter = meterRegistry.find(McpMetrics.ARGUMENTS_CLAMPED_COUNTER)
                .tag(McpMetrics.TOOL_TAG, SOME_TOOL)
                .tag(McpMetrics.ARGUMENT_TAG, "depth")
                .counter();
        assertEquals(1.0, counter.count(), "only the over-the-max call should count as clamped");
    }

    @Test
    void requireWithinLengthAcceptsNullAndShortValues() {
        props.requireWithinLength("query", null);
        props.requireWithinLength("query", "a".repeat(2000));
    }

    @Test
    void requireWithinLengthRejectsOverLongValues() {
        JeapToolValidationException ex = org.junit.jupiter.api.Assertions.assertThrows(
                JeapToolValidationException.class, () -> props.requireWithinLength("query", "a".repeat(2001)));
        assertEquals("Argument 'query' is too long (2001 characters, maximum 2000).", ex.getMessage());
    }

    @Test
    void requireWithinSizeAcceptsNullAndSmallLists() {
        props.requireWithinSize("languages", null);
        props.requireWithinSize("languages", java.util.Collections.nCopies(20, "java"));
    }

    @Test
    void requireWithinSizeRejectsOversizedLists() {
        JeapToolValidationException ex = org.junit.jupiter.api.Assertions.assertThrows(
                JeapToolValidationException.class,
                () -> props.requireWithinSize("languages", java.util.Collections.nCopies(21, "java")));
        assertEquals("Argument 'languages' has too many entries (21, maximum 20).", ex.getMessage());
    }

    @Test
    void requireWithinSizeRejectsAnOverLongSingleElement() {
        // Count alone isn't enough: one over-long element must not slip through just because the
        // list itself is small.
        java.util.List<String> withOneOverLongElement = java.util.List.of("java", "a".repeat(2001), "kotlin");

        JeapToolValidationException ex = org.junit.jupiter.api.Assertions.assertThrows(
                JeapToolValidationException.class,
                () -> props.requireWithinSize("languages", withOneOverLongElement));
        assertEquals("Argument 'languages[1]' is too long (2001 characters, maximum 2000).", ex.getMessage());
    }

    @Test
    void validateReturnsEmptyWhenChecksPass() {
        assertEquals(java.util.Optional.empty(),
                props.validate(SOME_TOOL, metrics, () -> props.requireWithinLength("query", "ok")));
    }

    @Test
    void validateReturnsExceptionMessageWhenACheckThrows() {
        assertEquals(
                java.util.Optional.of("Argument 'query' is too long (2001 characters, maximum 2000)."),
                props.validate(SOME_TOOL, metrics, () -> props.requireWithinLength("query", "a".repeat(2001))));
    }

    @Test
    void validateStopsAtTheFirstFailingCheck() {
        // the second check would also fail, but must never even run - validate() short-circuits like
        // the try/catch it replaces in JeapRagTools.
        java.util.Optional<String> result = props.validate(SOME_TOOL, metrics, () -> {
            props.requireWithinLength("query", "a".repeat(2001));
            props.requireWithinLength("project", "b".repeat(2001));
        });
        assertEquals(java.util.Optional.of("Argument 'query' is too long (2001 characters, maximum 2000)."), result);
    }

    @Test
    void validateRecordsArgumentsRejectedMetricOnFailure() {
        props.validate(SOME_TOOL, metrics, () -> props.requireWithinLength("query", "a".repeat(2001)));

        io.micrometer.core.instrument.Counter counter = meterRegistry.find(McpMetrics.ARGUMENTS_REJECTED_COUNTER)
                .tag(McpMetrics.TOOL_TAG, SOME_TOOL)
                .tag(McpMetrics.ARGUMENT_TAG, "query")
                .counter();
        assertEquals(1.0, counter.count());
    }

    @Test
    void validateRecordsNoMetricWhenChecksPass() {
        props.validate(SOME_TOOL, metrics, () -> props.requireWithinLength("query", "ok"));

        io.micrometer.core.instrument.Counter counter = meterRegistry.find(McpMetrics.ARGUMENTS_REJECTED_COUNTER).counter();
        assertNull(counter);
    }
}

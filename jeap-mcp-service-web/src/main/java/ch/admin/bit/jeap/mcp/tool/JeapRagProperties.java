package ch.admin.bit.jeap.mcp.tool;

import ch.admin.bit.jeap.mcp.metrics.McpMetrics;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Optional;

/**
 * Configuration for {@link JeapRagTools}' code-search tools ({@code jeap.mcp.rag}). Bound as a
 * record so it is hand-constructable in unit tests without a Spring context.
 * Registered as a bean via {@code @ConfigurationPropertiesScan} on {@code Application}.
 *
 * <p>The {@code jeap_*} tools are reachable anonymously (see {@code McpSecurityConfig}), so a
 * caller-supplied {@code limit}/{@code depth} must never be forwarded to the upstream
 * {@code project-rag} process unbounded: an oversized value is a cheap way to force an expensive
 * query against the shared sidecar. {@link #clampLimit(Integer, String, McpMetrics)} /
 * {@link #clampDepth(Integer, String, McpMetrics)} enforce a server-side ceiling regardless of
 * what a client requests (recording {@code jeap.mcp.tool.arguments.clamped} whenever a request
 * actually exceeded it); a missing/non-positive value still falls through to the upstream tool's
 * own default (i.e. is left absent).
 *
 * <p>Free-text ({@code query}, {@code path}, {@code file_path}, {@code project}) and list
 * ({@code file_extensions}, {@code languages}, {@code path_patterns}) arguments are, unlike
 * {@code limit}/{@code depth}, <em>rejected</em> rather than silently truncated when oversized
 * ({@link #requireWithinLength(String, String)} / {@link #requireWithinSize(String, List)}):
 * silently cutting a query or path would change its meaning rather than merely capping cost, so a
 * clear tool error is preferable to a confusing partial match.
 *
 * @param maxLimit     upper bound for any {@code limit} argument (findCodeExamples, findReferences, searchByFilters)
 * @param maxDepth     upper bound for {@code get_call_graph}'s {@code depth} argument
 * @param maxTextLength upper bound (in characters) for any single free-text argument
 * @param maxListSize   upper bound for the number of items in any list argument
 */
@ConfigurationProperties("jeap.mcp.rag")
public record JeapRagProperties(
        @DefaultValue("25") int maxLimit,
        @DefaultValue("5") int maxDepth,
        @DefaultValue("2000") int maxTextLength,
        @DefaultValue("20") int maxListSize) {

    /**
     * {@code null} or non-positive maps to {@code null} (so {@code Args#put} omits the key and
     * upstream applies its own default); otherwise capped at {@link #maxLimit}, recording
     * {@code jeap.mcp.tool.arguments.clamped} when {@code requested} actually exceeded it.
     * <p>
     * Non-positive is mapped to {@code null} rather than forwarded as-is: {@code Args#put} only
     * skips {@code null}, so an unchanged {@code 0}/negative value would reach upstream verbatim -
     * and some upstream tools treat a non-positive limit as "unlimited", which would silently
     * bypass the cap this method exists to enforce.
     *
     * @param requested    the caller-supplied {@code limit}, or {@code null} if absent
     * @param jeapToolName the calling {@code jeap_*} tool's name, used to tag the clamp metric
     * @param metrics      where to record a clamp
     * @return the clamped value, or {@code null} if {@code requested} was {@code null}/non-positive
     */
    public Integer clampLimit(Integer requested, String jeapToolName, McpMetrics metrics) {
        if (requested == null || requested < 1) {
            return null;
        }
        if (requested > maxLimit) {
            metrics.recordArgumentClamped(jeapToolName, "limit");
            return maxLimit;
        }
        return requested;
    }

    /**
     * {@code null} or non-positive maps to {@code null} (so {@code Args#put} omits the key and
     * upstream applies its own default); otherwise capped at {@link #maxDepth}, recording
     * {@code jeap.mcp.tool.arguments.clamped} when {@code requested} actually exceeded it. See
     * {@link #clampLimit(Integer, String, McpMetrics)} for why non-positive is mapped to
     * {@code null} rather than forwarded unchanged.
     *
     * @param requested    the caller-supplied {@code depth}, or {@code null} if absent
     * @param jeapToolName the calling {@code jeap_*} tool's name, used to tag the clamp metric
     * @param metrics      where to record a clamp
     * @return the clamped value, or {@code null} if {@code requested} was {@code null}/non-positive
     */
    public Integer clampDepth(Integer requested, String jeapToolName, McpMetrics metrics) {
        if (requested == null || requested < 1) {
            return null;
        }
        if (requested > maxDepth) {
            metrics.recordArgumentClamped(jeapToolName, "depth");
            return maxDepth;
        }
        return requested;
    }

    /**
     * Reject an over-long free-text argument.
     *
     * @param fieldName the argument's name, used in the thrown exception's message
     * @param value     the value to check; {@code null} is always accepted (an absent optional argument)
     * @throws JeapToolValidationException if {@code value} is longer than {@link #maxTextLength}
     *                                      characters
     */
    public void requireWithinLength(String fieldName, String value) {
        if (value != null && value.length() > maxTextLength) {
            throw new JeapToolValidationException(fieldName,
                    "Argument '" + fieldName + "' is too long (" + value.length()
                            + " characters, maximum " + maxTextLength + ").");
        }
    }

    /**
     * Reject an oversized list argument, by element count and by each element's length.
     * <p>
     * Bounding the count alone still lets a single element carry an arbitrarily long string
     * through to {@code project-rag} - the same cost/abuse concern {@link #maxTextLength} closes
     * for {@code query}/{@code path}/{@code file_path}/{@code project} applies per-element here too.
     *
     * @param fieldName the argument's name, used in the thrown exception's message
     * @param values    the list to check; {@code null} is always accepted (an absent optional argument)
     * @throws JeapToolValidationException if {@code values} has more than {@link #maxListSize}
     *                                      elements, or any single element is longer than
     *                                      {@link #maxTextLength} characters
     */
    public void requireWithinSize(String fieldName, List<String> values) {
        if (values == null) {
            return;
        }
        if (values.size() > maxListSize) {
            throw new JeapToolValidationException(fieldName,
                    "Argument '" + fieldName + "' has too many entries (" + values.size()
                            + ", maximum " + maxListSize + ").");
        }
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (value != null && value.length() > maxTextLength) {
                throw new JeapToolValidationException(fieldName,
                        "Argument '" + fieldName + "[" + i + "]' is too long (" + value.length()
                                + " characters, maximum " + maxTextLength + ").");
            }
        }
    }

    /**
     * Run one or more {@code requireWithin*} checks and turn a {@link JeapToolValidationException}
     * into a returned error message instead of a thrown exception, so {@code @Tool} methods in
     * {@link JeapRagTools} can validate their arguments in one line:
     * <pre>{@code
     * return ragProperties.validate(TOOL_FIND_CODE_EXAMPLES, metrics, () -> {
     *     ragProperties.requireWithinLength("query", query);
     *     ragProperties.requireWithinLength("project", project);
     * }).orElseGet(() -> ...call upstream...);
     * }</pre>
     * A rejection also increments {@code jeap.mcp.tool.arguments.rejected} - see that metric's
     * javadoc in {@link McpMetrics} for why: the {@code jeap_*} tools are reachable anonymously, so
     * this is the primary signal for telling deliberate abuse of the argument limits apart from
     * normal traffic.
     *
     * @param jeapToolName the calling {@code jeap_*} tool's name, used to tag the rejection metric
     * @param metrics      where to record a rejection
     * @param checks       one or more {@code requireWithin*} calls to run
     * @return the exception's message if {@code checks} threw {@link JeapToolValidationException},
     *         otherwise {@link Optional#empty()}
     */
    public Optional<String> validate(String jeapToolName, McpMetrics metrics, Runnable checks) {
        try {
            checks.run();
            return Optional.empty();
        } catch (JeapToolValidationException e) {
            metrics.recordArgumentRejected(jeapToolName, e.fieldName());
            return Optional.of(e.getMessage());
        }
    }
}


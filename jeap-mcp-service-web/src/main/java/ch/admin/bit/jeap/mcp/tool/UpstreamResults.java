package ch.admin.bit.jeap.mcp.tool;

import org.springframework.ai.util.JacksonUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Utility class for parsing the upstream {@code project-rag} {@code query_codebase} response
 * shape ({@code {"results":[...]}}).
 */
final class UpstreamResults {

    private static final JsonMapper MAPPER = JacksonUtils.getDefaultJsonMapper();
    private static final String RESULTS = "results";

    private UpstreamResults() {
    }

    /**
     * The {@code results[]} chunks of an upstream response.
     *
     * <ul>
     *   <li>{@link Optional#empty()} — the body is not a JSON {@code results[]} response: either an
     *       intentional non-JSON upstream error string (upstream errors are non-JSON by design), or
     *       JSON without a {@code results[]} array.</li>
     *   <li>a present list (possibly empty) — the {@code results[]} chunks; empty means a well-formed
     *       response that simply returned zero hits.</li>
     * </ul>
     *
     * @throws JacksonException if the body <em>looks</em> like JSON (starts with <code>{</code> or
     *                          <code>[</code>) but cannot be parsed.
     */
    static Optional<List<JsonNode>> chunks(String json) {
        if (json == null) {
            return Optional.empty();
        }
        String trimmed = json.stripLeading();
        if (trimmed.isEmpty() || (trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[')) {
            return Optional.empty();
        }
        JsonNode results = MAPPER.readTree(trimmed).path(RESULTS);
        if (!results.isArray()) {
            return Optional.empty();
        }
        List<JsonNode> chunks = new ArrayList<>();
        results.forEach(chunks::add);
        return Optional.of(chunks);
    }
}

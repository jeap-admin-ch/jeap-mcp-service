package ch.admin.bit.jeap.mcp.tool;

/**
 * Signals a rejected {@code jeap_*} tool argument (e.g. an over-long query or an oversized filter
 * list). Its message must be clean and non-leaking so it can be returned straight to the agent as
 * the tool's result, matching {@code ch.admin.bit.jeap.mcp.docs.DocAccessException}'s convention.
 */
public class JeapToolValidationException extends RuntimeException {

    private final String fieldName;

    /**
     * @param fieldName the rejected argument's name (e.g. {@code "query"}, {@code "file_path"}),
     *                  used to tag {@code jeap.mcp.tool.arguments.rejected} when this is caught by
     *                  {@link JeapRagProperties#validate(String, ch.admin.bit.jeap.mcp.metrics.McpMetrics, Runnable)}
     * @param message   the clean, agent-safe error message describing the rejected argument
     */
    public JeapToolValidationException(String fieldName, String message) {
        super(message);
        this.fieldName = fieldName;
    }

    public String fieldName() {
        return fieldName;
    }
}

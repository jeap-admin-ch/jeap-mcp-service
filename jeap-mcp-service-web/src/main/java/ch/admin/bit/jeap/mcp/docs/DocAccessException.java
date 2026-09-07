package ch.admin.bit.jeap.mcp.docs;

/**
 * Signals a failed documentation fetch/read. Its message must be clean and non-leaking so it
 * can be returned straight to the agent.
 */
public class DocAccessException extends RuntimeException {

    public DocAccessException(String message) {
        super(message);
    }
}

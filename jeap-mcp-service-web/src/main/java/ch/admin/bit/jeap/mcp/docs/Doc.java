package ch.admin.bit.jeap.mcp.docs;

/**
 * A read documentation file: the {@link DocRef} it was read from and the full {@code content}.
 */
public record Doc(DocRef ref, String content) {
}

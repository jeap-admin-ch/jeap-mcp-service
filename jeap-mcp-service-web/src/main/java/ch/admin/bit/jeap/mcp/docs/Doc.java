package ch.admin.bit.jeap.mcp.docs;

/**
 * A read documentation file: the {@link DocRef} it was read from and the full {@code content}.
 *
 * @param ref     the reference the document was read from
 * @param content the full document content
 */
public record Doc(DocRef ref, String content) {
}

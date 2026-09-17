package ch.admin.bit.jeap.mcp.docs;

/**
 * A documentation reference split into its resolvable {@code path} and an optional {@code anchor}.
 * A trailing {@code #anchor} is kept (so callers can echo it back); a {@code ?query} is
 * dropped.
 *
 * @param path   the resolvable, repo-prefixed document path (without anchor/query)
 * @param anchor the trailing {@code #anchor} fragment, or {@code null} if absent
 */
public record DocRef(String path, String anchor) {

    /**
     * Parse a raw, repo-prefixed reference such as {@code jeap-messaging/docs/outbox.md#configuration}:
     * strip the {@code #anchor} (kept as {@link #anchor()}) and any {@code ?query}, leaving the
     * resolvable {@link #path()}.
     *
     * @throws DocAccessException if the reference is blank before or after stripping.
     */
    public static DocRef parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DocAccessException("Document path must not be empty.");
        }
        String ref = raw.strip();
        String anchor = null;
        int hash = ref.indexOf('#');
        if (hash >= 0) {
            anchor = emptyToNull(ref.substring(hash + 1).strip());
            ref = ref.substring(0, hash);
        }
        int query = ref.indexOf('?');
        if (query >= 0) {
            ref = ref.substring(0, query);
        }
        ref = ref.strip();
        if (ref.isBlank()) {
            throw new DocAccessException("Document path must not be empty.");
        }
        return new DocRef(ref, anchor);
    }

    private static String emptyToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}

package ch.admin.bit.jeap.mcp.docs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the jEAP documentation tools ({@code jeap.mcp.docs}). Bound as a record so it is
 * hand-constructable in unit tests ({@code new JeapDocsProperties(docsRoot, 5, 0.6, true)}) without a Spring
 * context. Registered as a bean via {@code @ConfigurationPropertiesScan} on {@code Application}.
 *
 * <p>Document size is intentionally <em>not</em> bounded here: {@code docs/**} files are kept small,
 * AI-readable and link-navigable by policy, so the only limit is on the <em>number</em> of documents
 * returned ({@link #maxDocs}). The {@link #clamp(Integer)} / {@link #overFetch(Integer)} helpers own that
 * single count policy so the upstream over-fetch and the reshape truncation can never drift apart.
 *
 * @param docsRoot filesystem root under which docs are read ({@code <repo>/docs/...} resolves here)
 * @param maxDocs  top-K distinct documents returned by {@code jeap_find_in_documentation}
 * @param minScore lower similarity floor for Markdown chunks (below {@code query_codebase}'s 0.7)
 * @param hybrid   hybrid (vector+BM25) vs pure-vector for the no-repo {@code query_codebase} path
 */
@ConfigurationProperties("jeap.mcp.docs")
public record JeapDocsProperties(
        @DefaultValue("/jeap/src") String docsRoot,
        @DefaultValue("5") int maxDocs,
        @DefaultValue("0.6") double minScore,
        @DefaultValue("true") boolean hybrid) {

    /** Over-fetch multiplier: upstream is queried for K*OVER_FETCH chunks so dedupe-to-K still yields K docs. */
    private static final int OVER_FETCH = 3;

    /**
     * The clamped target document count: {@code (requested == null || requested < 1) ? maxDocs :
     * min(requested, maxDocs)}. A caller-supplied {@code limit=1000} is capped to {@code maxDocs}.
     */
    public int clamp(Integer requested) {
        if (requested == null || requested < 1) {
            return maxDocs;
        }
        return Math.min(requested, maxDocs);
    }

    /** Clamp first, then over-fetch: caps the upstream query at {@code clamp(requested) * OVER_FETCH} candidates. */
    public int overFetch(Integer requested) {
        return clamp(requested) * OVER_FETCH;
    }
}

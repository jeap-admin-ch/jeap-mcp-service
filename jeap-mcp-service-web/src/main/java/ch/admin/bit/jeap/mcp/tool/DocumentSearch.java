package ch.admin.bit.jeap.mcp.tool;

import ch.admin.bit.jeap.mcp.docs.Doc;
import ch.admin.bit.jeap.mcp.docs.DocAccessException;
import ch.admin.bit.jeap.mcp.docs.DocRef;
import ch.admin.bit.jeap.mcp.docs.DocsReader;
import ch.admin.bit.jeap.mcp.docs.JeapDocsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns the {@code jeap_find_in_documentation} round-trip against the upstream {@code query_codebase}
 * tool. {@link JeapRagTools} supplies only transport; <em>all</em> documentation-search policy lives
 * here, split across two halves that must stay consistent:
 *
 * <ul>
 *   <li>{@link #requestArgs} — the outbound request: always scoped to the artificial {@code jeap-docs}
 *       project, with the docs-tuned min-score / hybrid and an <em>over-fetched</em> limit.</li>
 *   <li>{@link #assembleDocuments} — the inbound reshape of the upstream {@code results[]} into a JSON array of
 *       whole documents, truncated to the matching top-K.</li>
 * </ul>
 *
 * <p> Process: dedupe chunk hits by {@code file_path} (best upstream {@code score} wins), rank, take
 * the clamped top-K, and read each whole file via {@link DocsReader#find(String)}. The upstream fused
 * {@code score} is used for ranking only: under hybrid search it is an RRF rank artifact (&asymp; 1/60)
 * that carries no relevance meaning, so the {@code score} echoed per document is the winning chunk's
 * cosine {@code vector_score} when present. Every hit carries a
 * {@code snippet} — the start of the matching upstream chunk, showing where the document matched — plus
 * the whole-file {@code content}; a doc that cannot be read falls back to a {@code pointer} in place of
 * that content. A body that is not the expected {@code results[]} JSON is handed back unchanged. This
 * class never throws on a response it cannot process.
 */
@Slf4j
@Component
public class DocumentSearch {

    static final String DOCS_PROJECT = "jeap-docs";

    private static final int SNIPPET_MAX_CHARS = 250;
    private static final JsonMapper MAPPER = JacksonUtils.getDefaultJsonMapper();

    // keys of the upstream vocabulary to search for documents
    private static final String QUERY = "query";
    private static final String PROJECT = "project";
    private static final String MIN_SCORE = "min_score";
    private static final String LIMIT = "limit";
    private static final String HYBRID = "hybrid";
    private static final String FILE_PATH = "file_path";
    private static final String SCORE = "score";
    private static final String VECTOR_SCORE = "vector_score";
    private static final String CONTENT = "content";

    // keys of a result document object
    private static final String RESULT_PATH = "path";
    private static final String RESULT_SCORE = "score";
    private static final String RESULT_SNIPPET = "snippet";
    private static final String RESULT_CONTENT = "content";
    private static final String RESULT_POINTER = "pointer";

    private final DocsReader docsReader;
    private final JeapDocsProperties props;

    public DocumentSearch(DocsReader docsReader, JeapDocsProperties props) {
        this.docsReader = docsReader;
        this.props = props;
    }

    /**
     * The upstream {@code query_codebase} arguments for a documentation search: always scoped to the
     * artificial {@code jeap-docs} project, with the docs-tuned min-score / hybrid and an over-fetched
     * limit ({@code clamp(requested) * OVER_FETCH}) so dedupe-to-K still yields K distinct documents.
     */
    public Map<String, Object> requestArgs(String query, Integer requestedLimit) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(QUERY, query);
        args.put(PROJECT, DOCS_PROJECT);                    // ALWAYS jeap-docs
        args.put(MIN_SCORE, props.minScore());              // lower floor for Markdown chunks
        args.put(LIMIT, props.overFetch(requestedLimit));   // clamped (K * over-fetch)
        args.put(HYBRID, props.hybrid());
        return args;
    }

    /**
     * Reshape an upstream {@code results[]} response into a JSON array
     * {@code [{ path, score, snippet, content | pointer }]}, clamped to {@code clamp(requestedLimit)}
     * distinct documents. A body that is not a {@code results[]} response is returned unchanged.
     */
    public String assembleDocuments(String upstreamJson, Integer requestedLimit) {
        Optional<List<JsonNode>> chunks;
        try {
            chunks = UpstreamResults.chunks(upstreamJson);
        } catch (JacksonException e) {
            log.warn("Upstream body looked like JSON but did not parse, returning unchanged: {}",
                    e.toString());
            return upstreamJson;
        }
        if (chunks.isEmpty()) {
            return upstreamJson;   // not a results[] response (e.g. an upstream error string) -> unchanged
        }
        return toJson(rankAndLimitToTopK(dedupe(chunks.get()), props.clamp(requestedLimit)), upstreamJson);
    }

    /**
     * Collapse chunks to best-score-per-file hits, preserving first-seen order for equal-scored elements.
     * Selection uses the fused {@code score}; the hit also carries the winning chunk's client-visible
     * {@link #reportedScore(JsonNode, double) reported score}.
     */
    private static List<Hit> dedupe(List<JsonNode> chunks) {
        Map<String, Hit> bestHitForPathMap = new LinkedHashMap<>();
        for (JsonNode chunk : chunks) {
            String filePath = resolvablePath(chunk.path(FILE_PATH).asString(""));
            if (filePath == null) {
                continue;
            }
            double score = chunk.path(SCORE).asDouble(0.0);
            Hit existing = bestHitForPathMap.get(filePath);
            if (existing == null || score > existing.rankScore()) {
                bestHitForPathMap.put(filePath,
                        new Hit(filePath, score, reportedScore(chunk, score), extractSnippet(chunk)));
            }
        }
        return new ArrayList<>(bestHitForPathMap.values());
    }

    /** The client-visible score: the chunk's cosine {@code vector_score} when present, else the fused {@code score}. */
    private static double reportedScore(JsonNode chunk, double fusedScore) {
        return chunk.path(VECTOR_SCORE).asDouble(fusedScore);
    }

    /** The bare, resolvable path for an upstream {@code file_path}, or {@code null} if it is blank or resolves to nothing. */
    private static String resolvablePath(String rawFilePath) {
        if (rawFilePath.isBlank()) {
            return null;
        }
        try {
            return DocRef.parse(rawFilePath).path();
        } catch (DocAccessException _) {
            return null;
        }
    }

    private static List<Hit> rankAndLimitToTopK(List<Hit> hits, int k) {
        return hits.stream()
                .sorted(Comparator.comparingDouble(Hit::rankScore).reversed())
                .limit(k)
                .toList();
    }

    private String toJson(List<Hit> hits, String fallback) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Hit hit : hits) {
            out.add(toNode(hit));
        }
        try {
            return MAPPER.writeValueAsString(out);
        } catch (JacksonException e) {
            log.warn("failed to serialize docs response: {}", e.toString());
            return fallback;
        }
    }

    private Map<String, Object> toNode(Hit hit) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put(RESULT_PATH, hit.path());
        node.put(RESULT_SCORE, hit.reportedScore());
        if (hit.snippet() != null) {
            node.put(RESULT_SNIPPET, hit.snippet());
        }
        Optional<Doc> doc = docsReader.find(hit.path());
        if (doc.isPresent()) {
            node.put(RESULT_CONTENT, doc.get().content());
        } else {
            node.put(RESULT_POINTER, hit.path());   // unreadable -> pointer instead of content
        }
        return node;
    }

    private static String extractSnippet(JsonNode chunk) {
        String value = chunk.path(CONTENT).asString("");
        if (value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() <= SNIPPET_MAX_CHARS) {
            return trimmed;
        }
        // Don't cut through a surrogate pair: a supplementary-plane character (e.g. an emoji)
        // straddling the boundary would leave a lone high surrogate that Jackson emits as a
        // garbled '?'. Back the cut off by one char so the whole pair stays out.
        int end = Character.isHighSurrogate(trimmed.charAt(SNIPPET_MAX_CHARS - 1))
                ? SNIPPET_MAX_CHARS - 1
                : SNIPPET_MAX_CHARS;
        return trimmed.substring(0, end) + "…";
    }

    private record Hit(String path, double rankScore, double reportedScore, String snippet) {
    }
}

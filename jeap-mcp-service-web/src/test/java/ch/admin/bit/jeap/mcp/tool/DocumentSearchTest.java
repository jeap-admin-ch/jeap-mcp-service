package ch.admin.bit.jeap.mcp.tool;

import ch.admin.bit.jeap.mcp.docs.DocPathPolicy;
import ch.admin.bit.jeap.mcp.docs.DocsReader;
import ch.admin.bit.jeap.mcp.docs.JeapDocsProperties;
import ch.admin.bit.jeap.mcp.docs.TestDocsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.util.JacksonUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two halves of the documentation-search policy: the outbound {@code query_codebase} request
 * ({@link DocumentSearch#requestArgs}) and the inbound reshape of an upstream {@code results[]}
 * response into ranked, deduped whole documents ({@link DocumentSearch#assembleDocuments}).
 */
@SuppressWarnings("SameParameterValue")
class DocumentSearchTest {

    private static final JsonMapper MAPPER = JacksonUtils.getDefaultJsonMapper();

    @TempDir
    Path root;

    private DocumentSearch search;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("jeap-messaging/docs"));
        Files.writeString(root.resolve("jeap-messaging/docs/outbox.md"), "# Outbox\nbody text");
        search = searchFor(TestDocsProperties.withDocsRoot(root.toString()));
    }

    private static DocumentSearch searchFor(JeapDocsProperties props) {
        return new DocumentSearch(new DocsReader(new DocPathPolicy(props)), props);
    }

    // --- requestArgs (outbound policy) ---

    @Test
    void requestArgsScopeToDocsProjectWithOverFetchedLimit() {
        Map<String, Object> args = searchFor(TestDocsProperties.withDocsRoot(root.toString())).requestArgs("q", null);
        assertEquals("q", args.get("query"));
        assertEquals("jeap-docs", args.get("project"), "always scoped to the jeap-docs project");
        assertEquals(0.5, ((Number) args.get("min_score")).doubleValue(), 1e-9);
        assertEquals(15, ((Number) args.get("limit")).intValue(), "maxDocs(5) * OVER_FETCH(3)");
        assertEquals(Boolean.TRUE, args.get("hybrid"));
    }

    @Test
    void requestArgsClampRequestedLimitBeforeOverFetching() {
        Map<String, Object> args = searchForMaxDocs(2).requestArgs("q", 1000);
        assertEquals(6, ((Number) args.get("limit")).intValue(), "limit=1000 clamps to maxDocs(2) then * OVER_FETCH(3)");
    }

    // --- assembleDocuments (inbound reshape) ---

    @Test
    void dedupesByFilePathAndReadsWholeContent() {
        String json = "{\"results\":["
                + "{\"file_path\":\"jeap-messaging/docs/outbox.md\",\"score\":0.6,\"content\":\"chunk-a\"},"
                + "{\"file_path\":\"jeap-messaging/docs/outbox.md\",\"score\":0.9,\"content\":\"chunk-b\"}]}";
        JsonNode out = MAPPER.readTree(search.assembleDocuments(json, 5));
        assertEquals(1, out.size(), "two chunks of one file collapse to one document");
        assertEquals("jeap-messaging/docs/outbox.md", out.get(0).path("path").asString());
        assertEquals(0.9, out.get(0).path("score").asDouble(), 1e-9, "keeps the best chunk score");
        assertTrue(out.get(0).path("content").asString().contains("body text"), "returns whole-doc body");
    }

    @Test
    void reportsVectorScoreToTheClientButRanksByFusedScore() {
        // Under hybrid search the upstream fused score is an RRF rank artifact (~1/60): correct for
        // ordering, meaningless as a relevance signal. The reshape must rank by the fused score but
        // echo the cosine vector_score to the client.
        String json = "{\"results\":["
                + "{\"file_path\":\"jeap-messaging/docs/second.md\",\"score\":0.0161,\"vector_score\":0.95},"
                + "{\"file_path\":\"jeap-messaging/docs/first.md\",\"score\":0.0164,\"vector_score\":0.72}]}";
        JsonNode out = MAPPER.readTree(search.assembleDocuments(json, 5));
        assertEquals(2, out.size());
        assertEquals("jeap-messaging/docs/first.md", out.get(0).path("path").asString(), "the fused score decides the rank");
        assertEquals(0.72, out.get(0).path("score").asDouble(), 1e-9, "the vector_score is what the client sees");
        assertEquals(0.95, out.get(1).path("score").asDouble(), 1e-9);
    }

    @Test
    void dedupeReportsTheVectorScoreOfTheChunkThatWonByFusedScore() {
        // two chunks of one file: the fused score picks the winning chunk, and the reported score must be
        // that same chunk's vector_score - not the file's best vector_score.
        String json = "{\"results\":["
                + "{\"file_path\":\"jeap-messaging/docs/outbox.md\",\"score\":0.6,\"vector_score\":0.99,\"content\":\"chunk-a\"},"
                + "{\"file_path\":\"jeap-messaging/docs/outbox.md\",\"score\":0.9,\"vector_score\":0.7,\"content\":\"chunk-b\"}]}";
        JsonNode out = MAPPER.readTree(search.assembleDocuments(json, 5));
        assertEquals(1, out.size());
        assertEquals(0.7, out.get(0).path("score").asDouble(), 1e-9, "vector_score of the fused-score winner");
        assertTrue(out.get(0).path("snippet").asString().contains("chunk-b"), "snippet of the fused-score winner");
    }

    @Test
    void dedupesChunksOfOneFileThatDifferOnlyBySuffix() {
        // were the upstream ever to key chunks of one file by #anchor / ?query, those must still collapse to
        // one document — otherwise the same whole-doc content would fill two top-K slots.
        String json = "{\"results\":["
                + "{\"file_path\":\"jeap-messaging/docs/outbox.md#configuration\",\"score\":0.6,\"content\":\"chunk-a\"},"
                + "{\"file_path\":\"jeap-messaging/docs/outbox.md?v=2\",\"score\":0.9,\"content\":\"chunk-b\"}]}";
        JsonNode out = MAPPER.readTree(search.assembleDocuments(json, 5));
        assertEquals(1, out.size(), "suffixed chunks of one file collapse to one document");
        assertEquals("jeap-messaging/docs/outbox.md", out.get(0).path("path").asString(), "echoes the bare resolvable path");
        assertEquals(0.9, out.get(0).path("score").asDouble(), 1e-9, "keeps the best chunk score");
        assertTrue(out.get(0).path("content").asString().contains("body text"), "returns whole-doc body");
    }

    @Test
    void clampsOutputToMaxDocs() {
        // maxDocs = 2; feed 4 distinct file_paths -> at most 2 documents returned
        DocumentSearch capped = searchForMaxDocs(2);
        StringBuilder sb = new StringBuilder("{\"results\":[");
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"file_path\":\"jeap-messaging/docs/d").append(i).append(".md\",\"score\":0.")
                    .append(9 - i).append("}");
        }
        sb.append("]}");
        JsonNode out = MAPPER.readTree(capped.assembleDocuments(sb.toString(), 1000));
        assertEquals(2, out.size(), "output is clamped to maxDocs regardless of requested limit");
    }

    @Test
    void ranksByScoreDescendingAndKeepsHighestScoredTopK() {
        // scores deliberately out of input order; maxDocs=2 must keep the two HIGHEST and order them desc.
        // Without this, an unsorted or ascending rank would still pass clampsOutputToMaxDocs (size only).
        DocumentSearch capped = searchForMaxDocs(2);
        String json = "{\"results\":["
                + "{\"file_path\":\"jeap-messaging/docs/low.md\",\"score\":0.2},"
                + "{\"file_path\":\"jeap-messaging/docs/high.md\",\"score\":0.9},"
                + "{\"file_path\":\"jeap-messaging/docs/mid.md\",\"score\":0.5}]}";
        JsonNode out = MAPPER.readTree(capped.assembleDocuments(json, 1000));
        assertEquals(2, out.size(), "clamped to maxDocs(2)");
        assertEquals("jeap-messaging/docs/high.md", out.get(0).path("path").asString(), "highest score first");
        assertEquals("jeap-messaging/docs/mid.md", out.get(1).path("path").asString(), "next-highest second, lowest dropped");
    }

    @Test
    void zeroHitResultsBecomeEmptyJsonArray() {
        // a well-formed results[] with no hits reshapes to a parseable empty array, not upstream passthrough
        JsonNode out = MAPPER.readTree(search.assembleDocuments("{\"results\":[]}", 5));
        assertTrue(out.isArray());
        assertEquals(0, out.size());
    }

    @Test
    void returnsPointerWhenBodyUnreadable() {
        // a ranked path that does not exist on disk -> pointer, not content (reshape must not throw)
        String json = "{\"results\":[{\"file_path\":\"jeap-messaging/docs/ghost.md\",\"score\":0.9}]}";
        JsonNode out = MAPPER.readTree(search.assembleDocuments(json, 5));
        assertEquals(1, out.size());
        assertEquals("jeap-messaging/docs/ghost.md", out.get(0).path("pointer").asString());
        assertTrue(out.get(0).path("content").isMissingNode());
    }

    @Test
    void keepsSnippetWhenBodyUnreadable() {
        // the snippet (the start of the matching chunk) rides on every hit; verify it survives even when
        // an unreadable doc falls back to a pointer in place of whole-file content.
        String json = "{\"results\":[{\"file_path\":\"jeap-messaging/docs/ghost.md\",\"score\":0.9,"
                + "\"content\":\"a helpful snippet chunk\"}]}";
        JsonNode out = MAPPER.readTree(search.assembleDocuments(json, 5));
        assertEquals(1, out.size());
        assertEquals("jeap-messaging/docs/ghost.md", out.get(0).path("pointer").asString());
        assertTrue(out.get(0).path("snippet").asString().contains("helpful snippet"), "snippet survives the pointer fallback");
        assertTrue(out.get(0).path("content").isMissingNode());
    }

    @Test
    void truncatesSnippetWithoutSplittingASurrogatePair() {
        // A supplementary-plane char (emoji = a UTF-16 surrogate pair) straddling the 250-char cut must
        // not leave a lone surrogate, which Jackson would serialize as a garbled '?'. The high surrogate
        // sits at index 249 (SNIPPET_MAX_CHARS - 1), so the whole pair is dropped and the snippet ends
        // cleanly with the ellipsis.
        int max = 250;   // mirrors DocumentSearch.SNIPPET_MAX_CHARS
        String emoji = new String(Character.toChars(0x1F600));   // 😀
        String content = "a".repeat(max - 1) + emoji + "tail";
        String json = "{\"results\":[{\"file_path\":\"jeap-messaging/docs/ghost.md\",\"score\":0.9,"
                + "\"content\":\"" + content + "\"}]}";

        JsonNode out = MAPPER.readTree(search.assembleDocuments(json, 5));

        assertEquals("a".repeat(max - 1) + "…", out.get(0).path("snippet").asString());
    }

    @Test
    void docsRootAbsentDegradesToPointerAndNeverThrows() {
        DocumentSearch noRoot = searchFor(TestDocsProperties.withDocsRoot(root.resolve("does-not-exist").toString()));
        String json = "{\"results\":[{\"file_path\":\"jeap-messaging/docs/outbox.md\",\"score\":0.9}]}";
        String out = noRoot.assembleDocuments(json, 5);
        assertTrue(out.contains("pointer"));
        assertFalse(out.contains("\"content\""));
    }

    @Test
    void handsBackNonResultsJsonUnchanged() {
        String notResults = "upstream error: something went wrong";
        assertEquals(notResults, search.assembleDocuments(notResults, 5));
    }

    @Test
    void handsBackMalformedJsonUnchanged() {
        // a body that LOOKS like JSON but does not parse must pass through, never throw
        String malformed = "{not valid json";
        assertEquals(malformed, search.assembleDocuments(malformed, 5));
    }

    private DocumentSearch searchForMaxDocs(int maxDocs) {
        return searchFor(TestDocsProperties.withDocsRootAndMaxDocs(root.toString(), maxDocs));
    }
}

package ch.admin.bit.jeap.mcp.tool;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The three-way contract of the shared {@code results[]} parser that both {@link DocumentSearch} (reshape)
 * and {@link JeapRagTools} (upstream-chunk metric) depend on: empty-Optional for a non-results body,
 * a present list for a results[] response (possibly empty), and a throw for JSON-looking-but-malformed
 * input so a parse failure stays distinguishable from an intentional non-JSON error string.
 */
class UpstreamResultsTest {

    @Test
    void returnsResultChunks() {
        Optional<List<JsonNode>> chunks = UpstreamResults.chunks(
                "{\"results\":[{\"file_path\":\"a.md\",\"score\":0.9},{\"file_path\":\"b.md\",\"score\":0.5}]}");
        assertTrue(chunks.isPresent());
        assertEquals(2, chunks.get().size());
        assertEquals("a.md", chunks.get().getFirst().path("file_path").asString(""));
    }

    @Test
    void presentButEmptyForZeroHits() {
        Optional<List<JsonNode>> chunks = UpstreamResults.chunks("{\"results\":[]}");
        assertTrue(chunks.isPresent(), "a well-formed response with zero hits is still a results[] response");
        assertTrue(chunks.get().isEmpty());
    }

    @Test
    void emptyForNonJsonBody() {
        // upstream errors are non-JSON by design -> not a parse failure, just no results
        assertTrue(UpstreamResults.chunks("upstream error: boom").isEmpty());
    }

    @Test
    void emptyForNullBody() {
        assertTrue(UpstreamResults.chunks(null).isEmpty());
    }

    @Test
    void emptyForJsonWithoutResultsArray() {
        assertTrue(UpstreamResults.chunks("{\"other\":true}").isEmpty());
        assertTrue(UpstreamResults.chunks("{\"results\":\"not-an-array\"}").isEmpty());
    }

    @Test
    void throwsForMalformedJsonLookingBody() {
        assertThrows(JacksonException.class, () -> UpstreamResults.chunks("{not valid json"));
    }
}

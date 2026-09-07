package ch.admin.bit.jeap.mcp;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Integration test that verifies the docs search and get tools of the jEAP MCP server end-to-end against a live
 * jEAP MCP server container instance started from the image given by SERVER_IMAGE.
 * Disabled by default - run with:
 * <pre>
 * SERVER_IMAGE=jeap-mcp-server:local ./mvnw verify -Dit.test=JeapDocsIT
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "SERVER_IMAGE", matches = ".+")
class JeapDocsIT {

    private static final JsonMapper MAPPER = JacksonUtils.getDefaultJsonMapper();
    private static final int TOP_K = 5;
    private static final int MCP_PORT = 8080;
    private static final AtomicInteger REQUEST_ID = new AtomicInteger(10);

    // A known docs path present under the image's docs-root /jeap/src. Overridable by DOCS_CONTENT_DOC_PATH.
    private static final String DOC_PATH =
            System.getenv().getOrDefault("DOCS_CONTENT_DOC_PATH", "jeap-messaging-outbox/docs/sending-messages.md");

    private static GenericContainer<?> server;

    // The container is a class-wide fixture closed in @AfterAll (stopServer), so try-with-resources does not apply.
    @SuppressWarnings("resource")
    @BeforeAll
    static void startServer() {
        server = new GenericContainer<>(DockerImageName.parse(System.getenv("SERVER_IMAGE")))
                // copy the upstream-connection config in and point Spring's config import at it.
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("application-docs-content-it.yml"),
                        "/tmp/config/application-docs-content-it.yml")
                .withEnv("SPRING_CONFIG_ADDITIONAL_LOCATION",
                        "file:/tmp/config/application-docs-content-it.yml")
                .withExposedPorts(MCP_PORT)
                // Tomcat opens the port only after the context fully refreshes (JeapRagTools + the upstream
                // stdio client already validated), so a listening port is a sound readiness signal here.
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofMinutes(3));
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private McpClientHarness harness;

    @BeforeEach
    void startSession() {
        harness = new McpClientHarness(server.getHost(), server.getMappedPort(MCP_PORT));
        harness.initialize();
    }

    /**
     * Calibrated eval set of {@code (query, expectedPath)} pairs. Each {@code expectedPath} is a real
     * {@code <repo>/docs/...} file the preindexed corpus returns for the given query. To retarget, rebuild
     * {@code SERVER_IMAGE} from a new preindexed digest AND recalibrate these cases together.
     */
    static Stream<Arguments> evalCases() {
        return Stream.of(
                arguments("How do I publish events transactionally with the outbox?",
                        "jeap-messaging-outbox/docs/sending-messages.md"),
                arguments("How does the sequential inbox guarantee ordering?",
                        "jeap-messaging-sequential-inbox/docs/how-it-works.md"),
                arguments("How does error handling and the dead-letter flow work?",
                        "jeap-messaging/docs/error-handling.md"));
    }

    // Every calibrated case must surface its expected document in the top-K.
    // A failure means the documentation, the index, or that eval case needs investigation.
    @ParameterizedTest(name = "{0}")
    @MethodSource("evalCases")
    void findInDocumentationExpectedDocumentIsInTopResults(String query, String expectedPath) {
        List<String> paths = topPaths(query);

        assertFalse(paths.isEmpty(), "no documents returned for query '" + query
                + "' - is the jeap-docs corpus present in the image?");

        assertTrue(paths.stream().limit(TOP_K).anyMatch(p -> p.equals(expectedPath)),
                "expected '" + expectedPath + "' in top-" + TOP_K + " for query '" + query + "', got " + paths);
    }

    @Test
    void findInDocumentationReturnsRealBodiesNotPointers() {
        String text = extractTextFromResponse(callFind("transactional outbox"));
        List<JsonNode> docs = parseDocuments(text);

        assertFalse(docs.isEmpty(), "find returned no documents - upstream must succeed and the "
                + "jeap-docs corpus be present in the image, got: " + text);

        for (JsonNode doc : docs) {
            String path = doc.path("path").asString("");
            String content = doc.path("content").asString("");

            // A whole file was read: not a ~250-char snippet, a pointer (no content), or an error message.
            assertTrue(content.length() > 300, "expected a full body (>300 chars) for '" + path
                    + "', got " + content.length() + " chars: " + doc);

            // Every hit is relevant: its body mentions at least one of the query's terms.
            String body = content.toLowerCase(Locale.ROOT);
            assertTrue(body.contains("transactional") || body.contains("outbox"),
                    "expected 'transactional' or 'outbox' in the body of '" + path + "'");
        }
    }

    @Test
    void getDocumentReturnsBodyAndIgnoresAnchor() {
        String plain = extractTextFromResponse(callGetDocument(DOC_PATH));
        assertTrue(StringUtils.hasText(plain), "jeap_get_document returns a non-empty body");
        assertTrue(plain.length() > 300, "jeap_get_document did not just return an error or a snippet");
        String withAnchor = extractTextFromResponse(callGetDocument(DOC_PATH + "#configuration"));
        assertEquals(plain, withAnchor, "an additional #anchor does not interfere with get document)");
    }

    @Test
    void docResourceTemplateReturnsTheSameBodyAsGetDocumentTool() {
        String viaTool = extractTextFromResponse(callGetDocument(DOC_PATH));

        String encodedRef = java.net.URLEncoder.encode(DOC_PATH, java.nio.charset.StandardCharsets.UTF_8);
        String viaResource = extractResourceTextFromResponse(callReadResource("jeap-docs://" + encodedRef));

        assertTrue(StringUtils.hasText(viaResource), "jeap-docs://{ref} resource returns a non-empty body");
        assertEquals(viaTool, viaResource,
                "the Resource and Tool surfaces read the same underlying doc file identically");
    }

    @Test
    void indexResourceListsCuratedOverviewAlongsideOverviewTool() {
        String viaTool = extractTextFromResponse(callTool("jeap_overview", "{}"));
        String viaResource = extractResourceTextFromResponse(callReadResource("jeap-docs://index"));

        // jeap-docs://index is the jeap_overview content followed by a DocsSitemap of ready-to-use
        // jeap-docs://{ref} URIs - not an exact mirror of the tool output.
        assertTrue(viaResource.startsWith(viaTool),
                "jeap-docs://index should start with the same content as the jeap_overview tool");
        assertTrue(viaResource.length() > viaTool.length(),
                "jeap-docs://index should append a sitemap of jeap-docs://{ref} URIs after the overview");
    }

    private List<String> topPaths(String query) {
        return parseDocuments(extractTextFromResponse(callFind(query))).stream()
                .map(d -> d.path("path").asString(""))
                .toList();
    }

    /** Parse a {@code jeap_find_in_documentation} response into its document nodes; unparsable output -> empty. */
    private static List<JsonNode> parseDocuments(String text) {
        List<JsonNode> docs = new ArrayList<>();
        try {
            MAPPER.readTree(text).forEach(docs::add);
        } catch (Exception _) {
            // output unparsable -> treated as no documents
        }
        return docs;
    }

    private ResponseEntity<String> callFind(String query) {
        String args = "{\"query\":" + MAPPER.valueToTree(query) + "}";
        return callTool("jeap_find_in_documentation", args);
    }

    private ResponseEntity<String> callGetDocument(String path) {
        String args = "{\"path\":" + MAPPER.valueToTree(path) + "}";
        return callTool("jeap_get_document", args);
    }

    private ResponseEntity<String> callReadResource(String uri) {
        String body = """
                {"jsonrpc":"2.0","id":%d,"method":"resources/read",
                 "params":{"uri":%s}}
                """.formatted(REQUEST_ID.getAndIncrement(), MAPPER.valueToTree(uri));
        return harness.postJsonRpc(body);
    }

    private String extractResourceTextFromResponse(ResponseEntity<String> response) {
        JsonNode parsed = McpClientHarness.parseMcpBody(response.getBody());
        return parsed.path("result").path("contents").path(0).path("text").asString("");
    }

    private ResponseEntity<String> callTool(String name, String argsJson) {
        String body = """
                {"jsonrpc":"2.0","id":%d,"method":"tools/call",
                 "params":{"name":"%s","arguments":%s}}
                """.formatted(REQUEST_ID.getAndIncrement(), name, argsJson);
        return harness.postJsonRpc(body);
    }

    private String extractTextFromResponse(ResponseEntity<String> response) {
        JsonNode parsed = McpClientHarness.parseMcpBody(response.getBody());
        return parsed.path("result").path("content").path(0).path("text").asString("");
    }
}

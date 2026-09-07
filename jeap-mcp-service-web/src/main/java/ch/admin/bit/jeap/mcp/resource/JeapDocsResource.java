package ch.admin.bit.jeap.mcp.resource;

import ch.admin.bit.jeap.mcp.docs.Doc;
import ch.admin.bit.jeap.mcp.docs.DocAccessException;
import ch.admin.bit.jeap.mcp.docs.DocPaths;
import ch.admin.bit.jeap.mcp.docs.DocsReader;
import ch.admin.bit.jeap.mcp.docs.DocsSitemap;
import ch.admin.bit.jeap.mcp.metrics.McpMetrics;
import ch.admin.bit.jeap.mcp.tool.JeapOverviewTool;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import org.springframework.ai.mcp.annotation.McpComplete;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Exposes jEAP documentation as MCP <b>Resources</b>
 * (<a href="https://modelcontextprotocol.io/specification/2025-11-25/server/resources">spec</a>), as a
 * second, context-sparing surface alongside the existing {@code jeap_get_document} / {@code
 * jeap_find_in_documentation} tools:
 * <ul>
 *     <li>{@link #index()} — a single static resource, {@code jeap-docs://index}, the curated repository
 *         overview. It is the one and only cheap discovery entry point exposed in {@code resources/list}
 *         — deliberately <b>not</b> one static resource per doc file, which would blow up context with a
 *         listing of hundreds of {@code docs/**} files.</li>
 *     <li>{@link #getDocument(String)} — one Resource <b>Template</b>, {@code jeap-docs://{ref}}, covering
 *         every allow-listed doc file on demand. A single template entry in {@code
 *         resources/templates/list} replaces what would otherwise be a per-file resource list.</li>
 * </ul>
 * {@link #index()}'s content is more than just the umbrella overview: it also appends a
 * {@link DocsSitemap} of ready-to-use, percent-encoded {@code jeap-docs://{ref}} URIs, grouped by
 * repository, so a client does not have to construct (and correctly percent-encode) a ref itself.
 * <p>
 * Both read paths delegate to the same {@link DocsReader} / {@code docs/**} allow-list the tools use, so
 * access rules and content are identical — only the MCP feature (Resource vs Tool) differs. Discovery of
 * *which* doc to read is still {@code jeap_find_in_documentation}'s job (or the {@code index} resource for
 * a first orientation); resources are for reading a doc once you know (or can guess) its path.
 */
@Component
public class JeapDocsResource {

    public static final String DOC_RESOURCE_NAME = "jeap_doc";
    public static final String INDEX_RESOURCE_NAME = "jeap_docs_index";

    private final DocsReader docsReader;
    private final JeapOverviewTool overviewTool;
    private final McpMetrics metrics;

    public JeapDocsResource(DocsReader docsReader, JeapOverviewTool overviewTool, McpMetrics metrics) {
        this.docsReader = docsReader;
        this.overviewTool = overviewTool;
        this.metrics = metrics;
    }

    /**
     * Static entry-point resource: the same curated jEAP repository index the {@code jeap_overview} tool
     * returns. Kept as a single, small resource (not a list of docs) so a client browsing {@code
     * resources/list} sees one cheap orientation document, not the whole corpus.
     */
    @McpResource(
            uri = "jeap-docs://index",
            name = INDEX_RESOURCE_NAME,
            title = "jEAP documentation index",
            description = "Start here: the curated jEAP repository index (every jeap-* repository with a "
                    + "one-line description, same content as the jeap_overview tool), followed by a sitemap "
                    + "of ready-to-use jeap-docs://{ref} URIs grouped by repository.",
            mimeType = "text/markdown")
    public String index() {
        metrics.recordResourceRead(INDEX_RESOURCE_NAME, "md", "docs");
        return overviewTool.jeapOverview() + DocsSitemap.render(docsReader.listKnownPaths());
    }

    /**
     * Resource Template covering every allow-listed jEAP documentation file. {@code ref} is the same
     * repo-prefixed, index-local path {@code jeap_get_document} accepts (e.g. {@code
     * jeap-messaging/docs/outbox.md}, optionally with a trailing {@code #section} anchor) — but the MCP URI
     * template syntax only allows a single opaque path segment per {@code {ref}} placeholder (no literal
     * {@code /}), so the client must percent-encode it, e.g. {@code
     * jeap-docs://jeap-messaging%2Fdocs%2Foutbox.md}.
     */
    @McpResource(
            uri = "jeap-docs://{ref}",
            name = DOC_RESOURCE_NAME,
            title = "jEAP documentation file",
            description = "Full text of a single jEAP documentation file, addressed by its percent-encoded, "
                    + "repo-prefixed path (the same path jeap_get_document accepts, e.g. "
                    + "jeap-messaging%2Fdocs%2Foutbox.md for jeap-messaging/docs/outbox.md; a trailing "
                    + "%23section anchor is allowed but stripped before reading). Use jeap_find_in_documentation "
                    + "or the jeap-docs://index resource to discover paths.",
            mimeType = "text/plain")
    public String getDocument(String ref) {
        // URLDecoder implements application/x-www-form-urlencoded, not strict RFC 3986 percent-decoding:
        // it also turns a literal '+' into a space (RFC 3986 would leave '+' as-is). Harmless here since
        // no allow-listed doc path contains '+' or a space — but a client sending a literal '+' as part
        // of a ref would be misread as a space. If that ever needs to be exact, decode %XX manually
        // instead of delegating to URLDecoder.
        String decodedRef = URLDecoder.decode(ref, StandardCharsets.UTF_8);
        try {
            Doc doc = docsReader.readDocument(decodedRef);
            // Count on the NORMALIZED path (no #anchor / ?query), same policy as jeap_get_document's metric.
            metrics.recordResourceRead(DOC_RESOURCE_NAME, DocPaths.extension(doc.ref().path()),
                    DocPaths.area(doc.ref().path()));
            return doc.content();
        } catch (DocAccessException e) {
            // A clean, non-leaking McpError (not a text body): resources/read failures should surface as a
            // proper JSON-RPC error to the client, unlike the tool's plain-text error convention.
            throw McpError.builder(ErrorCodes.INVALID_PARAMS).message(e.getMessage()).build();
        }
    }

    /**
     * Completion for the {@code ref} argument of the {@code jeap-docs://{ref}} template
     * (<a href="https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/completion">spec</a>):
     * as a client fills in {@code ref}, suggest allow-listed doc paths starting with what has been typed
     * so far, so a caller does not have to already know (or guess) the exact path.
     */
    @McpComplete(uri = "jeap-docs://{ref}")
    public List<String> completeRef(String value) {
        String prefix = value == null ? "" : value;
        List<String> suggestions = docsReader.listKnownPaths().stream()
                .filter(path -> path.startsWith(prefix))
                .limit(100)
                .toList();
        metrics.recordResourceCompletionCall(DOC_RESOURCE_NAME, !suggestions.isEmpty());
        return suggestions;
    }
}

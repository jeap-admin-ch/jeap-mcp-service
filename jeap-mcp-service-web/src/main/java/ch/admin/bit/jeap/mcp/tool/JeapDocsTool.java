package ch.admin.bit.jeap.mcp.tool;

import ch.admin.bit.jeap.mcp.docs.Doc;
import ch.admin.bit.jeap.mcp.docs.DocAccessException;
import ch.admin.bit.jeap.mcp.docs.DocPaths;
import ch.admin.bit.jeap.mcp.docs.DocsReader;
import ch.admin.bit.jeap.mcp.metrics.McpMetrics;
import io.micrometer.core.annotation.Timed;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Fetch jEAP documentation tool: returns the full text of a single jEAP documentation file by its
 * repo-prefixed path. A pure filesystem read (no upstream {@code project-rag} call).
 * Discovery of <em>which</em> doc to read is {@code jeap_find_in_documentation}'s job.
 */
@Component
public class JeapDocsTool {

    static final String TOOL_NAME = "jeap_get_document";

    private final DocsReader docsReader;
    private final McpMetrics metrics;

    public JeapDocsTool(DocsReader docsReader, McpMetrics metrics) {
        this.docsReader = docsReader;
        this.metrics = metrics;
    }

    @Tool(name = TOOL_NAME,
            description = "Fetch the full text of a single jEAP documentation file by its "
                    + "repo-prefixed path (e.g. for the repo jeap-messaging: \"jeap-messaging/docs/outbox.md\", "
                    + "\"jeap/docs/overview/architecture.md\", or the repo-root README like "
                    + "\"jeap-messaging/README.md\"). The path always starts with the <repo> segment; a "
                    + "trailing #section anchor is allowed but is stripped before the file is read. Use to "
                    + "read a complete doc file and to follow its cross-links to related docs on demand (pass a "
                    + "linked <repo>/docs/... path back into this tool). Use jeap_find_in_documentation "
                    + "to discover documentation files to read.")
    @Timed(value = McpMetrics.TIMER_NAME,
            description = McpMetrics.DESCRIPTION,
            extraTags = {McpMetrics.TOOL_TAG, TOOL_NAME})
    public String getDocument(
            @ToolParam(description = "Repo-prefixed documentation path, e.g. jeap-messaging/docs/outbox.md for the repo jeap-messaging")
            String path) {
        try {
            Doc doc = docsReader.readDocument(path);
            // Count on the NORMALIZED path (no #anchor / ?query), so extension/area tags stay clean.
            metrics.recordDocFetched(DocPaths.extension(doc.ref().path()), DocPaths.area(doc.ref().path()));
            return doc.content();
        } catch (DocAccessException e) {
            return e.getMessage();
        }
    }
}

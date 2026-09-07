package ch.admin.bit.jeap.mcp.tool;

import ch.admin.bit.jeap.mcp.metrics.McpMetrics;
import io.micrometer.core.annotation.Timed;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

@Component
public class JeapOverviewTool {

    static final String TOOL_NAME = "jeap_overview";

    private final String jeapOverview;

    public JeapOverviewTool(
            @Value("${jeap.mcp.knowledge.overview-docs-location}") Resource docsDirectory) throws IOException {
        this.jeapOverview = loadDocs(docsDirectory.getFile().toPath());
    }

    @Tool(name = TOOL_NAME,
            description = "Start here for all jEAP queries: What is JEAP, which libraries and starters " +
                    "does it provide. Recommended to consult before calling jeap_find_code_examples.")
    @Timed(value = McpMetrics.TIMER_NAME,
            description = McpMetrics.DESCRIPTION,
            extraTags = {McpMetrics.TOOL_TAG, TOOL_NAME})
    public String jeapOverview() {
        return jeapOverview;
    }

    static String loadDocs(Path docsDir) throws IOException {
        List<Path> mdFiles;
        try (Stream<Path> paths = Files.walk(docsDir)) {
            mdFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    .sorted((a, b) -> compareDocPaths(docsDir, a, b))
                    .toList();
        }
        StringBuilder sb = new StringBuilder();
        for (Path file : mdFiles) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(Files.readString(file, UTF_8));
        }
        return sb.toString();
    }

    /**
     * Sorts doc paths so that shallower files come first (overview before details),
     * {@code index.md} comes before sibling files in the same directory, and files
     * at the same depth are ordered alphabetically.
     */
    private static int compareDocPaths(Path base, Path a, Path b) {
        Path relA = base.relativize(a);
        Path relB = base.relativize(b);
        int depthA = relA.getNameCount();
        int depthB = relB.getNameCount();
        if (depthA != depthB) {
            return Integer.compare(depthA, depthB);
        }
        boolean aIsIndex = a.getFileName().toString().equals("index.md");
        boolean bIsIndex = b.getFileName().toString().equals("index.md");
        if (aIsIndex != bIsIndex) {
            return aIsIndex ? -1 : 1;
        }
        return relA.compareTo(relB);
    }
}

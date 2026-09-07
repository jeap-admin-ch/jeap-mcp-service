package ch.admin.bit.jeap.mcp.tool;

import ch.admin.bit.jeap.mcp.metrics.McpMetrics;
import ch.admin.bit.jeap.mcp.services.JeapVersionOverviewService;
import io.micrometer.core.annotation.Timed;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class JeapVersionOverviewTool {

    static final String TOOL_NAME = "jeap_version_overview";

    private final JeapVersionOverviewService versionOverviewService;

    public JeapVersionOverviewTool(JeapVersionOverviewService versionOverviewService) {
        this.versionOverviewService = versionOverviewService;
    }

    @Tool(name = TOOL_NAME,
            description = "Fetch the latest jEAP version overview. Returns current versions of jEAP parent, libraries, " +
                    "products, Spring components, and managed 3rd party dependencies. Data is cached and refreshed periodically from GitHub. " +
                    "Optional filter parameter: 'parent' (jEAP Parent), 'libraries' (jEAP Library Versions), 'products' (jEAP Products), " +
                    "'spring' (Spring component versions), 'thirdparty' (3rd party dependencies), or 'all' (default).")
    @Timed(value = McpMetrics.TIMER_NAME,
            description = McpMetrics.DESCRIPTION,
            extraTags = {McpMetrics.TOOL_TAG, TOOL_NAME})
    public String jeapVersionOverview(
            @ToolParam(required = false, description = "Optional filter: 'parent', 'libraries', 'products', 'spring', 'thirdparty', or 'all' (default). " +
                    "Use this to get only the version information you need.")
            String filter) {
        return versionOverviewService.getVersionOverviewFiltered(filter);
    }
}

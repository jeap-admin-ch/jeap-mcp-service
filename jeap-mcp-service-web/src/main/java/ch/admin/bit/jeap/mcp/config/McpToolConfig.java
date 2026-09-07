package ch.admin.bit.jeap.mcp.config;

import ch.admin.bit.jeap.mcp.tool.JeapDocsTool;
import ch.admin.bit.jeap.mcp.tool.JeapOverviewTool;
import ch.admin.bit.jeap.mcp.tool.JeapRagTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class McpToolConfig {

    @Bean
    ToolCallbackProvider jeapToolCallbackProvider(JeapOverviewTool jeapOverviewTool,
                                                  JeapRagTools jeapRagTools,
                                                  JeapDocsTool jeapDocsTool,
                                                  ch.admin.bit.jeap.mcp.tool.JeapVersionOverviewTool jeapVersionOverviewTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(jeapOverviewTool, jeapRagTools, jeapDocsTool, jeapVersionOverviewTool)
                .build();
    }

    @Bean
    RestClient restClient(RestClient.Builder builder) {
        // A bare RestClient.create() has no timeout at all - JeapVersionOverviewService used to
        // call out on the main startup thread with this, and a slow/unreachable network path
        // (e.g. restricted outbound internet access) could hang app startup for minutes instead
        // of failing fast into its own "use cached content" fallback.
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults()
                        .withConnectTimeout(Duration.ofSeconds(5))
                        .withReadTimeout(Duration.ofSeconds(5)));
        return builder
                .requestFactory(requestFactory)
                // Some internal mirrors sit behind a WAF/proxy that may treat a generic/default
                // HTTP client User-Agent differently from a browser- or curl-like one - set an
                // explicit, identifiable one rather than leaving it to whatever the underlying
                // client defaults to.
                .defaultHeader(HttpHeaders.USER_AGENT, "jeap-mcp-service")
                .build();
    }
}

package ch.admin.bit.jeap.mcp.tool;

import ch.admin.bit.jeap.mcp.docs.DocPathPolicy;
import ch.admin.bit.jeap.mcp.docs.DocsReader;
import ch.admin.bit.jeap.mcp.docs.JeapDocsProperties;
import ch.admin.bit.jeap.mcp.docs.TestDocsProperties;
import ch.admin.bit.jeap.mcp.metrics.McpMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies that {@code @Retryable} on the {@link JeapRagTools} {@code @Tool} methods actually fires
 * through Spring AOP and honours the configured properties. Uses a custom (non-default) {@code
 * max-attempts=5} so a passing test proves the SpEL placeholders resolve against {@code Environment}
 * — if placeholder resolution silently fell back to the annotation defaults (3), the assertions
 * below would fail.
 */
@SpringJUnitConfig(JeapRagToolsRetryTest.TestConfig.class)
@TestPropertySource(properties = {
        "jeap.mcp.upstream.retry.max-attempts=5",
        "jeap.mcp.upstream.retry.backoff-millis=0"
})
class JeapRagToolsRetryTest {

    private static final int CONFIGURED_MAX_ATTEMPTS = 5;
    private static final String CLIENT_NAME = "spring-ai-mcp-client";

    @Autowired
    private McpSyncClient projectRagClient;

    @Autowired
    private JeapRagTools tools;

    @BeforeEach
    void resetMock() {
        reset(projectRagClient);
        when(projectRagClient.getClientInfo())
                .thenReturn(McpSchema.Implementation.builder(CLIENT_NAME + " - project-rag", "1.0.0").build());
    }

    @Test
    void transientFailureIsRetriedAndEventuallySucceeds() {
        when(projectRagClient.callTool(any()))
                .thenThrow(new RuntimeException("Failed to enqueue message"))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(McpSchema.TextContent.builder("recovered").build()), false, null, null));

        String result = tools.getStatistics();

        assertEquals("recovered", result);
        verify(projectRagClient, times(2)).callTool(any());
    }

    @Test
    void upstreamCallThrowingIsRethrownAfterMaxAttempts() {
        RuntimeException failure = new RuntimeException("connection lost");
        when(projectRagClient.callTool(any())).thenThrow(failure);

        RuntimeException ex = assertThrows(RuntimeException.class, tools::getStatistics);
        assertSame(failure, ex);
        verify(projectRagClient, times(CONFIGURED_MAX_ATTEMPTS)).callTool(any());
    }

    @Test
    void illegalStateExceptionIsNotRetried() {
        // Pins the noRetryFor = IllegalStateException.class contract on @Retryable. If someone
        // removes it, this test sees CONFIGURED_MAX_ATTEMPTS invocations instead of one and fails.
        IllegalStateException failure = new IllegalStateException("permanent configuration problem");
        when(projectRagClient.callTool(any())).thenThrow(failure);

        IllegalStateException ex = assertThrows(IllegalStateException.class, tools::getStatistics);
        assertSame(failure, ex);
        verify(projectRagClient, times(1)).callTool(any());
    }

    @Configuration
    @EnableRetry
    static class TestConfig {

        @Bean
        McpSyncClient projectRagClient() {
            McpSyncClient client = mock(McpSyncClient.class);
            when(client.getClientInfo())
                    .thenReturn(McpSchema.Implementation.builder(CLIENT_NAME + " - project-rag", "1.0.0").build());
            return client;
        }

        @Bean
        JeapRagTools jeapRagTools(McpSyncClient projectRagClient) {
            JeapDocsProperties props = TestDocsProperties.defaults();
            DocumentSearch documentSearch = new DocumentSearch(new DocsReader(new DocPathPolicy(props)), props);
            return new JeapRagTools(List.of(projectRagClient), CLIENT_NAME, false,
                    new McpMetrics(new SimpleMeterRegistry()), documentSearch);
        }
    }
}

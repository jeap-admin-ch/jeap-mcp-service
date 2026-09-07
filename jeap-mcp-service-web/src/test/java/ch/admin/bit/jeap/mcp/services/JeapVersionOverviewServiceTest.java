package ch.admin.bit.jeap.mcp.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JeapVersionOverviewService Tests")
class JeapVersionOverviewServiceTest {

    private static final String GITHUB_RAW_URL = "https://raw.githubusercontent.com/jeap-admin-ch/jeap/main/docs/jeap-version-overview.md";
    private static final String MOCK_VERSION_CONTENT = "# jEAP Parent\nCurrent Version: `36.4.0`\n\n" +
            "| Component | Current Version |\n| --- | --- |\n| jeap-audit | `8.16.0` |";

    // Deep stubs: RestClient's fluent chain (get().uri(...).retrieve().body(...)) has no single
    // method to mock directly - each intermediate spec interface needs its own mock, and Mockito
    // creates/remembers those automatically per distinct call, including per distinct uri(...)
    // argument (relevant for testServiceCallsGithubWithCorrectUrl below). uri(URI), not
    // uri(String): the service passes a pre-built URI so RestClient doesn't re-encode an
    // already-percent-encoded query string as if it were a URI template - see the service's own
    // comment on that call.
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private RestClient restClient;

    private JeapVersionOverviewService service;

    @BeforeEach
    void setUp() {
        service = new JeapVersionOverviewService(restClient, GITHUB_RAW_URL);
    }

    private void mockResponse(String content) {
        when(restClient.get().uri(URI.create(GITHUB_RAW_URL)).retrieve().body(String.class)).thenReturn(content);
    }

    @DisplayName("Should initialize and load content on startup")
    @Test
    void testServiceInitializesWithVersionOverview() {
        mockResponse(MOCK_VERSION_CONTENT);

        service.refreshVersionOverview();

        String result = service.getVersionOverview();
        assertNotNull(result);
        assertFalse(result.isBlank());
        assertEquals(MOCK_VERSION_CONTENT, result);
        assertTrue(result.contains("jEAP Parent"));
        assertTrue(result.contains("jeap-audit"));
    }

    @DisplayName("Should handle network errors gracefully")
    @Test
    void testServiceHandlesNetworkErrorGracefully() {
        when(restClient.get().uri(URI.create(GITHUB_RAW_URL)).retrieve().body(String.class))
                .thenThrow(new RestClientException("Network timeout"));

        service.refreshVersionOverview();

        String result = service.getVersionOverview();
        assertNotNull(result);
        assertTrue(result.contains("currently being loaded"));
    }

    @DisplayName("Should handle empty content from GitHub")
    @Test
    void testServiceHandlesEmptyContent() {
        mockResponse("");

        service.refreshVersionOverview();

        String result = service.getVersionOverview();
        assertNotNull(result);
        assertTrue(result.contains("currently being loaded"));
    }

    @DisplayName("Should handle null content from GitHub")
    @Test
    void testServiceHandlesNullContent() {
        mockResponse(null);

        service.refreshVersionOverview();

        String result = service.getVersionOverview();
        assertNotNull(result);
        assertTrue(result.contains("currently being loaded"));
    }

    @DisplayName("Should cache content after successful refresh")
    @Test
    void testServiceCachesContent() {
        mockResponse(MOCK_VERSION_CONTENT);

        service.refreshVersionOverview();
        String firstCall = service.getVersionOverview();

        service.refreshVersionOverview();
        String secondCall = service.getVersionOverview();

        assertEquals(firstCall, secondCall);
        // 2 explicit refreshes = 2 times (constructor no longer eagerly refreshes - it would
        // block application startup on an external network call; @Scheduled's own initialDelay
        // covers the first fetch instead, on a background thread)
        verify(restClient.get().uri(URI.create(GITHUB_RAW_URL)).retrieve(), times(2)).body(String.class);
    }

    @DisplayName("Should recover from transient network failure")
    @Test
    void testServiceRecoveryFromTransientNetworkFailure() {
        when(restClient.get().uri(URI.create(GITHUB_RAW_URL)).retrieve().body(String.class))
                .thenThrow(new RestClientException("Network error"))
                .thenReturn(MOCK_VERSION_CONTENT);

        // First call - network error
        service.refreshVersionOverview();
        String firstResult = service.getVersionOverview();
        assertTrue(firstResult.contains("currently being loaded"));

        // Second call - success
        service.refreshVersionOverview();
        String secondResult = service.getVersionOverview();
        assertEquals(MOCK_VERSION_CONTENT, secondResult);
    }

    @DisplayName("Should preserve cached content when refresh fails")
    @Test
    void testServicePreservesCacheOnRefreshFailure() {
        mockResponse(MOCK_VERSION_CONTENT);

        // First successful refresh
        service.refreshVersionOverview();
        String cachedContent = service.getVersionOverview();
        assertEquals(MOCK_VERSION_CONTENT, cachedContent);

        // Second refresh fails
        when(restClient.get().uri(URI.create(GITHUB_RAW_URL)).retrieve().body(String.class))
                .thenThrow(new RestClientException("Network error"));
        service.refreshVersionOverview();

        // Cache should still contain the old content
        String result = service.getVersionOverview();
        assertEquals(MOCK_VERSION_CONTENT, result);
    }

    @DisplayName("Should handle whitespace-only content")
    @Test
    void testServiceHandlesWhitespaceOnlyContent() {
        mockResponse("   \n  \t  ");

        service.refreshVersionOverview();

        String result = service.getVersionOverview();
        assertNotNull(result);
        assertTrue(result.contains("currently being loaded"));
    }

    @DisplayName("Should update cache with new content on refresh")
    @Test
    void testServiceUpdatesCacheWithNewContent() {
        String initialContent = "# jEAP v1";
        String updatedContent = "# jEAP v2 - Updated";

        mockResponse(initialContent);

        service.refreshVersionOverview();
        assertEquals(initialContent, service.getVersionOverview());

        mockResponse(updatedContent);

        service.refreshVersionOverview();
        assertEquals(updatedContent, service.getVersionOverview());
    }

    @DisplayName("Should call GitHub API with correct URL")
    @Test
    void testServiceCallsGithubWithCorrectUrl() {
        mockResponse(MOCK_VERSION_CONTENT);

        service.refreshVersionOverview();

        // 1 explicit refresh = 1 time (constructor no longer eagerly refreshes)
        verify(restClient.get().uri(URI.create(GITHUB_RAW_URL)).retrieve(), times(1)).body(String.class);
    }

    @DisplayName("Should handle RuntimeException during refresh")
    @Test
    void testServiceHandlesRuntimeException() {
        when(restClient.get().uri(URI.create(GITHUB_RAW_URL)).retrieve().body(String.class))
                .thenThrow(new RuntimeException("Unexpected error"));

        assertDoesNotThrow(() -> service.refreshVersionOverview());

        String result = service.getVersionOverview();
        assertNotNull(result);
        assertTrue(result.contains("currently being loaded"));
    }

    @DisplayName("Should filter parent versions")
    @Test
    void testServiceFiltersParentVersions() {
        String mockContent = "# jEAP Parent\nCurrent Version: `36.4.0`\n\n" +
                "# jEAP Library Versions\n| jeap-audit | 8.16.0 |";
        mockResponse(mockContent);

        service.refreshVersionOverview();
        String result = service.getVersionOverviewFiltered("parent");

        assertNotNull(result);
        assertTrue(result.contains("jEAP Parent"));
        assertTrue(result.contains("36.4.0"));
        assertFalse(result.contains("jeap-audit"));
    }

    @DisplayName("Should filter libraries versions")
    @Test
    void testServiceFiltersLibrariesVersions() {
        String mockContent = "# jEAP Parent\nCurrent Version: `36.4.0`\n\n" +
                "# jEAP Library Versions\n| jeap-audit | 8.16.0 |\n\n" +
                "# Spring Versions\nspring-boot: 4.1.0";
        mockResponse(mockContent);

        service.refreshVersionOverview();
        String result = service.getVersionOverviewFiltered("libraries");

        assertNotNull(result);
        assertTrue(result.contains("jeap-audit"));
        assertFalse(result.contains("spring-boot"));
    }

    @DisplayName("Should filter spring versions")
    @Test
    void testServiceFiltersSpringVersions() {
        String mockContent = "# jEAP Library Versions\n| jeap-audit | 8.16.0 |\n\n" +
                "# Spring Versions\nspring-boot: 4.1.0\nspring-framework: 7.0.8\n\n" +
                "# jEAP Products\njEAP Service: 1.0.0";
        mockResponse(mockContent);

        service.refreshVersionOverview();
        String result = service.getVersionOverviewFiltered("spring");

        assertNotNull(result);
        assertTrue(result.contains("spring-boot"));
        assertTrue(result.contains("spring-framework"));
        assertFalse(result.contains("jEAP Service"));
    }

    @DisplayName("Should return full content for null filter")
    @Test
    void testServiceReturnsFullContentForNullFilter() {
        mockResponse(MOCK_VERSION_CONTENT);

        service.refreshVersionOverview();
        String result = service.getVersionOverviewFiltered(null);

        assertEquals(MOCK_VERSION_CONTENT, result);
    }

    @DisplayName("Should return full content for 'all' filter")
    @Test
    void testServiceReturnsFullContentForAllFilter() {
        mockResponse(MOCK_VERSION_CONTENT);

        service.refreshVersionOverview();
        String result = service.getVersionOverviewFiltered("all");

        assertEquals(MOCK_VERSION_CONTENT, result);
    }

    @DisplayName("Should return full content for invalid filter")
    @Test
    void testServiceReturnsFullContentForInvalidFilter() {
        mockResponse(MOCK_VERSION_CONTENT);

        service.refreshVersionOverview();
        String result = service.getVersionOverviewFiltered("invalid");

        assertEquals(MOCK_VERSION_CONTENT, result);
    }

    @DisplayName("Should be case-insensitive for filters")
    @Test
    void testServiceFiltersAreCaseInsensitive() {
        String mockContent = "# jEAP Parent\nVersion: 36.4.0\n\n# jEAP Library Versions\nLibrary";
        mockResponse(mockContent);

        service.refreshVersionOverview();
        String resultLower = service.getVersionOverviewFiltered("parent");
        String resultUpper = service.getVersionOverviewFiltered("PARENT");
        String resultMixed = service.getVersionOverviewFiltered("Parent");

        assertEquals(resultLower, resultUpper);
        assertEquals(resultLower, resultMixed);
    }
}

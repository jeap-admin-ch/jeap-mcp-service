package ch.admin.bit.jeap.mcp.tool;

import ch.admin.bit.jeap.mcp.services.JeapVersionOverviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("JeapVersionOverviewTool Tests")
class JeapVersionOverviewToolTest {

    @Mock
    private JeapVersionOverviewService versionOverviewService;

    @DisplayName("Should return version overview from service")
    @Test
    void testToolReturnsVersionOverviewFromService() {
        String expectedContent = "# jEAP Versions\nParent: 36.4.0\njeap-audit: 8.16.0";
        when(versionOverviewService.getVersionOverviewFiltered(null)).thenReturn(expectedContent);

        JeapVersionOverviewTool tool = new JeapVersionOverviewTool(versionOverviewService);
        String result = tool.jeapVersionOverview(null);

        assertEquals(expectedContent, result);
    }

    @DisplayName("Should return filtered version overview")
    @Test
    void testToolReturnsFilteredVersionOverview() {
        String filteredContent = "# jEAP Parent\nVersion: 36.4.0";
        when(versionOverviewService.getVersionOverviewFiltered("parent")).thenReturn(filteredContent);

        JeapVersionOverviewTool tool = new JeapVersionOverviewTool(versionOverviewService);
        String result = tool.jeapVersionOverview("parent");

        assertEquals(filteredContent, result);
    }

    @DisplayName("Should have correct tool name constant")
    @Test
    void testToolNameConstant() {
        assertEquals("jeap_version_overview", JeapVersionOverviewTool.TOOL_NAME);
    }

    @DisplayName("Should have @Tool annotation on method")
    @Test
    void testToolMethodHasToolAnnotation() throws NoSuchMethodException {
        Method method = JeapVersionOverviewTool.class.getMethod("jeapVersionOverview", String.class);
        Tool toolAnnotation = method.getAnnotation(Tool.class);

        assertNotNull(toolAnnotation, "Method should have @Tool annotation");
        assertEquals("jeap_version_overview", toolAnnotation.name());
    }

    @DisplayName("Should have descriptive tool description")
    @Test
    void testToolHasDescription() throws NoSuchMethodException {
        Method method = JeapVersionOverviewTool.class.getMethod("jeapVersionOverview", String.class);
        Tool toolAnnotation = method.getAnnotation(Tool.class);

        assertNotNull(toolAnnotation.description());
        assertFalse(toolAnnotation.description().isBlank());
        assertTrue(toolAnnotation.description().contains("version"));
        assertTrue(toolAnnotation.description().toLowerCase().contains("jeap"));
    }

    @DisplayName("Should return empty string when service returns empty")
    @Test
    void testToolReturnsEmptyStringWhenServiceReturnsEmpty() {
        when(versionOverviewService.getVersionOverviewFiltered(null)).thenReturn("");

        JeapVersionOverviewTool tool = new JeapVersionOverviewTool(versionOverviewService);
        String result = tool.jeapVersionOverview(null);

        assertEquals("", result);
    }

    @DisplayName("Should return placeholder message when content is loading")
    @Test
    void testToolReturnsPlaceholderWhenLoading() {
        String loadingMessage = "Version overview is currently being loaded. Please try again shortly.";
        when(versionOverviewService.getVersionOverviewFiltered(null)).thenReturn(loadingMessage);

        JeapVersionOverviewTool tool = new JeapVersionOverviewTool(versionOverviewService);
        String result = tool.jeapVersionOverview(null);

        assertEquals(loadingMessage, result);
        assertTrue(result.contains("currently being loaded"));
    }

    @DisplayName("Should return large content without truncation")
    @Test
    void testToolReturnsLargeContent() {
        StringBuilder largeContent = new StringBuilder("# jEAP Versions\n");
        for (int i = 0; i < 1000; i++) {
            largeContent.append("Component ").append(i).append(": v").append(i).append("\n");
        }
        String expectedContent = largeContent.toString();

        when(versionOverviewService.getVersionOverviewFiltered(null)).thenReturn(expectedContent);

        JeapVersionOverviewTool tool = new JeapVersionOverviewTool(versionOverviewService);
        String result = tool.jeapVersionOverview(null);

        assertEquals(expectedContent, result);
        assertEquals(expectedContent.length(), result.length());
    }

    @DisplayName("Should call service with filter parameter")
    @Test
    void testToolCallsServiceWithFilterParameter() {
        String content = "# jEAP Content";
        when(versionOverviewService.getVersionOverviewFiltered("spring")).thenReturn(content);

        JeapVersionOverviewTool tool = new JeapVersionOverviewTool(versionOverviewService);
        tool.jeapVersionOverview("spring");

        org.mockito.Mockito.verify(versionOverviewService).getVersionOverviewFiltered("spring");
    }

    @DisplayName("Should call service exactly once per invocation")
    @Test
    void testToolCallsServiceOncePerInvocation() {
        String content = "# jEAP Content";
        when(versionOverviewService.getVersionOverviewFiltered(null)).thenReturn(content);

        JeapVersionOverviewTool tool = new JeapVersionOverviewTool(versionOverviewService);
        tool.jeapVersionOverview(null);
        tool.jeapVersionOverview(null);

        org.mockito.Mockito.verify(versionOverviewService, org.mockito.Mockito.times(2)).getVersionOverviewFiltered(null);
    }

    @DisplayName("Should preserve content formatting")
    @Test
    void testToolPreservesContentFormatting() {
        String contentWithFormatting = "# jEAP Versions\n\n" +
                "| Component | Version |\n" +
                "| --- | --- |\n" +
                "| jeap-parent | 36.4.0 |\n" +
                "| jeap-audit | 8.16.0 |\n\n" +
                "## Spring Versions\n" +
                "- spring-boot: 4.1.0\n" +
                "- spring-cloud: 2025.1.2";

        when(versionOverviewService.getVersionOverviewFiltered(null)).thenReturn(contentWithFormatting);

        JeapVersionOverviewTool tool = new JeapVersionOverviewTool(versionOverviewService);
        String result = tool.jeapVersionOverview(null);

        assertEquals(contentWithFormatting, result);
        assertTrue(result.contains("| Component | Version |"));
        assertTrue(result.contains("## Spring Versions"));
    }

    @DisplayName("Should handle special characters in content")
    @Test
    void testToolHandlesSpecialCharacters() {
        String contentWithSpecialChars = "# jEAP Versions\n" +
                "Description: \"Versions & Components\"\n" +
                "URL: <https://github.com/jeap-admin-ch/jeap>\n" +
                "Tags: [version, jeap, parent]";

        when(versionOverviewService.getVersionOverviewFiltered(null)).thenReturn(contentWithSpecialChars);

        JeapVersionOverviewTool tool = new JeapVersionOverviewTool(versionOverviewService);
        String result = tool.jeapVersionOverview(null);

        assertEquals(contentWithSpecialChars, result);
    }

    @DisplayName("Should handle whitespace in content")
    @Test
    void testToolHandlesWhitespace() {
        String contentWithWhitespace = "# jEAP Versions\n\n\n  \nCurrent Version: 36.4.0\n   \n";

        when(versionOverviewService.getVersionOverviewFiltered(null)).thenReturn(contentWithWhitespace);

        JeapVersionOverviewTool tool = new JeapVersionOverviewTool(versionOverviewService);
        String result = tool.jeapVersionOverview(null);

        assertEquals(contentWithWhitespace, result);
    }
}

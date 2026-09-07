package ch.admin.bit.jeap.mcp.tool;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeapOverviewToolTest {

    @Test
    void shouldReturnReadmeContent() throws IOException {
        JeapOverviewTool tool = new JeapOverviewTool(new ClassPathResource("jeap-docs"));

        String result = tool.jeapOverview();

        assertTrue(result.contains("jEAP"), "Should contain jEAP reference");
        assertTrue(result.contains("jeap-messaging"), "Should list jeap-messaging");
        assertTrue(result.contains("jeap-crypto"), "Should list jeap-crypto");
    }

    @Test
    void shouldFailFastWhenDocsDirectoryIsMissing() {
        ClassPathResource missing = new ClassPathResource("does-not-exist-docs");

        assertThrows(IOException.class, () -> new JeapOverviewTool(missing));
    }
}

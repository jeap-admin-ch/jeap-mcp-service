package ch.admin.bit.jeap.mcp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/**
 * Loads {@code jeapMcpServiceDefaultProperties.properties}, the library-owned property defaults
 * for {@code jeap-mcp-service-web}. Unlike an {@code application.yml} bundled in this module -
 * which Spring Boot silently ignores once the module is packaged as a dependency inside an
 * instance's fat jar - a {@code @PropertySource} is added to the Environment regardless of
 * packaging, at low enough precedence that an instance's own {@code application.yml} can still
 * override any individual key. See the property file itself for which properties are, and are
 * not, appropriate to default here.
 */
@Configuration
@PropertySource("classpath:jeapMcpServiceDefaultProperties.properties")
class McpDefaultPropertiesConfig {

}

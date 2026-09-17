package ch.admin.bit.jeap.mcp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@Configuration
public class McpSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http,
            @Value("${jeap.mcp.max-request-body-bytes:1048576}") long maxRequestBodyBytes) throws Exception {
        http
                .securityMatcher("/mcp/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(new McpRequestSizeFilter(maxRequestBodyBytes), SecurityContextHolderFilter.class);
        return http.build();
    }
}

package ch.admin.bit.jeap.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableRetry
@EnableScheduling
@ConfigurationPropertiesScan
public class Application {

    static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}

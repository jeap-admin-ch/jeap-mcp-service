package ch.admin.bit.jeap.mcp.config;

import ch.admin.bit.jeap.mcp.metrics.McpMetrics;
import io.micrometer.core.aop.MeterTagAnnotationHandler;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class McpMetricsConfig {

    @Bean
    McpMetrics mcpMetrics(MeterRegistry meterRegistry) {
        return new McpMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(TimedAspect.class)
    OrderedTimedAspect timedAspect(MeterRegistry meterRegistry,
                                   ObjectProvider<MeterTagAnnotationHandler> meterTagAnnotationHandler) {
        OrderedTimedAspect timedAspect = new OrderedTimedAspect(meterRegistry);
        meterTagAnnotationHandler.ifAvailable(timedAspect::setMeterTagAnnotationHandler);
        return timedAspect;
    }

    @Aspect
    static final class OrderedTimedAspect extends TimedAspect implements Ordered {

        private OrderedTimedAspect(MeterRegistry registry) {
            super(registry);
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}

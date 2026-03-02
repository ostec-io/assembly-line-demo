package io.ostec.focusflow.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ManagementObservabilityConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> focusCommonTagsCustomizer(
        @Value("${spring.application.name:focusflow}") String applicationName
    ) {
        return meterRegistry -> meterRegistry.config()
            .commonTags(
                "application", applicationName,
                "service", applicationName
            );
    }

    @Bean
    public MeterFilter boundedHttpServerUriCardinality() {
        return MeterFilter.maximumAllowableTags(
            "http.server.requests",
            "uri",
            150,
            MeterFilter.deny()
        );
    }
}

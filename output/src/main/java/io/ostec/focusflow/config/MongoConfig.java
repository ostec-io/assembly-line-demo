package io.ostec.focusflow.config;

import io.ostec.foundation.security.principal.SecurityContextHelper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Configuration
@EnableMongoAuditing
@EnableMongoRepositories(basePackages = "io.ostec.focusflow")
public class MongoConfig {

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.ofNullable(SecurityContextHelper.getCurrentUserId())
            .filter(StringUtils::hasText)
            .or(() -> Optional.of("system"));
    }
}

package io.ostec.focusflow.config;

import io.ostec.foundation.security.tenant.TenantContext;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskDecorator;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Configuration
public class TracingCorrelationConfig {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final String CORRELATION_KEY = "correlationId";
    private static final String TENANT_KEY = "tenantId";
    private static final String ORG_KEY = "orgId";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 30)
    public OncePerRequestFilter correlationIdPropagationFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain
            ) throws ServletException, IOException {
                TenantContext previousContext = TenantContextHolder.getContext();
                boolean hadPreviousContext = TenantContextHolder.hasContext();
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();

                String correlationId = resolveCorrelationId(request.getHeader(CORRELATION_HEADER), previousContext);
                TenantContextHolder.setContext(mergeWithCorrelation(previousContext, correlationId));
                response.setHeader(CORRELATION_HEADER, correlationId);
                putMdc(correlationId, TenantContextHolder.getTenantId(), TenantContextHolder.getOrgId());

                try {
                    filterChain.doFilter(request, response);
                } finally {
                    if (hadPreviousContext && previousContext != null) {
                        TenantContextHolder.setContext(previousContext);
                    } else {
                        TenantContextHolder.clear();
                    }

                    if (previousMdc != null) {
                        MDC.setContextMap(previousMdc);
                    } else {
                        MDC.clear();
                    }
                }
            }
        };
    }

    @Bean
    public TaskDecorator correlationTaskDecorator() {
        return runnable -> {
            TenantContext capturedContext = TenantContextHolder.getContext();
            Map<String, String> capturedMdc = MDC.getCopyOfContextMap();

            return () -> {
                TenantContext previousContext = TenantContextHolder.getContext();
                boolean hadPreviousContext = TenantContextHolder.hasContext();
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();

                try {
                    if (capturedContext != null && (capturedContext.hasAnyContext()
                        || StringUtils.hasText(capturedContext.getCorrelationId()))) {
                        TenantContextHolder.setContext(capturedContext);
                    } else {
                        TenantContextHolder.clear();
                    }

                    if (capturedMdc != null) {
                        MDC.setContextMap(capturedMdc);
                    } else {
                        MDC.clear();
                    }

                    runnable.run();
                } finally {
                    if (hadPreviousContext && previousContext != null) {
                        TenantContextHolder.setContext(previousContext);
                    } else {
                        TenantContextHolder.clear();
                    }

                    if (previousMdc != null) {
                        MDC.setContextMap(previousMdc);
                    } else {
                        MDC.clear();
                    }
                }
            };
        };
    }

    private TenantContext mergeWithCorrelation(TenantContext currentContext, String correlationId) {
        if (currentContext == null) {
            return TenantContext.builder()
                .correlationId(correlationId)
                .build();
        }

        return TenantContext.builder()
            .tenantId(currentContext.getTenantId())
            .orgId(currentContext.getOrgId())
            .userId(currentContext.getUserId())
            .userEmail(currentContext.getUserEmail())
            .sessionId(currentContext.getSessionId())
            .correlationId(correlationId)
            .build();
    }

    private String resolveCorrelationId(String requestCorrelationId, TenantContext currentContext) {
        if (StringUtils.hasText(requestCorrelationId)) {
            return requestCorrelationId.trim();
        }

        if (currentContext != null && StringUtils.hasText(currentContext.getCorrelationId())) {
            return currentContext.getCorrelationId().trim();
        }

        String correlationFromContext = TenantContextHolder.getCorrelationId();
        if (StringUtils.hasText(correlationFromContext)) {
            return correlationFromContext.trim();
        }

        return UUID.randomUUID().toString();
    }

    private void putMdc(String correlationId, String tenantId, String orgId) {
        if (StringUtils.hasText(correlationId)) {
            MDC.put(CORRELATION_KEY, correlationId);
        } else {
            MDC.remove(CORRELATION_KEY);
        }

        if (StringUtils.hasText(tenantId)) {
            MDC.put(TENANT_KEY, tenantId);
        } else {
            MDC.remove(TENANT_KEY);
        }

        if (StringUtils.hasText(orgId)) {
            MDC.put(ORG_KEY, orgId);
        } else {
            MDC.remove(ORG_KEY);
        }
    }
}

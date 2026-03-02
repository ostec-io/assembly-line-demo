package io.ostec.focusflow.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.document.ai.AiProviderAuditDocument;
import io.ostec.focusflow.document.ai.PlanMemoryDocument;
import io.ostec.focusflow.document.ai.TaskBlockEmbedded;
import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.focusflow.repository.ai.AiProviderAuditRepository;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AiProviderAclService {

    private static final Logger log = LoggerFactory.getLogger(AiProviderAclService.class);

    private static final String FALLBACK_PROVIDER = "template-fallback";
    private static final String FALLBACK_MODEL = "template-v1";
    private static final int MIN_BLOCK_MINUTES = 5;

    private final AiProviderAuditRepository aiProviderAuditRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String providerEndpoint;
    private final String providerApiKey;
    private final String providerName;
    private final String providerModel;
    private final Duration providerTimeout;
    private final String fallbackTemplateVersion;

    public AiProviderAclService(
        AiProviderAuditRepository aiProviderAuditRepository,
        ObjectMapper objectMapper,
        @Value("${focusflow.ai.provider.endpoint:}") String providerEndpoint,
        @Value("${focusflow.ai.provider.api-key:}") String providerApiKey,
        @Value("${focusflow.ai.provider.name:external-ai}") String providerName,
        @Value("${focusflow.ai.provider.model:plan-model-v1}") String providerModel,
        @Value("${focusflow.ai.provider.timeout-ms:2500}") Long providerTimeoutMs,
        @Value("${focusflow.ai.fallback.template-version:v1}") String fallbackTemplateVersion
    ) {
        this.aiProviderAuditRepository = aiProviderAuditRepository;
        this.objectMapper = objectMapper;
        this.providerEndpoint = providerEndpoint;
        this.providerApiKey = providerApiKey;
        this.providerName = providerName;
        this.providerModel = providerModel;
        this.providerTimeout = Duration.ofMillis(Math.max(providerTimeoutMs != null ? providerTimeoutMs : 2500L, 100L));
        this.fallbackTemplateVersion = fallbackTemplateVersion;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(this.providerTimeout)
            .build();
    }

    public ProviderPlanResult requestPlan(
        String planId,
        String sessionId,
        String goal,
        Integer durationMinutes,
        PlanMemoryDocument memory
    ) {
        TenantScope scope = resolveScope();
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        String requestId = UUID.randomUUID().toString();

        ProviderPlanResult result;
        boolean fallbackUsed = false;
        String fallbackProvider = null;
        String safetyReason = null;

        try {
            result = callPrimaryProvider(planId, sessionId, goal, durationMinutes, memory, requestId);
        } catch (Exception ex) {
            fallbackUsed = true;
            fallbackProvider = FALLBACK_PROVIDER;
            safetyReason = "Provider unavailable";
            log.warn(
                "AI provider unavailable or response invalid for sessionId={}, planId={}; using fallback template",
                sessionId,
                planId
            );
            result = buildFallbackTemplate(goal, durationMinutes, requestId);
        }

        long latencyMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        persistAudit(scope, planId, sessionId, requestId, startedAt, latencyMs, fallbackUsed, fallbackProvider, safetyReason);

        return result;
    }

    private ProviderPlanResult callPrimaryProvider(
        String planId,
        String sessionId,
        String goal,
        Integer durationMinutes,
        PlanMemoryDocument memory,
        String requestId
    ) throws Exception {
        if (!StringUtils.hasText(providerEndpoint)) {
            throw new IllegalStateException("AI provider endpoint is not configured");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("sessionId", sessionId);
        requestBody.put("planId", planId);
        requestBody.put("goal", goal);
        requestBody.put("durationMinutes", durationMinutes);
        requestBody.put("preferences", memory != null ? memory.getStablePreferences() : List.of());
        requestBody.put("avoidPatterns", memory != null ? memory.getAvoidPatterns() : List.of());
        requestBody.put("templateVersion", fallbackTemplateVersion);
        requestBody.put("requestId", requestId);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(providerEndpoint.trim()))
            .timeout(providerTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)));

        if (StringUtils.hasText(providerApiKey)) {
            builder.header("Authorization", "Bearer " + providerApiKey.trim());
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Provider returned status " + response.statusCode());
        }

        return normalizeProviderResponse(response.body(), durationMinutes, requestId);
    }

    private ProviderPlanResult normalizeProviderResponse(
        String rawResponseBody,
        Integer durationMinutes,
        String requestId
    ) throws Exception {
        if (!StringUtils.hasText(rawResponseBody)) {
            throw new IllegalStateException("Provider returned empty body");
        }

        JsonNode root = objectMapper.readTree(rawResponseBody);
        List<TaskBlockEmbedded> taskBlocks = extractTaskBlocks(root, durationMinutes);
        if (taskBlocks.isEmpty()) {
            throw new IllegalStateException("Provider response did not contain task blocks");
        }

        String normalizedProvider = extractText(root, "provider", "vendor", "source");
        if (!StringUtils.hasText(normalizedProvider)) {
            normalizedProvider = providerName;
        }

        String normalizedModel = extractText(root, "model", "modelName", "engine");
        if (!StringUtils.hasText(normalizedModel)) {
            normalizedModel = providerModel;
        }

        String templateVersion = extractText(root, "templateVersion", "promptVersion", "version");
        if (!StringUtils.hasText(templateVersion)) {
            templateVersion = fallbackTemplateVersion;
        }

        return new ProviderPlanResult(
            taskBlocks,
            normalizedProvider,
            normalizedModel,
            templateVersion,
            false,
            null,
            requestId
        );
    }

    private ProviderPlanResult buildFallbackTemplate(String goal, Integer durationMinutes, String requestId) {
        int effectiveDuration = normalizeDuration(durationMinutes);
        int warmupMinutes = Math.max(MIN_BLOCK_MINUTES, Math.round(effectiveDuration * 0.2f));
        int reviewMinutes = Math.max(MIN_BLOCK_MINUTES, Math.round(effectiveDuration * 0.15f));
        int executionMinutes = Math.max(MIN_BLOCK_MINUTES, effectiveDuration - warmupMinutes - reviewMinutes);
        int total = warmupMinutes + reviewMinutes + executionMinutes;
        if (total != effectiveDuration) {
            executionMinutes = Math.max(MIN_BLOCK_MINUTES, executionMinutes + (effectiveDuration - total));
        }

        String normalizedGoal = StringUtils.hasText(goal) ? goal.trim() : "Complete the planned deep-work objective";
        List<TaskBlockEmbedded> taskBlocks = new ArrayList<>();
        taskBlocks.add(new TaskBlockEmbedded(
            deterministicBlockId(normalizedGoal, 1),
            "Prime Focus",
            "Clarify the target outcome and remove immediate distractions for: " + normalizedGoal,
            warmupMinutes,
            1
        ));
        taskBlocks.add(new TaskBlockEmbedded(
            deterministicBlockId(normalizedGoal, 2),
            "Execution Sprint",
            "Deliver the core work with uninterrupted concentration on: " + normalizedGoal,
            executionMinutes,
            2
        ));
        taskBlocks.add(new TaskBlockEmbedded(
            deterministicBlockId(normalizedGoal, 3),
            "Review and Capture",
            "Summarize progress, identify blockers, and prepare the next starting point",
            reviewMinutes,
            3
        ));

        return new ProviderPlanResult(
            taskBlocks,
            FALLBACK_PROVIDER,
            FALLBACK_MODEL,
            fallbackTemplateVersion,
            true,
            FALLBACK_PROVIDER,
            requestId
        );
    }

    private List<TaskBlockEmbedded> extractTaskBlocks(JsonNode root, Integer durationMinutes) {
        JsonNode taskBlockNode = firstArrayNode(root, "taskBlocks", "blocks", "tasks");
        if ((taskBlockNode == null || !taskBlockNode.isArray()) && root != null && root.has("plan")) {
            taskBlockNode = firstArrayNode(root.path("plan"), "taskBlocks", "blocks", "tasks", "steps");
        }

        if (taskBlockNode == null || !taskBlockNode.isArray() || taskBlockNode.isEmpty()) {
            return List.of();
        }

        int fallbackEstimate = Math.max(MIN_BLOCK_MINUTES, normalizeDuration(durationMinutes) / Math.max(taskBlockNode.size(), 1));
        List<TaskBlockEmbedded> blocks = new ArrayList<>();

        int sequence = 1;
        for (JsonNode node : taskBlockNode) {
            String title = extractText(node, "title", "name", "task");
            if (!StringUtils.hasText(title)) {
                continue;
            }

            Integer estimateMinutes = extractInteger(node, "estimateMinutes", "minutes", "durationMinutes", "estimate");
            if (estimateMinutes == null || estimateMinutes <= 0) {
                estimateMinutes = fallbackEstimate;
            }
            estimateMinutes = Math.clamp(estimateMinutes, MIN_BLOCK_MINUTES, 240);

            Integer providedSequence = extractInteger(node, "sequence", "order", "index", "position");
            int effectiveSequence = providedSequence != null && providedSequence > 0 ? providedSequence : sequence;
            String blockId = extractText(node, "blockId", "id", "taskId");
            if (!StringUtils.hasText(blockId)) {
                blockId = deterministicBlockId(title, effectiveSequence);
            }

            TaskBlockEmbedded block = new TaskBlockEmbedded(
                blockId,
                title.trim(),
                normalizeNullable(extractText(node, "details", "description", "notes")),
                estimateMinutes,
                effectiveSequence
            );
            blocks.add(block);
            sequence++;
        }

        return blocks;
    }

    private JsonNode firstArrayNode(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }

        for (String key : keys) {
            JsonNode candidate = node.get(key);
            if (candidate != null && candidate.isArray()) {
                return candidate;
            }
        }

        return null;
    }

    private Integer extractInteger(JsonNode node, String... keys) {
        String value = extractText(node, keys);
        if (!StringUtils.hasText(value)) {
            return null;
        }

        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String extractText(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }

        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isValueNode()) {
                String text = value.asText();
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
        }

        return null;
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private int normalizeDuration(Integer durationMinutes) {
        int duration = durationMinutes != null ? durationMinutes : 50;
        return Math.clamp(duration, 15, 240);
    }

    private String deterministicBlockId(String material, int sequence) {
        String source = sequence + ":" + material.toLowerCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void persistAudit(
        TenantScope scope,
        String planId,
        String sessionId,
        String requestId,
        Instant occurredAt,
        long latencyMs,
        boolean fallbackUsed,
        String fallbackProvider,
        String safetyReason
    ) {
        AiProviderAuditDocument audit = new AiProviderAuditDocument();
        audit.setTenantId(scope.tenantId());
        audit.setOrgId(scope.orgId());
        audit.setUserId(scope.userId());
        audit.setPlanId(planId);
        audit.setSessionId(sessionId);
        audit.setProvider(providerName);
        audit.setModel(providerModel);
        audit.setLatencyMs(Math.max(latencyMs, 0));
        audit.setFallbackUsed(fallbackUsed);
        audit.setFallbackProvider(fallbackProvider);
        audit.setSafetyCheckApplied(Boolean.TRUE);
        audit.setSafetyFlagged(Boolean.FALSE);
        audit.setSafetyBlocked(Boolean.FALSE);
        audit.setSafetyReason(safetyReason);
        audit.setRequestId(requestId);
        audit.setOccurredAt(occurredAt);
        aiProviderAuditRepository.save(audit);
    }

    private TenantScope resolveScope() {
        try {
            return new TenantScope(
                TenantContextHolder.requireTenantId(),
                TenantContextHolder.requireOrgId(),
                TenantContextHolder.requireUserId()
            );
        } catch (IllegalStateException ex) {
            throw new IllegalStateException(ApiErrorCode.TENANT_CONTEXT_MISSING.name(), ex);
        }
    }

    public record ProviderPlanResult(
        List<TaskBlockEmbedded> taskBlocks,
        String provider,
        String model,
        String templateVersion,
        boolean fallbackUsed,
        String fallbackProvider,
        String requestId
    ) {
    }

    private record TenantScope(String tenantId, String orgId, String userId) {
    }
}

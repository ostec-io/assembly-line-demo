package io.ostec.focusflow.controller.performance;

import io.ostec.focusflow.document.performance.DailyPerformanceRecordDocument;
import io.ostec.focusflow.document.performance.DailyRecordState;
import io.ostec.focusflow.document.performance.ScoreCorrectionAuditDocument;
import io.ostec.focusflow.document.performance.StreakLedgerDocument;
import io.ostec.focusflow.dto.request.performance.ApplyScoreCorrectionRequest;
import io.ostec.focusflow.dto.response.performance.DailyPerformanceResponse;
import io.ostec.focusflow.mapper.performance.PerformanceMapper;
import io.ostec.focusflow.repository.performance.DailyPerformanceRecordRepository;
import io.ostec.focusflow.repository.performance.ScoreCorrectionAuditRepository;
import io.ostec.focusflow.repository.performance.StreakLedgerRepository;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/performance/admin")
@Tag(name = "Performance Admin", description = "Administrative performance correction endpoints")
public class PerformanceAdminController {

    private static final int STREAK_QUALIFYING_SCORE = 65;
    private static final int MAX_TRACKED_EVENT_IDS = 500;
    private static final String FORMULA_VERSION = "focusflow-score-v1";

    private final DailyPerformanceRecordRepository dailyPerformanceRecordRepository;
    private final ScoreCorrectionAuditRepository scoreCorrectionAuditRepository;
    private final StreakLedgerRepository streakLedgerRepository;
    private final PerformanceMapper performanceMapper;

    public PerformanceAdminController(
        DailyPerformanceRecordRepository dailyPerformanceRecordRepository,
        ScoreCorrectionAuditRepository scoreCorrectionAuditRepository,
        StreakLedgerRepository streakLedgerRepository,
        PerformanceMapper performanceMapper
    ) {
        this.dailyPerformanceRecordRepository = dailyPerformanceRecordRepository;
        this.scoreCorrectionAuditRepository = scoreCorrectionAuditRepository;
        this.streakLedgerRepository = streakLedgerRepository;
        this.performanceMapper = performanceMapper;
    }

    @PostMapping("/score-corrections")
    @PreAuthorize("hasAuthority('SCOPE_performance.admin')")
    @Operation(summary = "Apply deterministic and auditable score correction")
    @Transactional
    public DailyPerformanceResponse applyScoreCorrection(@Valid @RequestBody ApplyScoreCorrectionRequest request) {
        String tenantId = TenantContextHolder.requireTenantId();
        String orgId = TenantContextHolder.requireOrgId();
        String correctedByUserId = resolveActorUserId();
        int correctedScore = normalizeCorrectedScore(request.correctedScore());
        String sourceEventId = buildDeterministicSourceEventId(tenantId, orgId, request, correctedScore);

        DailyPerformanceRecordDocument dailyRecord = dailyPerformanceRecordRepository
            .findByTenantIdAndOrgIdAndUserIdAndDate(tenantId, orgId, request.userId(), request.date())
            .orElseGet(() -> initializeDailyRecord(tenantId, orgId, request.userId(), request.date()));

        if (dailyRecord.getProcessedEventIds() != null && dailyRecord.getProcessedEventIds().contains(sourceEventId)) {
            StreakLedgerDocument existingLedger = streakLedgerRepository
                .findByTenantIdAndOrgIdAndUserId(tenantId, orgId, request.userId())
                .orElse(null);
            return performanceMapper.toDailyPerformanceResponse(dailyRecord, existingLedger);
        }

        int previousScore = safeInt(dailyRecord.getFocusScore());
        dailyRecord.setFocusScore(correctedScore);
        dailyRecord.setState(DailyRecordState.CORRECTED);
        if (dailyRecord.getFormulaVersion() == null) {
            dailyRecord.setFormulaVersion(FORMULA_VERSION);
        }
        rememberProcessedEventId(dailyRecord, sourceEventId);

        DailyPerformanceRecordDocument savedRecord = dailyPerformanceRecordRepository.save(dailyRecord);
        persistCorrectionAudit(
            tenantId,
            orgId,
            correctedByUserId,
            request,
            previousScore,
            correctedScore,
            sourceEventId
        );

        StreakLedgerDocument streakLedger = recalculateStreakLedger(tenantId, orgId, request.userId());
        return performanceMapper.toDailyPerformanceResponse(savedRecord, streakLedger);
    }

    private DailyPerformanceRecordDocument initializeDailyRecord(
        String tenantId,
        String orgId,
        String userId,
        LocalDate date
    ) {
        DailyPerformanceRecordDocument dailyRecord = new DailyPerformanceRecordDocument();
        dailyRecord.setTenantId(tenantId);
        dailyRecord.setOrgId(orgId);
        dailyRecord.setUserId(userId);
        dailyRecord.setDate(date);
        dailyRecord.setFocusScore(0);
        dailyRecord.setDeepWorkMinutes(0);
        dailyRecord.setCompletionRate(0.0);
        dailyRecord.setDistractionRate(0.0);
        dailyRecord.setState(DailyRecordState.CORRECTED);
        dailyRecord.setFormulaVersion(FORMULA_VERSION);
        dailyRecord.setProcessedEventIds(new ArrayList<>());
        return dailyRecord;
    }

    private void persistCorrectionAudit(
        String tenantId,
        String orgId,
        String correctedByUserId,
        ApplyScoreCorrectionRequest request,
        int previousScore,
        int correctedScore,
        String sourceEventId
    ) {
        ScoreCorrectionAuditDocument auditDocument = new ScoreCorrectionAuditDocument();
        auditDocument.setTenantId(tenantId);
        auditDocument.setOrgId(orgId);
        auditDocument.setUserId(request.userId());
        auditDocument.setDate(request.date());
        auditDocument.setPreviousFocusScore(previousScore);
        auditDocument.setCorrectedFocusScore(correctedScore);
        auditDocument.setReason(request.reason().trim());
        auditDocument.setCorrectedByUserId(correctedByUserId);
        auditDocument.setSourceEventId(sourceEventId);
        auditDocument.setCorrectedAt(Instant.now());

        try {
            scoreCorrectionAuditRepository.save(auditDocument);
        } catch (DuplicateKeyException ignored) {
            // Idempotency via deterministic sourceEventId.
        }
    }

    private StreakLedgerDocument recalculateStreakLedger(String tenantId, String orgId, String userId) {
        List<DailyPerformanceRecordDocument> records = dailyPerformanceRecordRepository
            .findByTenantIdAndOrgIdAndUserIdOrderByDateAsc(tenantId, orgId, userId);

        int currentStreak = 0;
        int longestStreak = 0;
        LocalDate lastQualifiedDate = null;
        LocalDate previousQualifiedDate = null;

        for (DailyPerformanceRecordDocument record : records) {
            if (record.getDate() == null) {
                continue;
            }

            if (safeInt(record.getFocusScore()) >= STREAK_QUALIFYING_SCORE) {
                if (previousQualifiedDate != null && record.getDate().equals(previousQualifiedDate.plusDays(1))) {
                    currentStreak += 1;
                } else {
                    currentStreak = 1;
                }

                previousQualifiedDate = record.getDate();
                lastQualifiedDate = record.getDate();
                longestStreak = Math.max(longestStreak, currentStreak);
            } else if (previousQualifiedDate != null && !record.getDate().isBefore(previousQualifiedDate)) {
                currentStreak = 0;
            }
        }

        StreakLedgerDocument streakLedger = streakLedgerRepository
            .findByTenantIdAndOrgIdAndUserId(tenantId, orgId, userId)
            .orElseGet(() -> initializeStreakLedger(tenantId, orgId, userId));

        streakLedger.setCurrentStreak(currentStreak);
        streakLedger.setLongestStreak(longestStreak);
        streakLedger.setLastQualifiedDate(lastQualifiedDate);
        return streakLedgerRepository.save(streakLedger);
    }

    private StreakLedgerDocument initializeStreakLedger(String tenantId, String orgId, String userId) {
        StreakLedgerDocument streakLedger = new StreakLedgerDocument();
        streakLedger.setTenantId(tenantId);
        streakLedger.setOrgId(orgId);
        streakLedger.setUserId(userId);
        streakLedger.setCurrentStreak(0);
        streakLedger.setLongestStreak(0);
        streakLedger.setProcessedEventIds(new ArrayList<>());
        return streakLedger;
    }

    private void rememberProcessedEventId(DailyPerformanceRecordDocument dailyRecord, String sourceEventId) {
        if (dailyRecord.getProcessedEventIds() == null) {
            dailyRecord.setProcessedEventIds(new ArrayList<>());
        }

        if (dailyRecord.getProcessedEventIds().contains(sourceEventId)) {
            return;
        }

        dailyRecord.getProcessedEventIds().add(sourceEventId);
        if (dailyRecord.getProcessedEventIds().size() > MAX_TRACKED_EVENT_IDS) {
            int overflow = dailyRecord.getProcessedEventIds().size() - MAX_TRACKED_EVENT_IDS;
            dailyRecord.getProcessedEventIds().subList(0, overflow).clear();
        }
    }

    private String buildDeterministicSourceEventId(
        String tenantId,
        String orgId,
        ApplyScoreCorrectionRequest request,
        int correctedScore
    ) {
        String canonicalReason = request.reason().trim();
        String material = tenantId
            + ":"
            + orgId
            + ":"
            + request.userId().trim()
            + ":"
            + request.date()
            + ":"
            + correctedScore
            + ":"
            + canonicalReason;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private int normalizeCorrectedScore(BigDecimal correctedScore) {
        int rounded = correctedScore.setScale(0, RoundingMode.HALF_UP).intValue();
        return Math.clamp(rounded, 0, 100);
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }

    private String resolveActorUserId() {
        try {
            return TenantContextHolder.requireUserId();
        } catch (IllegalStateException ex) {
            return "system";
        }
    }
}

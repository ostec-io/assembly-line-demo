package io.ostec.focusflow.service.performance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.audit.AuditLogService;
import io.ostec.focusflow.document.performance.DailyPerformanceRecordDocument;
import io.ostec.focusflow.document.performance.DailyRecordState;
import io.ostec.focusflow.document.performance.ScoreCorrectionAuditDocument;
import io.ostec.focusflow.document.performance.StreakLedgerDocument;
import io.ostec.focusflow.event.model.performance.DailyRecordFinalizedEvent;
import io.ostec.focusflow.event.model.performance.DailyScoreCalculatedEvent;
import io.ostec.focusflow.event.model.performance.DailyScoreCorrectedEvent;
import io.ostec.focusflow.event.model.performance.StreakBrokenEvent;
import io.ostec.focusflow.event.model.performance.StreakExtendedEvent;
import io.ostec.focusflow.event.publisher.OutboxPublisherService;
import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.focusflow.metrics.FocusMetricsService;
import io.ostec.focusflow.repository.performance.DailyPerformanceRecordRepository;
import io.ostec.focusflow.repository.performance.ScoreCorrectionAuditRepository;
import io.ostec.focusflow.repository.performance.StreakLedgerRepository;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class FocusScoreCalculationService {

    private static final Logger log = LoggerFactory.getLogger(FocusScoreCalculationService.class);

    private static final String DAILY_RECORD_AGGREGATE_TYPE = "DailyPerformanceRecord";
    private static final String STREAK_AGGREGATE_TYPE = "StreakLedger";
    private static final String EVENT_VERSION = "1";
    private static final String FORMULA_VERSION = "focusflow-score-v1";
    private static final int STREAK_QUALIFYING_SCORE = 65;
    private static final int MAX_TRACKED_EVENT_IDS = 500;

    private final DailyPerformanceRecordRepository dailyPerformanceRecordRepository;
    private final StreakLedgerRepository streakLedgerRepository;
    private final ScoreCorrectionAuditRepository scoreCorrectionAuditRepository;
    private final OutboxPublisherService outboxPublisherService;
    private final PerformanceProjectionService performanceProjectionService;
    private final FocusMetricsService focusMetricsService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public FocusScoreCalculationService(
        DailyPerformanceRecordRepository dailyPerformanceRecordRepository,
        StreakLedgerRepository streakLedgerRepository,
        ScoreCorrectionAuditRepository scoreCorrectionAuditRepository,
        OutboxPublisherService outboxPublisherService,
        PerformanceProjectionService performanceProjectionService,
        FocusMetricsService focusMetricsService,
        AuditLogService auditLogService,
        ObjectMapper objectMapper
    ) {
        this.dailyPerformanceRecordRepository = dailyPerformanceRecordRepository;
        this.streakLedgerRepository = streakLedgerRepository;
        this.scoreCorrectionAuditRepository = scoreCorrectionAuditRepository;
        this.outboxPublisherService = outboxPublisherService;
        this.performanceProjectionService = performanceProjectionService;
        this.focusMetricsService = focusMetricsService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CalculationOutcome calculateDailyScore(
        String sourceEventId,
        String sourceEventType,
        Instant occurredAt,
        Integer deepWorkMinutes,
        String summary,
        ZoneId zoneId,
        String correlationId,
        String causationId
    ) {
        long startedAtNanos = System.nanoTime();
        try {
            TenantScope scope = resolveScope();
            String normalizedSourceEventType = normalizeEventType(sourceEventType);
            Instant effectiveOccurredAt = occurredAt != null ? occurredAt : Instant.now();
            ZoneId effectiveZone = zoneId != null ? zoneId : ZoneOffset.UTC;
            LocalDate scoreDate = effectiveOccurredAt.atZone(effectiveZone).toLocalDate();

            String effectiveSourceEventId = resolveSourceEventId(
                sourceEventId,
                normalizedSourceEventType,
                scope.userId(),
                effectiveOccurredAt
            );
            String effectiveCausationId = StringUtils.hasText(causationId) ? causationId : effectiveSourceEventId;

            DailyPerformanceRecordDocument dailyRecord = loadOrCreateDailyRecord(scope, scoreDate);
            if (hasProcessedEventId(dailyRecord.getProcessedEventIds(), effectiveSourceEventId)) {
                return new CalculationOutcome(dailyRecord, snapshotTransition(scope), true);
            }

            if (dailyRecord.getState() == DailyRecordState.FINALIZED || dailyRecord.getState() == DailyRecordState.CORRECTED) {
                return processCorrection(
                    scope,
                    dailyRecord,
                    effectiveSourceEventId,
                    normalizedSourceEventType,
                    effectiveOccurredAt,
                    deepWorkMinutes,
                    summary,
                    effectiveZone,
                    correlationId,
                    effectiveCausationId
                );
            }

            applyEventContribution(dailyRecord, normalizedSourceEventType, deepWorkMinutes, summary);
            dailyRecord.setFocusScore(calculateFocusScore(dailyRecord));
            dailyRecord.setFormulaVersion(FORMULA_VERSION);
            dailyRecord.setState(DailyRecordState.SCORED);
            rememberProcessedEventId(dailyRecord.getProcessedEventIds(), effectiveSourceEventId);

            DailyPerformanceRecordDocument savedRecord = dailyPerformanceRecordRepository.save(dailyRecord);
            publishDailyScoreCalculated(
                scope,
                savedRecord,
                effectiveSourceEventId,
                normalizedSourceEventType,
                correlationId,
                effectiveCausationId
            );

            StreakTransition transition = snapshotTransition(scope);
            if (shouldFinalize(savedRecord.getDate(), effectiveZone)) {
                savedRecord.setState(DailyRecordState.FINALIZED);
                savedRecord = dailyPerformanceRecordRepository.save(savedRecord);
                publishDailyRecordFinalized(scope, savedRecord, effectiveSourceEventId, correlationId, effectiveCausationId);
                transition = computeStreakTransition(effectiveSourceEventId, savedRecord, correlationId, effectiveCausationId);
            }

            performanceProjectionService.updateDashboardProjection(savedRecord.getDate(), effectiveSourceEventId);
            return new CalculationOutcome(savedRecord, transition, false);
        } finally {
            focusMetricsService.recordPerformanceScoreCalcLatency(
                Duration.ofNanos(System.nanoTime() - startedAtNanos)
            );
        }
    }

    @Transactional
    public CalculationOutcome recalculateOnCorrection(
        String sourceEventId,
        String sourceEventType,
        Instant occurredAt,
        Integer deepWorkMinutes,
        String summary,
        ZoneId zoneId,
        String correlationId,
        String causationId
    ) {
        long startedAtNanos = System.nanoTime();
        try {
            TenantScope scope = resolveScope();
            String normalizedSourceEventType = normalizeEventType(sourceEventType);
            Instant effectiveOccurredAt = occurredAt != null ? occurredAt : Instant.now();
            ZoneId effectiveZone = zoneId != null ? zoneId : ZoneOffset.UTC;
            LocalDate scoreDate = effectiveOccurredAt.atZone(effectiveZone).toLocalDate();

            String effectiveSourceEventId = resolveSourceEventId(
                sourceEventId,
                normalizedSourceEventType,
                scope.userId(),
                effectiveOccurredAt
            );
            String effectiveCausationId = StringUtils.hasText(causationId) ? causationId : effectiveSourceEventId;

            DailyPerformanceRecordDocument dailyRecord = loadOrCreateDailyRecord(scope, scoreDate);
            return processCorrection(
                scope,
                dailyRecord,
                effectiveSourceEventId,
                normalizedSourceEventType,
                effectiveOccurredAt,
                deepWorkMinutes,
                summary,
                effectiveZone,
                correlationId,
                effectiveCausationId
            );
        } finally {
            focusMetricsService.recordPerformanceScoreCalcLatency(
                Duration.ofNanos(System.nanoTime() - startedAtNanos)
            );
        }
    }

    @Transactional
    public StreakTransition computeStreakTransition(
        String sourceEventId,
        DailyPerformanceRecordDocument dailyRecord,
        String correlationId,
        String causationId
    ) {
        TenantScope scope = resolveScope();

        if (dailyRecord == null || dailyRecord.getDate() == null) {
            return snapshotTransition(scope);
        }

        String effectiveSourceEventId = resolveSourceEventId(
            sourceEventId,
            "StreakTransition",
            scope.userId(),
            Instant.now()
        );

        StreakLedgerDocument streakLedger = loadOrCreateStreakLedger(scope);
        if (hasProcessedEventId(streakLedger.getProcessedEventIds(), effectiveSourceEventId)) {
            return new StreakTransition(
                StreakTransitionType.NONE,
                safeInt(streakLedger.getCurrentStreak()),
                safeInt(streakLedger.getCurrentStreak()),
                safeInt(streakLedger.getLongestStreak()),
                null
            );
        }

        int previousCurrentStreak = safeInt(streakLedger.getCurrentStreak());
        int previousLongestStreak = safeInt(streakLedger.getLongestStreak());
        LocalDate previousLastQualifiedDate = streakLedger.getLastQualifiedDate();

        int newCurrentStreak = previousCurrentStreak;
        int newLongestStreak = previousLongestStreak;
        StreakTransitionType transitionType = StreakTransitionType.NONE;
        String reason = null;

        boolean qualifiesForStreak = safeInt(dailyRecord.getFocusScore()) >= STREAK_QUALIFYING_SCORE;
        if (qualifiesForStreak) {
            if (previousLastQualifiedDate == null) {
                newCurrentStreak = 1;
                transitionType = StreakTransitionType.EXTENDED;
                streakLedger.setLastQualifiedDate(dailyRecord.getDate());
            } else if (dailyRecord.getDate().isAfter(previousLastQualifiedDate)) {
                if (dailyRecord.getDate().equals(previousLastQualifiedDate.plusDays(1))) {
                    newCurrentStreak = previousCurrentStreak + 1;
                } else {
                    newCurrentStreak = 1;
                }
                transitionType = StreakTransitionType.EXTENDED;
                streakLedger.setLastQualifiedDate(dailyRecord.getDate());
            } else if (dailyRecord.getDate().equals(previousLastQualifiedDate) && previousCurrentStreak == 0) {
                newCurrentStreak = 1;
                transitionType = StreakTransitionType.EXTENDED;
            }

            newLongestStreak = Math.max(previousLongestStreak, newCurrentStreak);
            streakLedger.setCurrentStreak(newCurrentStreak);
            streakLedger.setLongestStreak(newLongestStreak);
        } else if (previousCurrentStreak > 0
            && previousLastQualifiedDate != null
            && !dailyRecord.getDate().isBefore(previousLastQualifiedDate)) {
            transitionType = StreakTransitionType.BROKEN;
            reason = dailyRecord.getState() == DailyRecordState.CORRECTED
                ? "Corrected score dropped below streak threshold"
                : "Daily score dropped below streak threshold";
            newCurrentStreak = 0;
            streakLedger.setCurrentStreak(0);
        }

        rememberProcessedEventId(streakLedger.getProcessedEventIds(), effectiveSourceEventId);
        StreakLedgerDocument savedLedger = streakLedgerRepository.save(streakLedger);

        String effectiveCausationId = StringUtils.hasText(causationId) ? causationId : effectiveSourceEventId;
        if (transitionType == StreakTransitionType.EXTENDED) {
            publishStreakExtended(scope, savedLedger, dailyRecord, effectiveSourceEventId, correlationId, effectiveCausationId);
        } else if (transitionType == StreakTransitionType.BROKEN) {
            publishStreakBroken(
                scope,
                savedLedger,
                dailyRecord,
                previousCurrentStreak,
                reason,
                effectiveSourceEventId,
                correlationId,
                effectiveCausationId
            );
        }

        return new StreakTransition(
            transitionType,
            previousCurrentStreak,
            safeInt(savedLedger.getCurrentStreak()),
            safeInt(savedLedger.getLongestStreak()),
            reason
        );
    }

    private CalculationOutcome processCorrection(
        TenantScope scope,
        DailyPerformanceRecordDocument dailyRecord,
        String sourceEventId,
        String sourceEventType,
        Instant occurredAt,
        Integer deepWorkMinutes,
        String summary,
        ZoneId zoneId,
        String correlationId,
        String causationId
    ) {
        if (hasProcessedEventId(dailyRecord.getProcessedEventIds(), sourceEventId)) {
            return new CalculationOutcome(dailyRecord, snapshotTransition(scope), true);
        }

        int previousFocusScore = safeInt(dailyRecord.getFocusScore());
        applyEventContribution(dailyRecord, sourceEventType, deepWorkMinutes, summary);
        dailyRecord.setFocusScore(calculateFocusScore(dailyRecord));
        dailyRecord.setFormulaVersion(FORMULA_VERSION);
        dailyRecord.setState(DailyRecordState.CORRECTED);
        rememberProcessedEventId(dailyRecord.getProcessedEventIds(), sourceEventId);

        DailyPerformanceRecordDocument savedRecord = dailyPerformanceRecordRepository.save(dailyRecord);
        String correctionReason = resolveCorrectionReason(sourceEventType, summary);

        persistCorrectionAudit(
            scope,
            savedRecord,
            previousFocusScore,
            safeInt(savedRecord.getFocusScore()),
            correctionReason,
            sourceEventId,
            occurredAt
        );

        publishDailyScoreCorrected(
            scope,
            savedRecord,
            previousFocusScore,
            safeInt(savedRecord.getFocusScore()),
            correctionReason,
            sourceEventId,
            correlationId,
            causationId
        );

        StreakTransition transition = snapshotTransition(scope);
        if (shouldFinalize(savedRecord.getDate(), zoneId)) {
            transition = computeStreakTransition(sourceEventId, savedRecord, correlationId, causationId);
        }

        performanceProjectionService.updateDashboardProjection(savedRecord.getDate(), sourceEventId);
        return new CalculationOutcome(savedRecord, transition, false);
    }

    private DailyPerformanceRecordDocument loadOrCreateDailyRecord(TenantScope scope, LocalDate date) {
        return dailyPerformanceRecordRepository
            .findByTenantIdAndOrgIdAndUserIdAndDate(scope.tenantId(), scope.orgId(), scope.userId(), date)
            .orElseGet(() -> {
                DailyPerformanceRecordDocument document = new DailyPerformanceRecordDocument();
                document.setTenantId(scope.tenantId());
                document.setOrgId(scope.orgId());
                document.setUserId(scope.userId());
                document.setDate(date);
                document.setFocusScore(0);
                document.setDeepWorkMinutes(0);
                document.setCompletionRate(0.0);
                document.setDistractionRate(0.0);
                document.setFormulaVersion(FORMULA_VERSION);
                document.setState(DailyRecordState.ACCUMULATING);
                document.setProcessedEventIds(new ArrayList<>());
                return document;
            });
    }

    private StreakLedgerDocument loadOrCreateStreakLedger(TenantScope scope) {
        return streakLedgerRepository
            .findByTenantIdAndOrgIdAndUserId(scope.tenantId(), scope.orgId(), scope.userId())
            .orElseGet(() -> {
                StreakLedgerDocument document = new StreakLedgerDocument();
                document.setTenantId(scope.tenantId());
                document.setOrgId(scope.orgId());
                document.setUserId(scope.userId());
                document.setCurrentStreak(0);
                document.setLongestStreak(0);
                document.setProcessedEventIds(new ArrayList<>());
                return document;
            });
    }

    private void persistCorrectionAudit(
        TenantScope scope,
        DailyPerformanceRecordDocument dailyRecord,
        int previousFocusScore,
        int correctedFocusScore,
        String reason,
        String sourceEventId,
        Instant occurredAt
    ) {
        ScoreCorrectionAuditDocument auditDocument = new ScoreCorrectionAuditDocument();
        auditDocument.setTenantId(scope.tenantId());
        auditDocument.setOrgId(scope.orgId());
        auditDocument.setUserId(scope.userId());
        auditDocument.setDate(dailyRecord.getDate());
        auditDocument.setPreviousFocusScore(previousFocusScore);
        auditDocument.setCorrectedFocusScore(correctedFocusScore);
        auditDocument.setReason(reason);
        auditDocument.setCorrectedByUserId(resolveCurrentUserId());
        auditDocument.setSourceEventId(sourceEventId);
        auditDocument.setCorrectedAt(occurredAt != null ? occurredAt : Instant.now());

        try {
            scoreCorrectionAuditRepository.save(auditDocument);
            auditLogService.logScoreCorrection(
                scope.tenantId(),
                scope.orgId(),
                scope.userId(),
                dailyRecord.getDate(),
                previousFocusScore,
                correctedFocusScore,
                reason,
                auditDocument.getCorrectedByUserId(),
                sourceEventId,
                resolveCorrelationId(sourceEventId)
            );
        } catch (DuplicateKeyException ex) {
            log.info(
                "Ignoring duplicate correction audit for sourceEventId={}, tenantId={}, orgId={}",
                sourceEventId,
                scope.tenantId(),
                scope.orgId()
            );
        }
    }

    private void publishDailyScoreCalculated(
        TenantScope scope,
        DailyPerformanceRecordDocument dailyRecord,
        String sourceEventId,
        String sourceEventType,
        String correlationId,
        String causationId
    ) {
        String aggregateKey = scope.userId() + ":" + dailyRecord.getDate();
        String eventId = deterministicEventId(sourceEventId, "DailyScoreCalculated", aggregateKey);

        DailyScoreCalculatedEvent event = new DailyScoreCalculatedEvent(
            eventId,
            Instant.now(),
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            dailyRecord.getDate(),
            sourceEventId,
            sourceEventType,
            dailyRecord.getFocusScore(),
            dailyRecord.getDeepWorkMinutes(),
            dailyRecord.getCompletionRate(),
            dailyRecord.getDistractionRate(),
            dailyRecord.getFormulaVersion(),
            dailyRecord.getState()
        );

        outboxPublisherService.enqueueWithEventId(
            eventId,
            DAILY_RECORD_AGGREGATE_TYPE,
            aggregateKey,
            "DailyScoreCalculated",
            EVENT_VERSION,
            toJson(event),
            resolveCorrelationId(correlationId),
            StringUtils.hasText(causationId) ? causationId : sourceEventId
        );
    }

    private void publishDailyRecordFinalized(
        TenantScope scope,
        DailyPerformanceRecordDocument dailyRecord,
        String sourceEventId,
        String correlationId,
        String causationId
    ) {
        String aggregateKey = scope.userId() + ":" + dailyRecord.getDate();
        String eventId = deterministicEventId(sourceEventId, "DailyRecordFinalized", aggregateKey);

        DailyRecordFinalizedEvent event = new DailyRecordFinalizedEvent(
            eventId,
            Instant.now(),
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            dailyRecord.getDate(),
            dailyRecord.getFocusScore(),
            dailyRecord.getDeepWorkMinutes(),
            dailyRecord.getCompletionRate(),
            dailyRecord.getDistractionRate(),
            dailyRecord.getState(),
            sourceEventId
        );

        outboxPublisherService.enqueueWithEventId(
            eventId,
            DAILY_RECORD_AGGREGATE_TYPE,
            aggregateKey,
            "DailyRecordFinalized",
            EVENT_VERSION,
            toJson(event),
            resolveCorrelationId(correlationId),
            StringUtils.hasText(causationId) ? causationId : sourceEventId
        );
    }

    private void publishDailyScoreCorrected(
        TenantScope scope,
        DailyPerformanceRecordDocument dailyRecord,
        int previousScore,
        int correctedScore,
        String reason,
        String sourceEventId,
        String correlationId,
        String causationId
    ) {
        String aggregateKey = scope.userId() + ":" + dailyRecord.getDate();
        String eventId = deterministicEventId(sourceEventId, "DailyScoreCorrected", aggregateKey);

        DailyScoreCorrectedEvent event = new DailyScoreCorrectedEvent(
            eventId,
            Instant.now(),
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            dailyRecord.getDate(),
            previousScore,
            correctedScore,
            reason,
            resolveCurrentUserId(),
            sourceEventId
        );

        outboxPublisherService.enqueueWithEventId(
            eventId,
            DAILY_RECORD_AGGREGATE_TYPE,
            aggregateKey,
            "DailyScoreCorrected",
            EVENT_VERSION,
            toJson(event),
            resolveCorrelationId(correlationId),
            StringUtils.hasText(causationId) ? causationId : sourceEventId
        );
    }

    private void publishStreakExtended(
        TenantScope scope,
        StreakLedgerDocument streakLedger,
        DailyPerformanceRecordDocument dailyRecord,
        String sourceEventId,
        String correlationId,
        String causationId
    ) {
        String eventId = deterministicEventId(sourceEventId, "StreakExtended", scope.userId());

        StreakExtendedEvent event = new StreakExtendedEvent(
            eventId,
            Instant.now(),
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            dailyRecord.getDate(),
            streakLedger.getCurrentStreak(),
            streakLedger.getLongestStreak(),
            sourceEventId
        );

        outboxPublisherService.enqueueWithEventId(
            eventId,
            STREAK_AGGREGATE_TYPE,
            scope.userId(),
            "StreakExtended",
            EVENT_VERSION,
            toJson(event),
            resolveCorrelationId(correlationId),
            StringUtils.hasText(causationId) ? causationId : sourceEventId
        );
    }

    private void publishStreakBroken(
        TenantScope scope,
        StreakLedgerDocument streakLedger,
        DailyPerformanceRecordDocument dailyRecord,
        int previousStreak,
        String reason,
        String sourceEventId,
        String correlationId,
        String causationId
    ) {
        String eventId = deterministicEventId(sourceEventId, "StreakBroken", scope.userId());

        StreakBrokenEvent event = new StreakBrokenEvent(
            eventId,
            Instant.now(),
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            dailyRecord.getDate(),
            previousStreak,
            streakLedger.getCurrentStreak(),
            reason,
            sourceEventId
        );

        outboxPublisherService.enqueueWithEventId(
            eventId,
            STREAK_AGGREGATE_TYPE,
            scope.userId(),
            "StreakBroken",
            EVENT_VERSION,
            toJson(event),
            resolveCorrelationId(correlationId),
            StringUtils.hasText(causationId) ? causationId : sourceEventId
        );
    }

    private void applyEventContribution(
        DailyPerformanceRecordDocument dailyRecord,
        String sourceEventType,
        Integer deepWorkMinutes,
        String summary
    ) {
        int currentDeepWorkMinutes = safeInt(dailyRecord.getDeepWorkMinutes());
        double currentCompletionRate = normalizeRate(dailyRecord.getCompletionRate());
        double currentDistractionRate = normalizeRate(dailyRecord.getDistractionRate());
        int normalizedDeepWorkMinutes = normalizeDeepWorkMinutes(deepWorkMinutes);

        switch (sourceEventType) {
            case "FocusSessionCompleted", "ParticipantResultSubmitted" -> {
                dailyRecord.setDeepWorkMinutes(currentDeepWorkMinutes + normalizedDeepWorkMinutes);
                double completionIncrement = normalizedDeepWorkMinutes > 0
                    ? Math.min(0.35, 0.10 + (normalizedDeepWorkMinutes / 240.0))
                    : 0.08;
                dailyRecord.setCompletionRate(normalizeRate(currentCompletionRate + completionIncrement));
                dailyRecord.setDistractionRate(normalizeRate(currentDistractionRate - 0.03));
            }
            case "FocusSessionInterrupted" -> {
                dailyRecord.setDistractionRate(normalizeRate(currentDistractionRate + 0.20));
                dailyRecord.setCompletionRate(normalizeRate(currentCompletionRate - 0.05));
            }
            case "FocusSessionStarted" -> {
                dailyRecord.setCompletionRate(normalizeRate(Math.max(currentCompletionRate, 0.02)));
                dailyRecord.setDistractionRate(normalizeRate(currentDistractionRate));
            }
            default -> {
                if (normalizedDeepWorkMinutes > 0) {
                    dailyRecord.setDeepWorkMinutes(currentDeepWorkMinutes + normalizedDeepWorkMinutes);
                }
                if (StringUtils.hasText(summary)) {
                    dailyRecord.setCompletionRate(normalizeRate(currentCompletionRate + 0.02));
                }
            }
        }
    }

    private int calculateFocusScore(DailyPerformanceRecordDocument dailyRecord) {
        int deepWorkMinutes = safeInt(dailyRecord.getDeepWorkMinutes());
        double completionRate = normalizeRate(dailyRecord.getCompletionRate());
        double distractionRate = normalizeRate(dailyRecord.getDistractionRate());

        double deepWorkComponent = (Math.min(deepWorkMinutes, 240) / 240.0) * 70.0;
        double completionComponent = completionRate * 30.0;
        double distractionPenalty = distractionRate * 25.0;

        int score = (int) Math.round(deepWorkComponent + completionComponent - distractionPenalty);
        return Math.clamp(score, 0, 100);
    }

    private StreakTransition snapshotTransition(TenantScope scope) {
        StreakLedgerDocument streakLedger = streakLedgerRepository
            .findByTenantIdAndOrgIdAndUserId(scope.tenantId(), scope.orgId(), scope.userId())
            .orElse(null);

        if (streakLedger == null) {
            return new StreakTransition(StreakTransitionType.NONE, 0, 0, 0, null);
        }

        return new StreakTransition(
            StreakTransitionType.NONE,
            safeInt(streakLedger.getCurrentStreak()),
            safeInt(streakLedger.getCurrentStreak()),
            safeInt(streakLedger.getLongestStreak()),
            null
        );
    }

    private boolean shouldFinalize(LocalDate scoreDate, ZoneId zoneId) {
        if (scoreDate == null) {
            return false;
        }

        ZoneId effectiveZone = zoneId != null ? zoneId : ZoneOffset.UTC;
        return LocalDate.now(effectiveZone).isAfter(scoreDate);
    }

    private boolean hasProcessedEventId(List<String> processedEventIds, String eventId) {
        return StringUtils.hasText(eventId) && processedEventIds != null && processedEventIds.contains(eventId);
    }

    private void rememberProcessedEventId(List<String> processedEventIds, String eventId) {
        if (processedEventIds == null || !StringUtils.hasText(eventId) || processedEventIds.contains(eventId)) {
            return;
        }

        processedEventIds.add(eventId);
        if (processedEventIds.size() > MAX_TRACKED_EVENT_IDS) {
            int overflow = processedEventIds.size() - MAX_TRACKED_EVENT_IDS;
            processedEventIds.subList(0, overflow).clear();
        }
    }

    private int normalizeDeepWorkMinutes(Integer deepWorkMinutes) {
        if (deepWorkMinutes == null) {
            return 0;
        }
        return Math.clamp(deepWorkMinutes, 0, 720);
    }

    private double normalizeRate(Double value) {
        if (value == null || Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(value, 1.0));
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }

    private String resolveSourceEventId(String sourceEventId, String eventType, String userId, Instant occurredAt) {
        if (StringUtils.hasText(sourceEventId)) {
            return sourceEventId;
        }

        Instant effectiveOccurredAt = occurredAt != null ? occurredAt : Instant.now();
        String material = userId + ":" + eventType + ":" + effectiveOccurredAt;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String deterministicEventId(String sourceEventId, String eventType, String aggregateKey) {
        String material = sourceEventId + ":" + eventType + ":" + aggregateKey;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String resolveCorrelationId(String preferredCorrelationId) {
        if (StringUtils.hasText(preferredCorrelationId)) {
            return preferredCorrelationId;
        }

        String correlationIdFromContext = TenantContextHolder.getCorrelationId();
        if (StringUtils.hasText(correlationIdFromContext)) {
            return correlationIdFromContext;
        }

        return UUID.randomUUID().toString();
    }

    private String resolveCorrectionReason(String sourceEventType, String summary) {
        if (StringUtils.hasText(summary)) {
            return "Late-arriving " + sourceEventType + " event: " + summary.trim();
        }

        return "Late-arriving " + sourceEventType + " event";
    }

    private String resolveCurrentUserId() {
        try {
            return TenantContextHolder.requireUserId();
        } catch (IllegalStateException ignored) {
            return "system";
        }
    }

    private String normalizeEventType(String rawType) {
        if (!StringUtils.hasText(rawType)) {
            return "Unknown";
        }

        String normalizedType = rawType;
        int lastDotIndex = normalizedType.lastIndexOf('.');
        if (lastDotIndex >= 0 && lastDotIndex < normalizedType.length() - 1) {
            normalizedType = normalizedType.substring(lastDotIndex + 1);
        }

        if (normalizedType.endsWith("Event")) {
            normalizedType = normalizedType.substring(0, normalizedType.length() - "Event".length());
        }

        return normalizedType;
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize performance event payload", ex);
        }
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

    private record TenantScope(String tenantId, String orgId, String userId) {
    }

    public record CalculationOutcome(
        DailyPerformanceRecordDocument dailyRecord,
        StreakTransition streakTransition,
        boolean idempotent
    ) {
    }

    public record StreakTransition(
        StreakTransitionType type,
        int previousStreak,
        int currentStreak,
        int longestStreak,
        String reason
    ) {
    }

    public enum StreakTransitionType {
        NONE,
        EXTENDED,
        BROKEN
    }
}

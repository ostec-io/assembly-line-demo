package io.ostec.focusflow.service.performance;

import io.ostec.focusflow.document.performance.DailyPerformanceRecordDocument;
import io.ostec.focusflow.document.performance.DailyRecordState;
import io.ostec.focusflow.document.performance.StreakLedgerDocument;
import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.focusflow.repository.performance.DailyPerformanceRecordRepository;
import io.ostec.focusflow.repository.performance.StreakLedgerRepository;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class PerformanceQueryService {

    private final DailyPerformanceRecordRepository dailyPerformanceRecordRepository;
    private final StreakLedgerRepository streakLedgerRepository;

    public PerformanceQueryService(
        DailyPerformanceRecordRepository dailyPerformanceRecordRepository,
        StreakLedgerRepository streakLedgerRepository
    ) {
        this.dailyPerformanceRecordRepository = dailyPerformanceRecordRepository;
        this.streakLedgerRepository = streakLedgerRepository;
    }

    public DailyPerformanceView getDailyPerformance(LocalDate date) {
        TenantScope scope = resolveScope();
        LocalDate effectiveDate = date != null ? date : LocalDate.now(ZoneOffset.UTC);

        Optional<DailyPerformanceRecordDocument> record = dailyPerformanceRecordRepository
            .findByTenantIdAndOrgIdAndUserIdAndDate(
                scope.tenantId(),
                scope.orgId(),
                scope.userId(),
                effectiveDate
            );

        StreakLedgerDocument streakLedger = streakLedgerRepository
            .findByTenantIdAndOrgIdAndUserId(scope.tenantId(), scope.orgId(), scope.userId())
            .orElse(null);

        return new DailyPerformanceView(
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            effectiveDate,
            record.map(DailyPerformanceRecordDocument::getFocusScore).orElse(0),
            record.map(DailyPerformanceRecordDocument::getDeepWorkMinutes).orElse(0),
            record.map(DailyPerformanceRecordDocument::getCompletionRate).orElse(0.0),
            record.map(DailyPerformanceRecordDocument::getDistractionRate).orElse(0.0),
            record.map(DailyPerformanceRecordDocument::getState).orElse(DailyRecordState.ACCUMULATING),
            streakLedger != null ? safeInt(streakLedger.getCurrentStreak()) : 0,
            streakLedger != null ? safeInt(streakLedger.getLongestStreak()) : 0,
            streakLedger != null ? streakLedger.getLastQualifiedDate() : null
        );
    }

    public WeeklyAnalytics getWeeklyAnalytics(LocalDate anchorDate) {
        TenantScope scope = resolveScope();
        LocalDate effectiveAnchorDate = anchorDate != null ? anchorDate : LocalDate.now(ZoneOffset.UTC);
        LocalDate fromDate = effectiveAnchorDate.minusDays(6);

        List<DailyPerformanceRecordDocument> records = dailyPerformanceRecordRepository
            .findByTenantIdAndOrgIdAndUserIdAndDateBetween(
                scope.tenantId(),
                scope.orgId(),
                scope.userId(),
                fromDate,
                effectiveAnchorDate
            )
            .stream()
            .sorted(Comparator.comparing(DailyPerformanceRecordDocument::getDate))
            .toList();

        Map<LocalDate, DailyPerformanceRecordDocument> recordsByDate = records.stream()
            .collect(Collectors.toMap(DailyPerformanceRecordDocument::getDate, record -> record, (left, right) -> right));

        List<DailySnapshot> dailySnapshots = IntStream.range(0, 7)
            .mapToObj(dayOffset -> fromDate.plusDays(dayOffset))
            .map(date -> toDailySnapshot(date, recordsByDate.get(date)))
            .toList();

        int scoredDays = (int) records.stream().filter(record -> record.getFocusScore() != null).count();
        double averageFocusScore = scoredDays > 0
            ? records.stream().mapToInt(record -> safeInt(record.getFocusScore())).average().orElse(0.0)
            : 0.0;
        double averageCompletionRate = records.isEmpty()
            ? 0.0
            : records.stream().mapToDouble(record -> normalizeRate(record.getCompletionRate())).average().orElse(0.0);
        double averageDistractionRate = records.isEmpty()
            ? 0.0
            : records.stream().mapToDouble(record -> normalizeRate(record.getDistractionRate())).average().orElse(0.0);
        int totalDeepWorkMinutes = records.stream().mapToInt(record -> safeInt(record.getDeepWorkMinutes())).sum();

        StreakLedgerDocument streakLedger = streakLedgerRepository
            .findByTenantIdAndOrgIdAndUserId(scope.tenantId(), scope.orgId(), scope.userId())
            .orElse(null);

        return new WeeklyAnalytics(
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            fromDate,
            effectiveAnchorDate,
            scoredDays,
            averageFocusScore,
            totalDeepWorkMinutes,
            averageCompletionRate,
            averageDistractionRate,
            streakLedger != null ? safeInt(streakLedger.getCurrentStreak()) : 0,
            streakLedger != null ? safeInt(streakLedger.getLongestStreak()) : 0,
            dailySnapshots
        );
    }

    private DailySnapshot toDailySnapshot(LocalDate date, DailyPerformanceRecordDocument record) {
        if (record == null) {
            return new DailySnapshot(date, 0, 0, 0.0, 0.0, DailyRecordState.ACCUMULATING);
        }

        return new DailySnapshot(
            date,
            safeInt(record.getFocusScore()),
            safeInt(record.getDeepWorkMinutes()),
            normalizeRate(record.getCompletionRate()),
            normalizeRate(record.getDistractionRate()),
            record.getState() != null ? record.getState() : DailyRecordState.ACCUMULATING
        );
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

    public record DailyPerformanceView(
        String tenantId,
        String orgId,
        String userId,
        LocalDate date,
        Integer focusScore,
        Integer deepWorkMinutes,
        Double completionRate,
        Double distractionRate,
        DailyRecordState state,
        Integer currentStreak,
        Integer longestStreak,
        LocalDate lastQualifiedDate
    ) {
    }

    public record WeeklyAnalytics(
        String tenantId,
        String orgId,
        String userId,
        LocalDate fromDate,
        LocalDate toDate,
        int scoredDays,
        double averageFocusScore,
        int totalDeepWorkMinutes,
        double averageCompletionRate,
        double averageDistractionRate,
        int currentStreak,
        int longestStreak,
        List<DailySnapshot> dailySnapshots
    ) {
    }

    public record DailySnapshot(
        LocalDate date,
        int focusScore,
        int deepWorkMinutes,
        double completionRate,
        double distractionRate,
        DailyRecordState state
    ) {
    }

    private record TenantScope(String tenantId, String orgId, String userId) {
    }
}

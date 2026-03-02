package io.ostec.focusflow.repository.performance;

import io.ostec.focusflow.document.performance.DailyPerformanceRecordDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyPerformanceRecordRepository extends MongoRepository<DailyPerformanceRecordDocument, String> {

    Optional<DailyPerformanceRecordDocument> findByTenantIdAndOrgIdAndUserIdAndDate(
        String tenantId,
        String orgId,
        String userId,
        LocalDate date
    );

    List<DailyPerformanceRecordDocument> findByTenantIdAndOrgIdAndUserIdAndDateBetween(
        String tenantId,
        String orgId,
        String userId,
        LocalDate fromDate,
        LocalDate toDate
    );

    List<DailyPerformanceRecordDocument> findByTenantIdAndOrgIdAndUserIdOrderByDateAsc(
        String tenantId,
        String orgId,
        String userId
    );
}

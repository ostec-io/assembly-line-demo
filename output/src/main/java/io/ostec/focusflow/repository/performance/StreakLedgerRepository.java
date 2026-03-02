package io.ostec.focusflow.repository.performance;

import io.ostec.focusflow.document.performance.StreakLedgerDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface StreakLedgerRepository extends MongoRepository<StreakLedgerDocument, String> {

    Optional<StreakLedgerDocument> findByTenantIdAndOrgIdAndUserId(String tenantId, String orgId, String userId);
}

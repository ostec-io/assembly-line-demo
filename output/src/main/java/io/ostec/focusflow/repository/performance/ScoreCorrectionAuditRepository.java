package io.ostec.focusflow.repository.performance;

import io.ostec.focusflow.document.performance.ScoreCorrectionAuditDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ScoreCorrectionAuditRepository extends MongoRepository<ScoreCorrectionAuditDocument, String> {

    Optional<ScoreCorrectionAuditDocument> findByIdAndTenantIdAndOrgId(String id, String tenantId, String orgId);
}

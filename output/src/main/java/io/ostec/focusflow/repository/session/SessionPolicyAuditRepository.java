package io.ostec.focusflow.repository.session;

import io.ostec.focusflow.document.session.SessionPolicyAuditDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface SessionPolicyAuditRepository extends MongoRepository<SessionPolicyAuditDocument, String> {

    Optional<SessionPolicyAuditDocument> findByIdAndTenantIdAndOrgId(String id, String tenantId, String orgId);
}

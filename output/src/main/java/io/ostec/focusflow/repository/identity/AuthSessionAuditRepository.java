package io.ostec.focusflow.repository.identity;

import io.ostec.focusflow.document.identity.AuthSessionAuditDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AuthSessionAuditRepository extends MongoRepository<AuthSessionAuditDocument, String> {

    List<AuthSessionAuditDocument> findTop50ByTenantIdAndOrgIdAndUserIdOrderByIssuedAtDesc(
        String tenantId,
        String orgId,
        String userId
    );
}

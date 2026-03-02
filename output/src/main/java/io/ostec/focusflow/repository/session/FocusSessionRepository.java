package io.ostec.focusflow.repository.session;

import io.ostec.focusflow.document.session.FocusSessionDocument;
import io.ostec.focusflow.document.session.FocusSessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Optional;

public interface FocusSessionRepository extends MongoRepository<FocusSessionDocument, String>, FocusSessionRepositoryCustom {

    Optional<FocusSessionDocument> findByIdAndTenantIdAndOrgId(String id, String tenantId, String orgId);

    Optional<FocusSessionDocument> findFirstByTenantIdAndOrgIdAndUserIdAndStatus(
        String tenantId,
        String orgId,
        String userId,
        FocusSessionStatus status
    );

    Page<FocusSessionDocument> findByTenantIdAndOrgIdAndUserIdAndCreatedAtBetween(
        String tenantId,
        String orgId,
        String userId,
        Instant from,
        Instant to,
        Pageable pageable
    );
}

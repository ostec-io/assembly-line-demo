package io.ostec.focusflow.repository.accountability;

import io.ostec.focusflow.document.accountability.RoomModerationAuditDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface RoomModerationAuditRepository extends MongoRepository<RoomModerationAuditDocument, String> {

    Optional<RoomModerationAuditDocument> findByIdAndTenantIdAndOrgId(String id, String tenantId, String orgId);
}

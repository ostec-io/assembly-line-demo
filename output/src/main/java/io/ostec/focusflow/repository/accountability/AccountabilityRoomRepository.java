package io.ostec.focusflow.repository.accountability;

import io.ostec.focusflow.document.accountability.AccountabilityRoomDocument;
import io.ostec.focusflow.document.accountability.RoomStatus;
import io.ostec.focusflow.document.accountability.RoomVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface AccountabilityRoomRepository extends MongoRepository<AccountabilityRoomDocument, String> {

    Optional<AccountabilityRoomDocument> findByIdAndTenantIdAndOrgId(String id, String tenantId, String orgId);

    Page<AccountabilityRoomDocument> findByTenantIdAndOrgIdAndVisibilityAndStatus(
        String tenantId,
        String orgId,
        RoomVisibility visibility,
        RoomStatus status,
        Pageable pageable
    );

    boolean existsByTenantIdAndOrgIdAndIdAndParticipantsUserId(String tenantId, String orgId, String roomId, String userId);
}

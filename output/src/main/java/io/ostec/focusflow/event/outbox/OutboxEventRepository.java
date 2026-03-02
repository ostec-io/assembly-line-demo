package io.ostec.focusflow.event.outbox;

import io.ostec.focusflow.event.outbox.OutboxEventDocument.OutboxStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface OutboxEventRepository extends MongoRepository<OutboxEventDocument, String> {

    List<OutboxEventDocument> findTop100ByTenantIdAndOrgIdAndStatusOrderByOccurredAtAsc(
        String tenantId,
        String orgId,
        OutboxStatus status
    );
}

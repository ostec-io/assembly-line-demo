package io.ostec.focusflow.repository.insights;

import io.ostec.focusflow.document.insights.ProjectionLagDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ProjectionLagRepository extends MongoRepository<ProjectionLagDocument, String> {

    Optional<ProjectionLagDocument> findByTenantIdAndOrgIdAndStream(String tenantId, String orgId, String stream);
}

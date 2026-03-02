package io.ostec.focusflow.repository.identity;

import io.ostec.focusflow.document.identity.UserProfileShadowDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserProfileShadowRepository extends MongoRepository<UserProfileShadowDocument, String> {

    Optional<UserProfileShadowDocument> findByTenantIdAndOrgIdAndUserId(String tenantId, String orgId, String userId);
}

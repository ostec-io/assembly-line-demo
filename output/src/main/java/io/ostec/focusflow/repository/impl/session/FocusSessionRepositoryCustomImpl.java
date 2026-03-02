package io.ostec.focusflow.repository.impl.session;

import io.ostec.focusflow.document.session.FocusSessionDocument;
import io.ostec.focusflow.repository.session.FocusSessionRepositoryCustom;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class FocusSessionRepositoryCustomImpl implements FocusSessionRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    public FocusSessionRepositoryCustomImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public FocusSessionDocument saveWithExpectedVersion(FocusSessionDocument doc, long expectedVersion) {
        validateDocument(doc);

        doc.setVersion(expectedVersion + 1);

        Query query = Query.query(Criteria.where("_id").is(doc.getId())
            .and("tenantId").is(doc.getTenantId())
            .and("orgId").is(doc.getOrgId())
            .and("version").is(expectedVersion));

        FocusSessionDocument updated = mongoTemplate.findAndReplace(
            query,
            doc,
            FindAndReplaceOptions.options().returnNew()
        );

        if (updated == null) {
            throw new OptimisticLockingFailureException("Focus session version mismatch or document not found");
        }

        return updated;
    }

    private void validateDocument(FocusSessionDocument doc) {
        if (doc == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        if (!StringUtils.hasText(doc.getId())) {
            throw new IllegalArgumentException("document id must not be blank");
        }
        if (!StringUtils.hasText(doc.getTenantId())) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (!StringUtils.hasText(doc.getOrgId())) {
            throw new IllegalArgumentException("orgId must not be blank");
        }
    }
}

package io.ostec.focusflow.metrics;

import io.ostec.focusflow.document.accountability.AccountabilityRoomDocument;
import io.ostec.focusflow.document.accountability.RoomStatus;
import io.ostec.focusflow.event.outbox.OutboxEventDocument;
import io.ostec.focusflow.event.outbox.OutboxEventDocument.OutboxStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class OutboxLagMetricsPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxLagMetricsPublisher.class);
    private static final List<RoomStatus> ACTIVE_ROOM_STATUSES = List.of(
        RoomStatus.OPEN,
        RoomStatus.LOCKED,
        RoomStatus.IN_SESSION,
        RoomStatus.REVEAL
    );

    private final MongoTemplate mongoTemplate;
    private final FocusMetricsService focusMetricsService;

    public OutboxLagMetricsPublisher(
        MongoTemplate mongoTemplate,
        FocusMetricsService focusMetricsService
    ) {
        this.mongoTemplate = mongoTemplate;
        this.focusMetricsService = focusMetricsService;
    }

    @Scheduled(fixedDelayString = "${focusflow.metrics.outbox-lag-publish-interval-ms:15000}")
    public void publish() {
        try {
            long activeRooms = countActiveRooms();
            long pendingEvents = countPendingOutboxEvents();
            double lagSeconds = calculateConsumerLagSeconds();

            focusMetricsService.setActiveRoomsCount(activeRooms);
            focusMetricsService.setOutboxPendingCount(pendingEvents);
            focusMetricsService.setConsumerLagSeconds(lagSeconds);
        } catch (Exception ex) {
            log.warn("Unable to publish outbox lag metrics", ex);
        }
    }

    private long countActiveRooms() {
        Query query = new Query(Criteria.where("status").in(ACTIVE_ROOM_STATUSES));
        return mongoTemplate.count(query, AccountabilityRoomDocument.class);
    }

    private long countPendingOutboxEvents() {
        Query query = new Query(Criteria.where("status").is(OutboxStatus.PENDING));
        return mongoTemplate.count(query, OutboxEventDocument.class);
    }

    private double calculateConsumerLagSeconds() {
        Query query = new Query(Criteria.where("status").is(OutboxStatus.PENDING));
        query.with(Sort.by(Sort.Direction.ASC, "occurredAt"));
        query.limit(1);

        OutboxEventDocument oldestPending = mongoTemplate.findOne(query, OutboxEventDocument.class);
        if (oldestPending == null || oldestPending.getOccurredAt() == null) {
            return 0.0d;
        }

        Duration lag = Duration.between(oldestPending.getOccurredAt(), Instant.now());
        if (lag.isNegative()) {
            return 0.0d;
        }
        return lag.toMillis() / 1000.0d;
    }
}

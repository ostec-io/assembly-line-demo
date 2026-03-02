package io.ostec.focusflow.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class FocusMetricsService {

    private final Counter sessionCreateCounter;
    private final Counter sessionCompleteCounter;
    private final Timer performanceScoreCalcLatencyTimer;
    private final Timer aiGenerateLatencyTimer;
    private final Timer entitlementCheckLatencyTimer;

    private final AtomicLong activeRoomsGauge = new AtomicLong(0L);
    private final AtomicLong outboxPendingGauge = new AtomicLong(0L);
    private final AtomicLong eventsConsumerLagMillisGauge = new AtomicLong(0L);

    public FocusMetricsService(MeterRegistry meterRegistry) {
        this.sessionCreateCounter = Counter.builder("focus.sessions.create.count")
            .description("Total number of focus sessions created")
            .register(meterRegistry);
        this.sessionCompleteCounter = Counter.builder("focus.sessions.complete.count")
            .description("Total number of focus sessions completed")
            .register(meterRegistry);
        this.performanceScoreCalcLatencyTimer = Timer.builder("focus.performance.score.calc.latency")
            .description("Latency for performance score calculation operations")
            .register(meterRegistry);
        this.aiGenerateLatencyTimer = Timer.builder("focus.ai.generate.latency")
            .description("Latency for AI plan generation operations")
            .register(meterRegistry);
        this.entitlementCheckLatencyTimer = Timer.builder("focus.entitlement.check.latency")
            .description("Latency for entitlement decision checks")
            .register(meterRegistry);

        Gauge.builder("focus.rooms.active.count", activeRoomsGauge, AtomicLong::doubleValue)
            .description("Number of active accountability rooms")
            .register(meterRegistry);
        Gauge.builder("focus.events.outbox.pending", outboxPendingGauge, AtomicLong::doubleValue)
            .description("Number of pending outbox events")
            .register(meterRegistry);
        Gauge.builder("focus.events.consumer.lag.seconds", eventsConsumerLagMillisGauge, millis -> millis.get() / 1000.0d)
            .description("Approximate consumer lag based on oldest pending event age")
            .register(meterRegistry);
    }

    public void incrementSessionCreate() {
        sessionCreateCounter.increment();
    }

    public void incrementSessionComplete() {
        sessionCompleteCounter.increment();
    }

    public void recordPerformanceScoreCalcLatency(Duration latency) {
        if (latency != null && !latency.isNegative()) {
            performanceScoreCalcLatencyTimer.record(latency);
        }
    }

    public void recordAiGenerateLatency(Duration latency) {
        if (latency != null && !latency.isNegative()) {
            aiGenerateLatencyTimer.record(latency);
        }
    }

    public void recordEntitlementCheckLatency(Duration latency) {
        if (latency != null && !latency.isNegative()) {
            entitlementCheckLatencyTimer.record(latency);
        }
    }

    public void setActiveRoomsCount(long activeRoomsCount) {
        activeRoomsGauge.set(Math.max(activeRoomsCount, 0L));
    }

    public void setOutboxPendingCount(long pendingCount) {
        outboxPendingGauge.set(Math.max(pendingCount, 0L));
    }

    public void setConsumerLagSeconds(double lagSeconds) {
        long lagMillis = Math.max((long) (lagSeconds * 1000.0d), 0L);
        eventsConsumerLagMillisGauge.set(lagMillis);
    }
}

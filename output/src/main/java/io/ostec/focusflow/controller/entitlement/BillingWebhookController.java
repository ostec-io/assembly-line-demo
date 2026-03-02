package io.ostec.focusflow.controller.entitlement;

import io.ostec.focusflow.dto.request.entitlement.StripeWebhookRequest;
import io.ostec.focusflow.event.saga.SubscriptionTransitionSaga;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/billing/stripe")
@Tag(name = "Billing Webhooks", description = "Public Stripe webhook ingestion endpoints")
public class BillingWebhookController {

    private final SubscriptionTransitionSaga subscriptionTransitionSaga;

    public BillingWebhookController(SubscriptionTransitionSaga subscriptionTransitionSaga) {
        this.subscriptionTransitionSaga = subscriptionTransitionSaga;
    }

    @PostMapping("/webhook")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Ingest Stripe billing webhook")
    public ResponseEntity<Void> ingestWebhook(@Valid @RequestBody StripeWebhookRequest request) {
        subscriptionTransitionSaga.processValidatedWebhook(
            "stripe",
            request.eventId(),
            request.type(),
            request.payload()
        );
        return ResponseEntity.noContent().build();
    }
}

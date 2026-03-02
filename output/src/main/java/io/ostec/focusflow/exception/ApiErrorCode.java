package io.ostec.focusflow.exception;

public enum ApiErrorCode {
    VALIDATION_ERROR,

    // Session domain
    INVALID_DURATION,
    ACTIVE_SESSION_EXISTS,
    COMPLETION_CRITERIA_NOT_MET,
    SESSION_NOT_FOUND,

    // Accountability domain
    NOT_HOST,
    ROOM_LOCKED,
    ROOM_FULL,
    PARTICIPANT_NOT_FOUND,
    REVEAL_PRECONDITIONS_NOT_MET,

    // Shared/eventing
    DUPLICATE_EVENT,
    OPTIMISTIC_LOCK_CONFLICT,

    // AI domain
    PROVIDER_UNAVAILABLE,
    PLAN_NOT_FOUND,
    AI_PLAN_INVALID,

    // Entitlement domain
    ENTITLEMENT_DENIED,
    USAGE_LIMIT_REACHED,
    BILLING_TRANSITION_CONFLICT,

    // Identity/domain context
    TENANT_CONTEXT_MISSING,
    IDENTITY_SYNC_FAILED,

    // Generic categories
    POLICY_VIOLATION,
    RESOURCE_NOT_FOUND,
    TRANSIENT_INFRA_FAILURE,
    INTERNAL_ERROR
}

# FocusFlow Spring Boot Component Outline (OSTEC)

Project ID: `69a1dc8f937a4a78197bfa39`  
Task ID: `task-01KJQ329ERE2CTX4QFN12X0TXG`  
Source DDD: `/Users/ossiejam/agentflow-development-workspace/api-assembly-line/assembly-line/output/focus-flow/01-ddd-spec/ddd-specification.md`

## 1. Gap Analysis vs OSTEC Non-Negotiables

| Area | DDD Spec Baseline | OSTEC Requirement | Component Outline Decision |
| --- | --- | --- | --- |
| Persistence | Multiple contexts reference PostgreSQL (+ Redis/warehouse projections) | Spring Data MongoDB | All write models are MongoDB `@Document` models; Redis/warehouse remain optional downstream read/projection stores behind integration adapters. |
| Security wiring | IdentityAccess context includes auth/token management | `ostec-security-starter` only; no custom `SecurityFilterChain` | Authentication/authorization handled by starter + Keycloak JWT; IdentityAccess context becomes profile shadow + auth audit + keycloak integration boundaries. |
| Tenant isolation | Mentioned in events and constraints | All queries filtered by `tenantId`; writes set `tenantId` + `orgId` | Every repository method includes `tenantId` (and `orgId`); service layer always pulls tenant/user from `TenantContextHolder` + `SecurityContextHelper`. |
| Endpoint protection | Not explicit in all endpoint definitions | `@PreAuthorize` on every endpoint | Every controller method declares `@PreAuthorize` (including `permitAll()` for webhook/public use-cases). |
| Event reliability | ADR recommends outbox | Reliable domain event publishing | Mongo transactional outbox (`OutboxEventDocument`) + async publisher + idempotent consumers on `eventId`. |
| API docs and observability | Required by platform concerns | Springdoc + Micrometer + OpenTelemetry + Prometheus | Included in base config and component responsibilities; controller annotations include OpenAPI operation metadata. |

## 2. Context Inventory

| Bounded Context | Aggregate Roots | Primary Collections | Core API Surface | Produced Events | Consumed Events |
| --- | --- | --- | --- | --- | --- |
| SessionExecutionContext | `FocusSession` | `focus_sessions` | `/api/v1/sessions` | `FocusSessionCreated`, `SessionPlanAttached`, `FocusSessionStarted`, `MidSessionCheckInRecorded`, `FocusSessionInterrupted`, `FocusSessionCompleted`, `FocusSessionAborted` | `EntitlementGranted/Revoked`, `SessionPlanGenerated/Attached`, `SubscriptionActivated`, `UsageLimitReached` |
| AccountabilityContext | `AccountabilityRoom` | `accountability_rooms` | `/api/v1/rooms` | `RoomCreated`, `ParticipantJoinedRoom`, `ParticipantGoalSet`, `RoomLocked`, `RoomSessionStarted`, `ParticipantResultSubmitted`, `RoomResultsRevealed`, `RoomClosed` | `FocusSessionStarted/Completed`, `EntitlementGranted/Revoked`, `SubscriptionActivated` |
| PerformanceContext | `DailyPerformanceRecord`, `StreakLedger` | `daily_performance_records`, `streak_ledgers` | `/api/v1/performance` | `DailyScoreCalculated`, `DailyRecordFinalized`, `StreakExtended`, `StreakBroken`, `DailyScoreCorrected` | `FocusSessionStarted`, `FocusSessionCompleted`, `FocusSessionInterrupted`, `ParticipantResultSubmitted` |
| AIPlanningContext | `SessionPlanArtifact` | `session_plan_artifacts`, `plan_memories` | `/api/v1/ai/plans` | `SessionPlanGenerated`, `SessionPlanEdited`, `SessionPlanAttached`, `PlanLearningMemoryUpdated` | `SubscriptionActivated`, `EntitlementGranted/Revoked` |
| EntitlementBillingContext | `SubscriptionAccount`, `UsageCounter` | `subscription_accounts`, `usage_counters`, `billing_webhook_inbox` | `/api/v1/entitlements`, `/api/v1/billing` | `SubscriptionActivated`, `SubscriptionRenewed`, `SubscriptionPastDue`, `SubscriptionCanceled`, `EntitlementGranted`, `EntitlementRevoked`, `UsageLimitReached` | Stripe webhook events |
| IdentityAccessContext | `UserAccount` (shadow) | `user_profile_shadows`, `auth_session_audits` | `/api/v1/me` | `UserRegistered`, `UserAuthenticated`, `AccessTokenIssued`, `UserSuspended` (mirrored/integrated) | Keycloak auth events / JWT claims |
| InsightsContext | `AnalyticsFact`, `KpiSnapshot` | `analytics_facts`, `kpi_snapshots` | `/api/v1/insights` | `AnalyticsEventIngested`, `KpiSnapshotPublished` | Domain events from all contexts |

## 3. Package Structure (Per OSTEC Layout)

Base package: `io.ostec.focusflow`

```text
io.ostec.focusflow/
  config/
  controller/
    session/ accountability/ performance/ ai/ entitlement/ identity/ insights/
  dto/request/
    session/ accountability/ performance/ ai/ entitlement/ identity/ insights/
  dto/response/
    session/ accountability/ performance/ ai/ entitlement/ identity/ insights/
  document/
    session/ accountability/ performance/ ai/ entitlement/ identity/ insights/ common/
  repository/
    session/ accountability/ performance/ ai/ entitlement/ identity/ insights/
  repository/impl/
    session/ accountability/ performance/ ai/ entitlement/ identity/ insights/
  service/
    session/ accountability/ performance/ ai/ entitlement/ identity/ insights/
  event/
    publisher/ listener/ model/ outbox/
  metrics/
  audit/
  validation/
  exception/
  mapper/
  util/
  web/
```

## 4. Cross-Cutting Baseline

### 4.1 Mandatory Stack and Dependencies

- Java 21
- Spring Boot 3.3.x (Maven)
- `ostec-security-starter:1.0.0-SNAPSHOT`
- Spring Data MongoDB
- Micrometer + OpenTelemetry + Prometheus registry
- Springdoc OpenAPI
- Lombok: use `@Builder`, `@Getter`, `@RequiredArgsConstructor`; do not use `@Data`

### 4.2 Security and Tenant Context Rules

- No custom `SecurityFilterChain` bean.
- `spring.application.name` base name: `focusflow`.
- Resource audience: `${spring.application.name}-api`.
- Each service command/query starts by resolving:
  - `String tenantId = TenantContextHolder.getTenantId();`
  - `String orgId = TenantContextHolder.getOrgId();`
  - `String userId = SecurityContextHelper.getCurrentUserId();`
- Every write sets `tenantId` and `orgId` on the target `@Document`.
- Every repository query includes `tenantId` (and `orgId` in the criteria for stricter partitioning).

### 4.3 Public Paths

Public endpoints configured via security starter permit list:

- `/actuator/**`
- `/health/**`
- `/swagger-ui/**`
- `/api-docs/**`
- `/v3/api-docs/**`
- `/error`

### 4.4 Shared Base Documents and Event Envelope

- `AuditedDocument` (from OSTEC base): `id`, `createdAt`, `createdBy`, `updatedAt`, `updatedBy`, `version`
- All aggregate root documents extend `AuditedDocument` and contain:
  - `tenantId`
  - `orgId`
- `OutboxEventDocument` (collection: `outbox_events`):
  - `eventId`, `eventType`, `eventVersion`, `occurredAt`, `producer`, `correlationId`, `causationId`, `tenantId`, `orgId`, `aggregateType`, `aggregateId`, `payload`, `status`
- Publishing strategy: transactional write of aggregate + outbox; async dispatcher to bus topics (`focus.sessions.v1`, `focus.rooms.v1`, `focus.performance.v1`, `focus.entitlements.v1`, `focus.ai.v1`, `focus.analytics.v1`).

## 5. Bounded Context Component Outlines

### 5.1 SessionExecutionContext

#### Document Models

| Document | Collection | Key Fields | Required Indexes |
| --- | --- | --- | --- |
| `FocusSessionDocument extends AuditedDocument` | `focus_sessions` | `tenantId`, `orgId`, `userId`, `goal`, `durationMinutes`, `status`, `planId`, `roomId`, `startedAt`, `endedAt`, `checkIns[]`, `interruptions[]`, `completionSummary` | `{tenantId:1, orgId:1, userId:1, status:1}`, `{tenantId:1, orgId:1, userId:1, createdAt:-1}`, `{tenantId:1, orgId:1, roomId:1, status:1}`, partial unique `{tenantId:1, orgId:1, userId:1, status:1}` where `status='ACTIVE'` |
| `SessionPolicyAuditDocument extends AuditedDocument` | `session_policy_audits` | `tenantId`, `orgId`, `sessionId`, `policyName`, `decision`, `reason` | `{tenantId:1, orgId:1, sessionId:1, createdAt:-1}` |

Embedded VOs/entities:
- `SessionGoalEmbedded`
- `MidSessionCheckInEmbedded`
- `InterruptionSignalEmbedded`
- `CompletionSummaryEmbedded`

#### Repository Interfaces

- `FocusSessionRepository`
  - `Optional<FocusSessionDocument> findByIdAndTenantIdAndOrgId(String id, String tenantId, String orgId)`
  - `Optional<FocusSessionDocument> findFirstByTenantIdAndOrgIdAndUserIdAndStatus(String tenantId, String orgId, String userId, FocusSessionStatus status)`
  - `Page<FocusSessionDocument> findByTenantIdAndOrgIdAndUserIdAndCreatedAtBetween(String tenantId, String orgId, String userId, Instant from, Instant to, Pageable pageable)`
- `FocusSessionRepositoryCustom` (`repository/impl`)
  - `FocusSessionDocument saveWithExpectedVersion(FocusSessionDocument doc, long expectedVersion)`

#### Services

- `SessionPolicyService`
  - `validateSessionCreation(...)`
  - `validateSessionStart(...)`
  - `assessCompletionEligibility(...)`
- `SessionCommandService`
  - `createSession(...)`
  - `attachSessionPlan(...)`
  - `startSession(...)`
  - `recordCheckIn(...)`
  - `recordInterruption(...)`
  - `completeSession(...)`
  - `abortSession(...)`
- `SessionQueryService`
  - `getTodayStatus(...)`
  - `getSessionHistory(...)`

Invariant enforcement:
- duration `[15,180]`
- no overlapping active session per tenant/org/user
- completion requires elapsed timer + summary

#### Controllers (`@PreAuthorize` on all methods)

- `SessionCommandController` (`/api/v1/sessions`)
  - `POST /` `@PreAuthorize("hasAuthority('SCOPE_sessions.write')")`
  - `POST /{sessionId}/plan` `@PreAuthorize("hasAuthority('SCOPE_sessions.write')")`
  - `POST /{sessionId}/start` `@PreAuthorize("hasAuthority('SCOPE_sessions.write')")`
  - `POST /{sessionId}/check-ins` `@PreAuthorize("hasAuthority('SCOPE_sessions.write')")`
  - `POST /{sessionId}/interruptions` `@PreAuthorize("hasAuthority('SCOPE_sessions.write')")`
  - `POST /{sessionId}/complete` `@PreAuthorize("hasAuthority('SCOPE_sessions.write')")`
  - `POST /{sessionId}/abort` `@PreAuthorize("hasAuthority('SCOPE_sessions.write')")`
- `SessionQueryController` (`/api/v1/sessions`)
  - `GET /today` `@PreAuthorize("hasAuthority('SCOPE_sessions.read')")`
  - `GET /history` `@PreAuthorize("hasAuthority('SCOPE_sessions.read')")`

#### DTO Records

Request records:
- `CreateFocusSessionRequest(@NotBlank @Size(max=500) String goal, @NotNull @Min(15) @Max(180) Integer durationMinutes, String correlationId)`
- `AttachSessionPlanRequest(@NotBlank String planId, @NotNull Integer taskCount)`
- `RecordMidSessionCheckInRequest(@NotBlank String status, @Size(max=500) String note)`
- `RecordSessionInterruptionRequest(@NotBlank String interruptionType, @NotNull Instant detectedAt)`
- `CompleteFocusSessionRequest(@NotBlank @Size(max=2000) String summary, @Min(0) Integer deepWorkMinutes)`

Response records:
- `FocusSessionResponse(...)`
- `TodaySessionStatusResponse(...)`
- `SessionHistoryItemResponse(...)`

#### Events and Listeners

Produced:
- `FocusSessionCreated`
- `SessionPlanAttached`
- `FocusSessionStarted`
- `MidSessionCheckInRecorded`
- `FocusSessionInterrupted`
- `FocusSessionCompleted`
- `FocusSessionAborted`

Consumed:
- `EntitlementGranted`
- `EntitlementRevoked`
- `UsageLimitReached`
- `SessionPlanGenerated`

#### Integration Points

- Sync: internal call to EntitlementBilling check API before create/start/plan-memory-related actions.
- Async: publish to `focus.sessions.v1`; consume plan/entitlement events.

---

### 5.2 AccountabilityContext

#### Document Models

| Document | Collection | Key Fields | Required Indexes |
| --- | --- | --- | --- |
| `AccountabilityRoomDocument extends AuditedDocument` | `accountability_rooms` | `tenantId`, `orgId`, `hostUserId`, `status`, `visibility`, `capacity`, `lockedAt`, `startedAt`, `closedAt`, `participants[]` | `{tenantId:1, orgId:1, status:1, visibility:1, createdAt:-1}`, `{tenantId:1, orgId:1, hostUserId:1, status:1}`, `{tenantId:1, orgId:1, "participants.userId":1}` |
| `RoomModerationAuditDocument extends AuditedDocument` | `room_moderation_audits` | `tenantId`, `orgId`, `roomId`, `actorUserId`, `action`, `reason` | `{tenantId:1, orgId:1, roomId:1, createdAt:-1}` |

Embedded:
- `RoomParticipantEmbedded`
- `ParticipantGoalEmbedded`
- `ResultSummaryEmbedded`

#### Repository Interfaces

- `AccountabilityRoomRepository`
  - `Optional<AccountabilityRoomDocument> findByIdAndTenantIdAndOrgId(String id, String tenantId, String orgId)`
  - `Page<AccountabilityRoomDocument> findByTenantIdAndOrgIdAndVisibilityAndStatus(String tenantId, String orgId, RoomVisibility visibility, RoomStatus status, Pageable pageable)`
  - `boolean existsByTenantIdAndOrgIdAndIdAndParticipantsUserId(String tenantId, String orgId, String roomId, String userId)`
- `RoomModerationAuditRepository`

#### Services

- `RoomModerationService`
  - `canLockRoom(...)`
  - `canRemoveParticipant(...)`
  - `canRevealResults(...)`
- `AccountabilityRoomCommandService`
  - `createRoom(...)`
  - `joinRoom(...)`
  - `setParticipantGoal(...)`
  - `lockRoom(...)`
  - `startRoomSession(...)`
  - `submitParticipantResult(...)`
  - `revealRoomResults(...)`
  - `closeRoom(...)`
- `AccountabilityRoomQueryService`
  - `listOpenPublicRooms(...)`
  - `getRoomLivePresence(...)`

Invariants:
- host-only moderation commands
- no joins outside `OPEN`
- reveal only after session ends

#### Controllers

- `AccountabilityRoomCommandController` (`/api/v1/rooms`)
  - `POST /` `@PreAuthorize("hasAuthority('SCOPE_rooms.write')")`
  - `POST /{roomId}/join` `@PreAuthorize("hasAuthority('SCOPE_rooms.write')")`
  - `POST /{roomId}/goal` `@PreAuthorize("hasAuthority('SCOPE_rooms.write')")`
  - `POST /{roomId}/lock` `@PreAuthorize("hasAuthority('SCOPE_rooms.write')")`
  - `POST /{roomId}/start` `@PreAuthorize("hasAuthority('SCOPE_rooms.write')")`
  - `POST /{roomId}/results` `@PreAuthorize("hasAuthority('SCOPE_rooms.write')")`
  - `POST /{roomId}/reveal` `@PreAuthorize("hasAuthority('SCOPE_rooms.write')")`
  - `POST /{roomId}/close` `@PreAuthorize("hasAuthority('SCOPE_rooms.write')")`
- `AccountabilityRoomQueryController` (`/api/v1/rooms`)
  - `GET /lobby` `@PreAuthorize("hasAuthority('SCOPE_rooms.read')")`
  - `GET /{roomId}` `@PreAuthorize("hasAuthority('SCOPE_rooms.read')")`

#### DTO Records

Request:
- `CreateAccountabilityRoomRequest(@NotBlank String title, @NotNull RoomVisibility visibility, @Min(2) @Max(50) Integer capacity)`
- `JoinAccountabilityRoomRequest(@NotBlank String roomId)`
- `SetParticipantGoalRequest(@NotBlank @Size(max=300) String goal)`
- `SubmitParticipantResultRequest(@NotBlank @Size(max=1500) String summary, @Min(0) Integer deepWorkMinutes)`

Response:
- `AccountabilityRoomResponse(...)`
- `RoomLobbyItemResponse(...)`
- `RoomResultBoardResponse(...)`

#### Events and Listeners

Produced:
- `RoomCreated`
- `ParticipantJoinedRoom`
- `ParticipantGoalSet`
- `RoomLocked`
- `RoomSessionStarted`
- `ParticipantResultSubmitted`
- `RoomResultsRevealed`
- `RoomClosed`

Consumed:
- `EntitlementGranted`
- `EntitlementRevoked`
- `FocusSessionStarted`
- `FocusSessionCompleted`

#### Integration Points

- Sync: entitlement check for private rooms (`POST /api/v1/entitlements/check`).
- Async: `focus.rooms.v1` publish; `focus.sessions.v1` consume for room-session linkage.

---

### 5.3 PerformanceContext

#### Document Models

| Document | Collection | Key Fields | Required Indexes |
| --- | --- | --- | --- |
| `DailyPerformanceRecordDocument extends AuditedDocument` | `daily_performance_records` | `tenantId`, `orgId`, `userId`, `date`, `focusScore`, `deepWorkMinutes`, `completionRate`, `distractionRate`, `formulaVersion`, `state`, `processedEventIds[]` | unique `{tenantId:1, orgId:1, userId:1, date:1}`, `{tenantId:1, orgId:1, userId:1, date:-1}` |
| `StreakLedgerDocument extends AuditedDocument` | `streak_ledgers` | `tenantId`, `orgId`, `userId`, `currentStreak`, `longestStreak`, `lastQualifiedDate` | unique `{tenantId:1, orgId:1, userId:1}`, `{tenantId:1, orgId:1, currentStreak:-1}` |
| `ScoreCorrectionAuditDocument extends AuditedDocument` | `score_correction_audits` | `tenantId`, `orgId`, `userId`, `date`, `reason`, `oldScore`, `newScore` | `{tenantId:1, orgId:1, userId:1, date:1}` |

#### Repository Interfaces

- `DailyPerformanceRecordRepository`
  - `Optional<DailyPerformanceRecordDocument> findByTenantIdAndOrgIdAndUserIdAndDate(String tenantId, String orgId, String userId, LocalDate date)`
  - `List<DailyPerformanceRecordDocument> findByTenantIdAndOrgIdAndUserIdAndDateBetween(String tenantId, String orgId, String userId, LocalDate from, LocalDate to)`
- `StreakLedgerRepository`
  - `Optional<StreakLedgerDocument> findByTenantIdAndOrgIdAndUserId(String tenantId, String orgId, String userId)`

#### Services

- `FocusScoreCalculationService`
  - `calculateDailyScore(...)`
  - `recalculateOnCorrection(...)`
  - `computeStreakTransition(...)`
- `PerformanceProjectionService`
  - updates dashboard-ready read payloads
- `PerformanceQueryService`
  - `getDailyPerformance(...)`
  - `getWeeklyAnalytics(...)`

Invariants:
- immutable source events for scoring
- no negative deep work minutes
- streak increments max one day per transition

#### Controllers

- `PerformanceQueryController` (`/api/v1/performance`)
  - `GET /me/daily` `@PreAuthorize("hasAuthority('SCOPE_performance.read')")`
  - `GET /me/weekly` `@PreAuthorize("hasAuthority('SCOPE_performance.read')")`
- `PerformanceAdminController` (`/api/v1/performance/admin`)
  - `POST /score-corrections` `@PreAuthorize("hasAuthority('SCOPE_performance.admin')")`

#### DTO Records

Request:
- `ApplyScoreCorrectionRequest(@NotBlank String userId, @NotNull LocalDate date, @NotBlank String reason, @NotNull BigDecimal correctedScore)`

Response:
- `DailyPerformanceResponse(...)`
- `WeeklyAnalyticsResponse(...)`
- `StreakSummaryResponse(...)`

#### Events and Listeners

Produced:
- `DailyScoreCalculated`
- `DailyRecordFinalized`
- `StreakExtended`
- `StreakBroken`
- `DailyScoreCorrected`

Consumed:
- `FocusSessionStarted`
- `FocusSessionCompleted`
- `FocusSessionInterrupted`
- `ParticipantResultSubmitted`

#### Integration Points

- Async consume from `focus.sessions.v1` and `focus.rooms.v1`.
- Async publish to `focus.performance.v1` and `focus.analytics.v1`.

---

### 5.4 AIPlanningContext

#### Document Models

| Document | Collection | Key Fields | Required Indexes |
| --- | --- | --- | --- |
| `SessionPlanArtifactDocument extends AuditedDocument` | `session_plan_artifacts` | `tenantId`, `orgId`, `planId`, `sessionId`, `userId`, `status`, `templateVersion`, `provider`, `taskBlocks[]`, `revision`, `attachedVersion` | `{tenantId:1, orgId:1, sessionId:1, revision:-1}`, `{tenantId:1, orgId:1, userId:1, updatedAt:-1}` |
| `PlanMemoryDocument extends AuditedDocument` | `plan_memories` | `tenantId`, `orgId`, `userId`, `memoryVersion`, `preferences`, `lastFeedbackAt` | unique `{tenantId:1, orgId:1, userId:1}` |
| `AiProviderAuditDocument extends AuditedDocument` | `ai_provider_audits` | `tenantId`, `orgId`, `planId`, `provider`, `latencyMs`, `fallbackUsed`, `safetyFlags` | `{tenantId:1, orgId:1, planId:1}` |

Embedded:
- `TaskBlockEmbedded`
- `PlanRevisionEmbedded`

#### Repository Interfaces

- `SessionPlanRepository`
  - `Optional<SessionPlanArtifactDocument> findByIdAndTenantIdAndOrgId(String planId, String tenantId, String orgId)`
  - `Optional<SessionPlanArtifactDocument> findFirstByTenantIdAndOrgIdAndSessionIdOrderByRevisionDesc(String tenantId, String orgId, String sessionId)`
- `PlanMemoryRepository`
  - `Optional<PlanMemoryDocument> findByTenantIdAndOrgIdAndUserId(String tenantId, String orgId, String userId)`

#### Services

- `PlanGenerationService`
  - `generatePlan(...)`
  - `validatePlanStructure(...)`
  - `applyUserEdits(...)`
- `AiProviderAclService`
  - provider request/response normalization + fallback template mode
- `PlanAttachmentService`
  - verifies latest revision and session-duration compatibility

Invariants:
- at least one task block with positive estimate
- total planned duration <= session duration + tolerance
- only latest revision attachable

#### Controllers

- `AiPlanningController` (`/api/v1/ai/plans`)
  - `POST /generate` `@PreAuthorize("hasAuthority('SCOPE_ai.write')")`
  - `POST /{planId}/edit` `@PreAuthorize("hasAuthority('SCOPE_ai.write')")`
  - `POST /{planId}/attach` `@PreAuthorize("hasAuthority('SCOPE_ai.write')")`
  - `POST /memory` `@PreAuthorize("hasAuthority('SCOPE_ai.write')")`
  - `GET /{planId}` `@PreAuthorize("hasAuthority('SCOPE_ai.read')")`

#### DTO Records

Request:
- `GenerateSessionPlanRequest(@NotBlank String sessionId, @NotBlank @Size(max=1000) String goal, @NotNull @Min(15) @Max(180) Integer durationMinutes)`
- `EditSessionPlanRequest(@NotNull @Size(min=1) List<TaskBlockRequest> taskBlocks, @NotNull Long expectedRevision)`
- `AttachGeneratedPlanRequest(@NotBlank String sessionId, @NotNull Long revision)`
- `UpdatePlanLearningMemoryRequest(@NotBlank String feedback, @NotBlank String outcome)`

Response:
- `SessionPlanResponse(...)`
- `PlanVersionHistoryResponse(...)`

#### Events and Listeners

Produced:
- `SessionPlanGenerated`
- `SessionPlanEdited`
- `SessionPlanAttached`
- `PlanLearningMemoryUpdated`

Consumed:
- `SubscriptionActivated`
- `EntitlementGranted`
- `EntitlementRevoked`

#### Integration Points

- Sync: entitlement check for plan-memory capability.
- Sync: external AI provider call via ACL adapter (timeout + fallback).
- Async: publish plan events to `focus.ai.v1`; emit `SessionPlanAttached` for session context.

---

### 5.5 EntitlementBillingContext

#### Document Models

| Document | Collection | Key Fields | Required Indexes |
| --- | --- | --- | --- |
| `SubscriptionAccountDocument extends AuditedDocument` | `subscription_accounts` | `tenantId`, `orgId`, `userId`, `plan`, `state`, `cycleStart`, `cycleEnd`, `entitlements[]`, `lastWebhookEventId` | unique `{tenantId:1, orgId:1, userId:1}`, `{tenantId:1, orgId:1, state:1}` |
| `UsageCounterDocument extends AuditedDocument` | `usage_counters` | `tenantId`, `orgId`, `userId`, `featureKey`, `usageDate`, `used`, `limit`, `idempotencyKeys[]` | unique `{tenantId:1, orgId:1, userId:1, featureKey:1, usageDate:1}` |
| `BillingWebhookInboxDocument extends AuditedDocument` | `billing_webhook_inbox` | `tenantId`, `orgId`, `provider`, `eventId`, `eventType`, `payloadHash`, `processedAt`, `status` | unique `{tenantId:1, orgId:1, provider:1, eventId:1}` |

#### Repository Interfaces

- `SubscriptionAccountRepository`
  - `Optional<SubscriptionAccountDocument> findByTenantIdAndOrgIdAndUserId(String tenantId, String orgId, String userId)`
- `UsageCounterRepository`
  - `Optional<UsageCounterDocument> findByTenantIdAndOrgIdAndUserIdAndFeatureKeyAndUsageDate(...)`
- `BillingWebhookInboxRepository`
  - `boolean existsByTenantIdAndOrgIdAndProviderAndEventId(...)`

#### Services

- `EntitlementDecisionService`
  - `checkFeatureAccess(...)`
  - `checkDailyUsage(...)`
  - `grantUsage(...)`
- `SubscriptionLifecycleService`
  - `activateProSubscription(...)`
  - `cancelSubscription(...)`
  - `recordBillingWebhook(...)`
- `WebhookSignatureValidationService`

Invariants:
- single active plan per account
- webhook exactly-once application
- free-tier cap default 3 sessions/day

#### Controllers

- `EntitlementController` (`/api/v1/entitlements`)
  - `POST /check` `@PreAuthorize("hasAuthority('SCOPE_internal.entitlements')")`
  - `POST /consume` `@PreAuthorize("hasAuthority('SCOPE_internal.entitlements')")`
  - `GET /me/status` `@PreAuthorize("hasAuthority('SCOPE_billing.read')")`
- `BillingWebhookController` (`/api/v1/billing/stripe`)
  - `POST /webhook` `@PreAuthorize("permitAll()")`

#### DTO Records

Request:
- `EntitlementCheckRequest(@NotBlank String featureKey, @NotBlank String action, String resourceId)`
- `ConsumeUsageQuotaRequest(@NotBlank String featureKey, @NotBlank String idempotencyKey)`
- `StripeWebhookRequest(@NotBlank String eventId, @NotBlank String type, @NotBlank String payload)`

Response:
- `EntitlementDecisionResponse(boolean allowed, String reasonCode, Integer remaining)`
- `PlanStatusResponse(...)`

#### Events and Listeners

Produced:
- `SubscriptionActivated`
- `SubscriptionRenewed`
- `SubscriptionPastDue`
- `SubscriptionCanceled`
- `EntitlementGranted`
- `EntitlementRevoked`
- `UsageLimitReached`

Consumed:
- Stripe webhook signals (`invoice.paid`, `invoice.payment_failed`, `customer.subscription.deleted`)

#### Integration Points

- Sync endpoint for dependent contexts (`/entitlements/check`) with fail-closed policy.
- Async publish to `focus.entitlements.v1`.

---

### 5.6 IdentityAccessContext

#### Document Models

| Document | Collection | Key Fields | Required Indexes |
| --- | --- | --- | --- |
| `UserProfileShadowDocument extends AuditedDocument` | `user_profile_shadows` | `tenantId`, `orgId`, `userId`, `email`, `displayName`, `status`, `lastLoginAt` | unique `{tenantId:1, orgId:1, userId:1}`, `{tenantId:1, orgId:1, email:1}` |
| `AuthSessionAuditDocument extends AuditedDocument` | `auth_session_audits` | `tenantId`, `orgId`, `userId`, `eventType`, `issuedAt`, `ipHash`, `userAgentHash` | `{tenantId:1, orgId:1, userId:1, issuedAt:-1}` |

#### Repository Interfaces

- `UserProfileShadowRepository`
  - `Optional<UserProfileShadowDocument> findByTenantIdAndOrgIdAndUserId(String tenantId, String orgId, String userId)`
- `AuthSessionAuditRepository`
  - `List<AuthSessionAuditDocument> findTop50ByTenantIdAndOrgIdAndUserIdOrderByIssuedAtDesc(...)`

#### Services

- `CurrentUserProfileService`
  - resolves user identity from `SecurityContextHelper`
- `IdentityEventSyncService`
  - syncs keycloak events to local profile/audit documents

Invariants:
- unique email per tenant/org shadow record
- identity state is source-of-truth from Keycloak; local docs are projection/shadow only

#### Controllers

- `CurrentUserController` (`/api/v1/me`)
  - `GET /` `@PreAuthorize("isAuthenticated()")`
  - `PATCH /display-name` `@PreAuthorize("isAuthenticated()")`
  - `GET /auth-history` `@PreAuthorize("isAuthenticated()")`

#### DTO Records

Request:
- `UpdateDisplayNameRequest(@NotBlank @Size(max=100) String displayName)`

Response:
- `CurrentUserProfileResponse(String userId, String email, String displayName, String status)`
- `AuthSessionAuditResponse(...)`

#### Events and Listeners

Produced (integration mirrored):
- `UserRegistered`
- `UserAuthenticated`
- `AccessTokenIssued`
- `UserSuspended`

Consumed:
- Keycloak admin/auth events

#### Integration Points

- JWT claim extraction via `ostec-security-starter`.
- No credential storage or password management inside this service.

---

### 5.7 InsightsContext

#### Document Models

| Document | Collection | Key Fields | Required Indexes |
| --- | --- | --- | --- |
| `AnalyticsFactDocument extends AuditedDocument` | `analytics_facts` | `tenantId`, `orgId`, `eventId`, `eventType`, `sourceContext`, `occurredAt`, `userId`, `payload` | unique `{tenantId:1, orgId:1, eventId:1}`, `{tenantId:1, orgId:1, eventType:1, occurredAt:-1}`, `{tenantId:1, orgId:1, userId:1, occurredAt:-1}` |
| `KpiSnapshotDocument extends AuditedDocument` | `kpi_snapshots` | `tenantId`, `orgId`, `kpiKey`, `windowStart`, `windowEnd`, `value`, `dimensions`, `computedAt` | `{tenantId:1, orgId:1, kpiKey:1, computedAt:-1}` |
| `ProjectionLagDocument extends AuditedDocument` | `projection_lag` | `tenantId`, `orgId`, `stream`, `lastEventAt`, `lagSeconds` | unique `{tenantId:1, orgId:1, stream:1}` |

#### Repository Interfaces

- `AnalyticsFactRepository`
  - `boolean existsByTenantIdAndOrgIdAndEventId(String tenantId, String orgId, String eventId)`
  - `Page<AnalyticsFactDocument> findByTenantIdAndOrgIdAndEventTypeAndOccurredAtBetween(...)`
- `KpiSnapshotRepository`
  - `List<KpiSnapshotDocument> findTop20ByTenantIdAndOrgIdAndKpiKeyOrderByComputedAtDesc(...)`

#### Services

- `AnalyticsIngestionService`
  - validates envelope + dedup + persists append-only facts
- `KpiComputationService`
  - scheduled KPI snapshots (conversion, retention, usage)
- `InsightsQueryService`

Invariants:
- analytical events immutable append-only
- idempotency on `eventId`

#### Controllers

- `InsightsQueryController` (`/api/v1/insights`)
  - `GET /kpis` `@PreAuthorize("hasAuthority('SCOPE_insights.read')")`
  - `GET /conversion` `@PreAuthorize("hasAuthority('SCOPE_insights.read')")`
  - `GET /dropoff` `@PreAuthorize("hasAuthority('SCOPE_insights.read')")`
- `AnalyticsIngestionController` (`/api/v1/insights/internal`)
  - `POST /events` `@PreAuthorize("hasAuthority('SCOPE_insights.write')")`

#### DTO Records

Request:
- `IngestAnalyticsEventRequest(@NotBlank String eventId, @NotBlank String eventType, @NotNull Instant occurredAt, @NotNull Map<String, Object> payload)`

Response:
- `KpiSnapshotResponse(...)`
- `ConversionDashboardResponse(...)`

#### Events and Listeners

Produced:
- `AnalyticsEventIngested`
- `KpiSnapshotPublished`

Consumed:
- all domain events from session/accountability/performance/entitlement/ai/identity

#### Integration Points

- Async consume from all focus topics.
- Optional downstream export adapter to warehouse (outside transactional boundary).

## 6. Domain Event Listeners and Process Managers

### 6.1 Listener Components

| Listener Class | Topic | Action |
| --- | --- | --- |
| `PerformanceSessionEventListener` | `focus.sessions.v1` | Update `DailyPerformanceRecordDocument` and publish score/streak events |
| `PerformanceRoomEventListener` | `focus.rooms.v1` | Apply room-result modifiers to daily score |
| `InsightsUniversalEventListener` | `focus.*.v1` | Persist immutable `AnalyticsFactDocument` entries |
| `SessionEntitlementEventListener` | `focus.entitlements.v1` | Refresh in-memory entitlement cache for command preflight |
| `RoomEntitlementEventListener` | `focus.entitlements.v1` | Enforce room create/join policy caches |

### 6.2 Saga / Process Manager Outlines

- `DeepWorkSessionSaga`
  - steps: entitlement check -> optional AI plan generation -> start session -> completion -> score trigger
  - compensation: AI failure => manual planning mode
- `RoomFocusRevealSaga`
  - steps: room session start -> await participant results -> reveal -> close
  - compensation: host disconnect => host transfer/timeout close
- `SubscriptionTransitionSaga`
  - steps: webhook validation -> state transition -> entitlement events -> cache invalidation
  - compensation: duplicate webhook ignored; downstream retry with DLQ

## 7. Exception Handling and Error Codes

### 7.1 Global Handler

- `GlobalExceptionHandler` in `exception/` with `@RestControllerAdvice`
- Standard response record:
  - `ApiErrorResponse(String code, String message, int status, String correlationId, String tenantId, Instant timestamp)`

### 7.2 Error Code Enum (`ApiErrorCode`)

- Session: `INVALID_DURATION`, `ACTIVE_SESSION_EXISTS`, `COMPLETION_CRITERIA_NOT_MET`, `SESSION_NOT_ACTIVE`, `SUMMARY_MISSING`
- Accountability: `NOT_HOST`, `ROOM_LOCKED`, `ROOM_FULL`, `PARTICIPANT_NOT_FOUND`, `REVEAL_PRECONDITIONS_NOT_MET`
- Performance: `DUPLICATE_EVENT`, `FORMULA_UNAVAILABLE`, `CORRECTION_CONFLICT`
- AI: `PROVIDER_UNAVAILABLE`, `PLAN_VALIDATION_FAILED`, `PLAN_NOT_CURRENT`, `SESSION_DURATION_MISMATCH`
- Entitlement/Billing: `ENTITLEMENT_DENIED`, `DAILY_LIMIT_EXCEEDED`, `QUOTA_EXCEEDED`, `INVALID_WEBHOOK_SIGNATURE`, `DUPLICATE_WEBHOOK_EVENT`
- Identity/Generic: `UNAUTHORIZED`, `FORBIDDEN`, `TENANT_CONTEXT_MISSING`, `CONCURRENCY_CONFLICT`, `INTERNAL_ERROR`

### 7.3 HTTP Mapping

- `ValidationException` -> `400`
- `DomainPolicyViolationException` -> `409`
- `EntitlementDeniedException` -> `403`
- `ResourceNotFoundException` -> `404`
- `DuplicateCommandException` -> `409`
- `OptimisticLockingFailureException` -> `409`
- `InfrastructureTransientException` -> `503`

## 8. Metrics, Tracing, and Audit

### 8.1 Micrometer Metrics

- `focus.sessions.create.count`
- `focus.sessions.complete.count`
- `focus.sessions.command.latency`
- `focus.rooms.active.count`
- `focus.rooms.reveal.latency`
- `focus.performance.score.calc.latency`
- `focus.performance.score.recalc.count`
- `focus.ai.generate.latency`
- `focus.ai.fallback.count`
- `focus.entitlement.check.latency`
- `focus.entitlement.denied.count`
- `focus.billing.webhook.process.latency`
- `focus.events.outbox.pending`
- `focus.events.consumer.lag.seconds`

Tags:
- `tenantId` (low-cardinality or hashed partition tag per policy)
- `context`
- `operation`
- `result`

### 8.2 OpenTelemetry

- Trace HTTP + Mongo + messaging operations.
- Propagate `correlationId` as baggage/header to events.
- Include span attributes: `tenant.id`, `org.id`, `event.type`, `aggregate.id`.

### 8.3 Audit

- `AuditLogService` writes immutable audit entries for:
  - entitlement grants/revokes
  - billing transitions/webhooks
  - moderation actions
  - score corrections

## 9. Configuration Templates

### 9.1 `application.yml`

```yaml
spring:
  application:
    name: focusflow
  data:
    mongodb:
      uri: ${MONGODB_URI}
      auto-index-creation: true

server:
  port: ${SERVER_PORT:8080}

ostec:
  security:
    resource-server:
      enabled: true
      issuer-uri: ${KEYCLOAK_ISSUER_URI}
      audience: ${spring.application.name}-api
    permit-all:
      - /actuator/**
      - /health/**
      - /swagger-ui/**
      - /api-docs/**
      - /v3/api-docs/**
      - /error

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: when_authorized
  metrics:
    tags:
      application: ${spring.application.name}

springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui

otel:
  service:
    name: ${spring.application.name}

logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-},%X{correlationId:-}]"
```

### 9.2 `.env.example`

```dotenv
SPRING_PROFILES_ACTIVE=local
SERVER_PORT=8080

MONGODB_URI=mongodb://localhost:27017/focusflow

KEYCLOAK_ISSUER_URI=https://keycloak.example.com/realms/ostec

# Optional service integrations
STRIPE_WEBHOOK_SECRET=whsec_replace_me
AI_PROVIDER_API_KEY=replace_me
AI_PROVIDER_TIMEOUT_MS=8000

# Eventing
EVENT_BUS_BOOTSTRAP_SERVERS=localhost:9092
OUTBOX_PUBLISH_INTERVAL_MS=1000

# Observability
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
MANAGEMENT_PROMETHEUS_ENABLED=true
```

## 10. Output Checklist Verification

| Checklist Item | Status | Notes |
| --- | --- | --- |
| All `@Document` models include `tenantId` and `orgId` | ✅ | Every context document table includes both fields. |
| Compound indexes defined with tenant scope | ✅ | Indexes listed per document include tenant partition keys. |
| Repositories include tenant-scoped queries | ✅ | Method signatures include `tenantId` and `orgId`. |
| Services use `TenantContextHolder` and `SecurityContextHelper` | ✅ | Cross-cutting rules require both in all command/query services. |
| No custom security chain | ✅ | Security handled only by `ostec-security-starter`. |
| Controllers protected with `@PreAuthorize` | ✅ | Every endpoint in each context includes `@PreAuthorize`. |
| DTOs are Java records with validation | ✅ | Request/response record sets defined per context with Bean Validation. |
| Domain events defined for state changes | ✅ | Produced/consumed events listed per context + listener matrix. |
| Exception handler and error codes | ✅ | `GlobalExceptionHandler` + `ApiErrorCode` included. |
| Metrics and observability included | ✅ | Micrometer + OTel + audit responsibilities specified. |
| `application.yml` + `.env.example` included | ✅ | Template sections provided. |
| Public paths configured | ✅ | Required public endpoints explicitly listed. |


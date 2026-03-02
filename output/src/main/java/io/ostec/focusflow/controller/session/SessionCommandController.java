package io.ostec.focusflow.controller.session;

import io.ostec.focusflow.dto.request.session.AbortFocusSessionRequest;
import io.ostec.focusflow.dto.request.session.AttachSessionPlanRequest;
import io.ostec.focusflow.dto.request.session.CompleteFocusSessionRequest;
import io.ostec.focusflow.dto.request.session.CreateFocusSessionRequest;
import io.ostec.focusflow.dto.request.session.RecordMidSessionCheckInRequest;
import io.ostec.focusflow.dto.request.session.RecordSessionInterruptionRequest;
import io.ostec.focusflow.dto.response.session.FocusSessionResponse;
import io.ostec.focusflow.mapper.session.SessionMapper;
import io.ostec.focusflow.service.session.SessionCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/sessions")
@Tag(name = "Session Commands", description = "Write endpoints for focus session execution")
public class SessionCommandController {

    private final SessionCommandService sessionCommandService;
    private final SessionMapper sessionMapper;

    public SessionCommandController(SessionCommandService sessionCommandService, SessionMapper sessionMapper) {
        this.sessionCommandService = sessionCommandService;
        this.sessionMapper = sessionMapper;
    }

    @PostMapping({"", "/"})
    @PreAuthorize("hasAuthority('SCOPE_sessions.write')")
    @Operation(summary = "Create a focus session")
    public FocusSessionResponse createSession(@Valid @RequestBody CreateFocusSessionRequest request) {
        return sessionMapper.toFocusSessionResponse(
            sessionCommandService.createSession(
                request.goal(),
                request.durationMinutes(),
                request.correlationId()
            )
        );
    }

    @PostMapping("/{sessionId}/plan")
    @PreAuthorize("hasAuthority('SCOPE_sessions.write')")
    @Operation(summary = "Attach a plan to a focus session")
    public FocusSessionResponse attachPlan(
        @PathVariable String sessionId,
        @Valid @RequestBody AttachSessionPlanRequest request
    ) {
        return sessionMapper.toFocusSessionResponse(
            sessionCommandService.attachSessionPlan(sessionId, request.planId(), request.taskCount())
        );
    }

    @PostMapping("/{sessionId}/start")
    @PreAuthorize("hasAuthority('SCOPE_sessions.write')")
    @Operation(summary = "Start a focus session")
    public FocusSessionResponse startSession(@PathVariable String sessionId) {
        return sessionMapper.toFocusSessionResponse(sessionCommandService.startSession(sessionId));
    }

    @PostMapping("/{sessionId}/check-ins")
    @PreAuthorize("hasAuthority('SCOPE_sessions.write')")
    @Operation(summary = "Record a mid-session check-in")
    public FocusSessionResponse recordCheckIn(
        @PathVariable String sessionId,
        @Valid @RequestBody RecordMidSessionCheckInRequest request
    ) {
        return sessionMapper.toFocusSessionResponse(
            sessionCommandService.recordCheckIn(sessionId, request.status(), request.note())
        );
    }

    @PostMapping("/{sessionId}/interruptions")
    @PreAuthorize("hasAuthority('SCOPE_sessions.write')")
    @Operation(summary = "Record a session interruption")
    public FocusSessionResponse recordInterruption(
        @PathVariable String sessionId,
        @Valid @RequestBody RecordSessionInterruptionRequest request
    ) {
        return sessionMapper.toFocusSessionResponse(
            sessionCommandService.recordInterruption(sessionId, request.interruptionType(), request.detectedAt())
        );
    }

    @PostMapping("/{sessionId}/complete")
    @PreAuthorize("hasAuthority('SCOPE_sessions.write')")
    @Operation(summary = "Complete a focus session")
    public FocusSessionResponse completeSession(
        @PathVariable String sessionId,
        @Valid @RequestBody CompleteFocusSessionRequest request
    ) {
        return sessionMapper.toFocusSessionResponse(
            sessionCommandService.completeSession(sessionId, request.summary(), request.deepWorkMinutes())
        );
    }

    @PostMapping("/{sessionId}/abort")
    @PreAuthorize("hasAuthority('SCOPE_sessions.write')")
    @Operation(summary = "Abort a focus session")
    public FocusSessionResponse abortSession(
        @PathVariable String sessionId,
        @Valid @RequestBody AbortFocusSessionRequest request
    ) {
        return sessionMapper.toFocusSessionResponse(sessionCommandService.abortSession(sessionId, request.reason()));
    }
}

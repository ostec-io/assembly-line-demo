package io.ostec.focusflow.controller.session;

import io.ostec.focusflow.dto.response.session.SessionHistoryItemResponse;
import io.ostec.focusflow.dto.response.session.TodaySessionStatusResponse;
import io.ostec.focusflow.mapper.session.SessionMapper;
import io.ostec.focusflow.service.session.SessionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/sessions")
@Tag(name = "Session Queries", description = "Read endpoints for focus session execution")
public class SessionQueryController {

    private final SessionQueryService sessionQueryService;
    private final SessionMapper sessionMapper;

    public SessionQueryController(SessionQueryService sessionQueryService, SessionMapper sessionMapper) {
        this.sessionQueryService = sessionQueryService;
        this.sessionMapper = sessionMapper;
    }

    @GetMapping("/today")
    @PreAuthorize("hasAuthority('SCOPE_sessions.read')")
    @Operation(summary = "Get current user's session status for today")
    public TodaySessionStatusResponse getTodayStatus() {
        return sessionMapper.toTodaySessionStatusResponse(sessionQueryService.getTodayStatus());
    }

    @GetMapping("/history")
    @PreAuthorize("hasAuthority('SCOPE_sessions.read')")
    @Operation(summary = "Get current user's focus session history")
    public List<SessionHistoryItemResponse> getHistory(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant from,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant to,
        @RequestParam(defaultValue = "0")
        @Min(0)
        int page,
        @RequestParam(defaultValue = "20")
        @Min(1)
        @Max(200)
        int size
    ) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return sessionQueryService.getSessionHistory(from, to, pageRequest)
            .map(sessionMapper::toSessionHistoryItemResponse)
            .getContent();
    }
}

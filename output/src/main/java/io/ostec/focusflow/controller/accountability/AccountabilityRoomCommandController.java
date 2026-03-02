package io.ostec.focusflow.controller.accountability;

import io.ostec.focusflow.dto.request.accountability.CreateAccountabilityRoomRequest;
import io.ostec.focusflow.dto.request.accountability.JoinAccountabilityRoomRequest;
import io.ostec.focusflow.dto.request.accountability.SetParticipantGoalRequest;
import io.ostec.focusflow.dto.request.accountability.SubmitParticipantResultRequest;
import io.ostec.focusflow.dto.response.accountability.AccountabilityRoomResponse;
import io.ostec.focusflow.mapper.accountability.AccountabilityRoomMapper;
import io.ostec.focusflow.service.accountability.AccountabilityRoomCommandService;
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
@RequestMapping("/api/v1/rooms")
@Tag(name = "Accountability Room Commands", description = "Write endpoints for accountability rooms")
public class AccountabilityRoomCommandController {

    private final AccountabilityRoomCommandService accountabilityRoomCommandService;
    private final AccountabilityRoomMapper accountabilityRoomMapper;

    public AccountabilityRoomCommandController(
        AccountabilityRoomCommandService accountabilityRoomCommandService,
        AccountabilityRoomMapper accountabilityRoomMapper
    ) {
        this.accountabilityRoomCommandService = accountabilityRoomCommandService;
        this.accountabilityRoomMapper = accountabilityRoomMapper;
    }

    @PostMapping({"", "/"})
    @PreAuthorize("hasAuthority('SCOPE_rooms.write')")
    @Operation(summary = "Create an accountability room")
    public AccountabilityRoomResponse createRoom(@Valid @RequestBody CreateAccountabilityRoomRequest request) {
        return accountabilityRoomMapper.toAccountabilityRoomResponse(
            accountabilityRoomCommandService.createRoom(
                request.title(),
                request.visibility(),
                request.capacity(),
                null
            )
        );
    }

    @PostMapping("/{roomId}/join")
    @PreAuthorize("hasAuthority('SCOPE_rooms.write')")
    @Operation(summary = "Join an accountability room")
    public AccountabilityRoomResponse joinRoom(
        @PathVariable String roomId,
        @Valid @RequestBody JoinAccountabilityRoomRequest request
    ) {
        if (!roomId.equals(request.roomId())) {
            throw new IllegalStateException("ROOM_ID_MISMATCH");
        }

        return accountabilityRoomMapper.toAccountabilityRoomResponse(
            accountabilityRoomCommandService.joinRoom(roomId)
        );
    }

    @PostMapping("/{roomId}/goal")
    @PreAuthorize("hasAuthority('SCOPE_rooms.write')")
    @Operation(summary = "Set current participant goal")
    public AccountabilityRoomResponse setGoal(
        @PathVariable String roomId,
        @Valid @RequestBody SetParticipantGoalRequest request
    ) {
        return accountabilityRoomMapper.toAccountabilityRoomResponse(
            accountabilityRoomCommandService.setParticipantGoal(roomId, request.goal())
        );
    }

    @PostMapping("/{roomId}/lock")
    @PreAuthorize("hasAuthority('SCOPE_rooms.write')")
    @Operation(summary = "Lock an accountability room")
    public AccountabilityRoomResponse lockRoom(@PathVariable String roomId) {
        return accountabilityRoomMapper.toAccountabilityRoomResponse(accountabilityRoomCommandService.lockRoom(roomId));
    }

    @PostMapping("/{roomId}/start")
    @PreAuthorize("hasAuthority('SCOPE_rooms.write')")
    @Operation(summary = "Start accountability room session")
    public AccountabilityRoomResponse startRoomSession(@PathVariable String roomId) {
        return accountabilityRoomMapper.toAccountabilityRoomResponse(
            accountabilityRoomCommandService.startRoomSession(roomId)
        );
    }

    @PostMapping("/{roomId}/results")
    @PreAuthorize("hasAuthority('SCOPE_rooms.write')")
    @Operation(summary = "Submit current participant result")
    public AccountabilityRoomResponse submitResult(
        @PathVariable String roomId,
        @Valid @RequestBody SubmitParticipantResultRequest request
    ) {
        return accountabilityRoomMapper.toAccountabilityRoomResponse(
            accountabilityRoomCommandService.submitParticipantResult(
                roomId,
                request.summary(),
                request.deepWorkMinutes()
            )
        );
    }

    @PostMapping("/{roomId}/reveal")
    @PreAuthorize("hasAuthority('SCOPE_rooms.write')")
    @Operation(summary = "Reveal accountability room results")
    public AccountabilityRoomResponse revealRoomResults(@PathVariable String roomId) {
        return accountabilityRoomMapper.toAccountabilityRoomResponse(
            accountabilityRoomCommandService.revealRoomResults(roomId)
        );
    }

    @PostMapping("/{roomId}/close")
    @PreAuthorize("hasAuthority('SCOPE_rooms.write')")
    @Operation(summary = "Close an accountability room")
    public AccountabilityRoomResponse closeRoom(@PathVariable String roomId) {
        return accountabilityRoomMapper.toAccountabilityRoomResponse(accountabilityRoomCommandService.closeRoom(roomId));
    }
}

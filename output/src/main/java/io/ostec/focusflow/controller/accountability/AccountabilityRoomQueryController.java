package io.ostec.focusflow.controller.accountability;

import io.ostec.focusflow.dto.response.accountability.RoomLobbyItemResponse;
import io.ostec.focusflow.dto.response.accountability.RoomResultBoardResponse;
import io.ostec.focusflow.mapper.accountability.AccountabilityRoomMapper;
import io.ostec.focusflow.service.accountability.AccountabilityRoomQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/rooms")
@Tag(name = "Accountability Room Queries", description = "Read endpoints for accountability rooms")
public class AccountabilityRoomQueryController {

    private final AccountabilityRoomQueryService accountabilityRoomQueryService;
    private final AccountabilityRoomMapper accountabilityRoomMapper;

    public AccountabilityRoomQueryController(
        AccountabilityRoomQueryService accountabilityRoomQueryService,
        AccountabilityRoomMapper accountabilityRoomMapper
    ) {
        this.accountabilityRoomQueryService = accountabilityRoomQueryService;
        this.accountabilityRoomMapper = accountabilityRoomMapper;
    }

    @GetMapping("/lobby")
    @PreAuthorize("hasAuthority('SCOPE_rooms.read')")
    @Operation(summary = "List open public accountability rooms")
    public List<RoomLobbyItemResponse> getLobby(
        @RequestParam(defaultValue = "0")
        @Min(0)
        int page,
        @RequestParam(defaultValue = "20")
        @Min(1)
        @Max(200)
        int size
    ) {
        return accountabilityRoomQueryService.listOpenPublicRooms(page, size)
            .map(accountabilityRoomMapper::toRoomLobbyItemResponse)
            .getContent();
    }

    @GetMapping("/{roomId}")
    @PreAuthorize("hasAuthority('SCOPE_rooms.read')")
    @Operation(summary = "Get accountability room result board")
    public RoomResultBoardResponse getRoom(@PathVariable String roomId) {
        return accountabilityRoomMapper.toRoomResultBoardResponse(
            accountabilityRoomQueryService.getRoomLivePresence(roomId)
        );
    }
}

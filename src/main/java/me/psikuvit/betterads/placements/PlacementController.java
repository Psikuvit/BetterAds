package me.psikuvit.betterads.placements;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.placements.dto.EventRequest;
import me.psikuvit.betterads.placements.dto.EventResponse;
import me.psikuvit.betterads.placements.dto.SelectRequest;
import me.psikuvit.betterads.placements.dto.SelectResponse;
import me.psikuvit.betterads.placements.dto.SessionRequest;
import me.psikuvit.betterads.placements.dto.SessionResponse;
import me.psikuvit.betterads.security.ClientIpResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/placements")
@Slf4j
public class PlacementController {

    private final SessionService sessionService;
    private final AdSelectionService adSelectionService;
    private final ClientIpResolver clientIpResolver;

    public PlacementController(SessionService sessionService, AdSelectionService adSelectionService,
                               ClientIpResolver clientIpResolver) {
        this.sessionService = sessionService;
        this.adSelectionService = adSelectionService;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/{siteKey}/select")
    public ResponseEntity<SelectResponse> selectAd(@PathVariable String siteKey,
                                                   @Valid @RequestBody SelectRequest request,
                                                   HttpServletRequest httpRequest) {
        String ip = clientIpResolver.resolve(httpRequest);
        SelectResponse response = adSelectionService.selectAd(siteKey, request, httpRequest, ip);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{siteKey}/session")
    public ResponseEntity<SessionResponse> createSession(@PathVariable String siteKey,
                                                         @Valid @RequestBody SessionRequest request,
                                                         HttpServletRequest httpRequest) {
        String ip = clientIpResolver.resolve(httpRequest);
        String deviceInfo = httpRequest.getHeader("User-Agent");
        SessionResponse response = sessionService.createSession(siteKey, request, httpRequest, ip, deviceInfo);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/session/{sessionToken}/events")
    public ResponseEntity<EventResponse> recordEvent(@PathVariable String sessionToken,
                                                      @Valid @RequestBody EventRequest request) {
        return ResponseEntity.ok(sessionService.recordEvent(sessionToken, request));
    }
}

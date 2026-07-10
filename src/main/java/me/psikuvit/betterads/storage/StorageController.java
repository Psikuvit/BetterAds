package me.psikuvit.betterads.storage;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.auth.CurrentUserService;
import me.psikuvit.betterads.storage.dto.PresignRequest;
import me.psikuvit.betterads.storage.entities.User;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/upload")
@Slf4j
public class StorageController {

    private final StorageService storageService;
    private final CurrentUserService currentUserService;

    public StorageController(StorageService storageService, CurrentUserService currentUserService) {
        this.storageService = storageService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/presign")
    @PreAuthorize("hasRole('ADVERTISER')")
    public ResponseEntity<?> presign(@Valid @RequestBody PresignRequest request, Authentication auth) {
        log.info("POST /api/upload/presign called with key={}, contentType={}", request.key(), request.contentType());
        if (!UploadPolicy.ALLOWED_CONTENT_TYPES.contains(request.contentType())) {
            log.warn("Rejected presign request: contentType={} not in allowed types", request.contentType());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "contentType must be one of: " + UploadPolicy.ALLOWED_CONTENT_TYPES));
        }
        User user = currentUserService.resolve(auth);
        String expectedPrefix = "ads/" + user.getId() + "/";
        if (!request.key().startsWith(expectedPrefix)) {
            log.warn("Rejected presign request: key={} not namespaced under expectedPrefix={} for userId={}", request.key(), expectedPrefix, user.getId());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "key must be namespaced under " + expectedPrefix));
        }
        String url = storageService.presignPutUrl(request.key(), request.contentType(), Duration.ofMinutes(15));
        log.info("Presigned PUT URL generated for key={}, userId={}", request.key(), user.getId());
        return ResponseEntity.ok(Map.of("url", url));
    }
}

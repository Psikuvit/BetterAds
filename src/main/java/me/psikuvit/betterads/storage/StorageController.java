package me.psikuvit.betterads.storage;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/upload")
public class StorageController {

    private final StorageService storageService;

    public StorageController(StorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping("/presign")
    public Map<String, String> presign(@RequestBody Map<String, String> body) {
        String key = body.get("key");
        String contentType = body.getOrDefault("contentType", "application/octet-stream");
        String url = storageService.presignPutUrl(key, contentType, Duration.ofMinutes(15));
        return Map.of("url", url);
    }
}

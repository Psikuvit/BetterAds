package me.psikuvit.betterads.embed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/embed")
@Slf4j
public class EmbedController {

    private final EmbedService embedService;

    public EmbedController(EmbedService embedService) {
        this.embedService = embedService;
    }

    @GetMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> widget(@PathVariable String token) {
        return embedService.findByToken(token)
                .map(link -> {
                    log.info("Serving widget for token={}, adId={}", token, link.getAdId());
                    return ResponseEntity.ok()
                            .contentType(MediaType.TEXT_HTML)
                            .body(embedService.widgetHtml(link.getAdId()));
                })
                .orElseGet(() -> {
                    log.warn("Widget requested for unknown token={}", token);
                    return ResponseEntity.notFound().build();
                });
    }
}

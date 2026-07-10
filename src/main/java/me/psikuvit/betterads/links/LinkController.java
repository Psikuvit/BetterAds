package me.psikuvit.betterads.links;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/links")
@Slf4j
public class LinkController {

    private final LinkService linkService;

    public LinkController(LinkService linkService) {
        this.linkService = linkService;
    }

    @GetMapping("/{adId}")
    @PreAuthorize("hasAnyRole('PUBLISHER', 'ADVERTISER')")
    public List<String> resolve(@PathVariable Long adId,
                                @RequestParam(required = false) String locale) {
        log.info("Resolving variants for adId={}, locale={}", adId, locale);
        return linkService.resolveStorageKeys(adId, locale);
    }
}

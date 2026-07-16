package me.psikuvit.betterads.placements;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.billing.BillingService;
import me.psikuvit.betterads.fraud.FraudService;
import me.psikuvit.betterads.fraud.exceptions.TooManyRequestsException;
import me.psikuvit.betterads.placements.dto.EventRequest;
import me.psikuvit.betterads.placements.dto.EventResponse;
import me.psikuvit.betterads.placements.dto.SessionRequest;
import me.psikuvit.betterads.placements.dto.SessionResponse;
import me.psikuvit.betterads.placements.exceptions.EventSequenceException;
import me.psikuvit.betterads.placements.exceptions.InvalidSessionException;
import me.psikuvit.betterads.storage.AdVariantResolver;
import me.psikuvit.betterads.storage.StorageService;
import me.psikuvit.betterads.storage.dto.AdSessionStatus;
import me.psikuvit.betterads.storage.dto.SessionEventType;
import me.psikuvit.betterads.storage.dto.SiteStatus;
import me.psikuvit.betterads.storage.entities.Ad;
import me.psikuvit.betterads.storage.entities.AdSession;
import me.psikuvit.betterads.storage.entities.AdVersion;
import me.psikuvit.betterads.storage.entities.Site;
import me.psikuvit.betterads.storage.entities.SessionEvent;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import me.psikuvit.betterads.storage.repositories.AdSessionRepository;
import me.psikuvit.betterads.storage.repositories.AdVersionRepository;
import me.psikuvit.betterads.storage.repositories.SessionEventRepository;
import me.psikuvit.betterads.storage.repositories.SiteRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class SessionService {

    // Order billable/analytics events must arrive in. ERROR is handled
    // separately — reachable from any non-terminal state, terminal itself.
    private static final List<SessionEventType> EVENT_ORDER = List.of(
            SessionEventType.IMPRESSION_START,
            SessionEventType.QUARTILE_25,
            SessionEventType.QUARTILE_50,
            SessionEventType.QUARTILE_75,
            SessionEventType.COMPLETE
    );

    private static final String REDIS_EVENT_KEY_PREFIX = "placement:event:";

    private final SiteRepository siteRepository;
    private final SiteService siteService;
    private final AdRepository adRepository;
    private final AdVersionRepository adVersionRepository;
    private final AdSessionRepository adSessionRepository;
    private final SessionEventRepository sessionEventRepository;
    private final AdVariantResolver adVariantResolver;
    private final StorageService storageService;
    private final FraudService fraudService;
    private final BillingService billingService;
    private final SessionTokenService sessionTokenService;
    private final StringRedisTemplate redis;
    private final Duration sessionTtl;
    private final Duration minViewability;

    public SessionService(SiteRepository siteRepository,
                          SiteService siteService,
                          AdRepository adRepository,
                          AdVersionRepository adVersionRepository,
                          AdSessionRepository adSessionRepository,
                          SessionEventRepository sessionEventRepository,
                          AdVariantResolver adVariantResolver,
                          StorageService storageService,
                          FraudService fraudService,
                          BillingService billingService,
                          SessionTokenService sessionTokenService,
                          StringRedisTemplate redis,
                          @Value("${app.placements.session-ttl-minutes:15}") long sessionTtlMinutes,
                          @Value("${app.placements.min-viewability-ms:2000}") long minViewabilityMs) {
        this.siteRepository = siteRepository;
        this.siteService = siteService;
        this.adRepository = adRepository;
        this.adVersionRepository = adVersionRepository;
        this.adSessionRepository = adSessionRepository;
        this.sessionEventRepository = sessionEventRepository;
        this.adVariantResolver = adVariantResolver;
        this.storageService = storageService;
        this.fraudService = fraudService;
        this.billingService = billingService;
        this.sessionTokenService = sessionTokenService;
        this.redis = redis;
        this.sessionTtl = Duration.ofMinutes(sessionTtlMinutes);
        this.minViewability = Duration.ofMillis(minViewabilityMs);
    }

    @Transactional
    public SessionResponse createSession(String siteKey, SessionRequest request, HttpServletRequest httpRequest,
                                         String ip, String deviceInfo) {
        Site site = siteRepository.findBySiteKey(siteKey)
                .filter(s -> s.getStatus() == SiteStatus.ACTIVE)
                .orElseThrow(() -> new NoSuchElementException("Site not found for key: " + siteKey));

        siteService.validateOrigin(site, httpRequest, request.bundleId());

        // Always enforced now — no more "a valid token bypasses this" shortcut.
        if (fraudService.isLikelyFraud(ip)) {
            log.warn("Blocked session request for site={} from ip={}", site.getSiteKey(), ip);
            throw new TooManyRequestsException("Too many requests from this IP");
        }

        Ad ad = adRepository.findById(request.adId())
                .orElseThrow(() -> new NoSuchElementException("Ad not found: " + request.adId()));
        Long campaignId = ad.getCampaignId();
        if (campaignId != null && fraudService.isCampaignOverVelocity(campaignId)) {
            log.warn("Blocked session request for adId={} — campaign {} exceeded view velocity cap", ad.getId(), campaignId);
            throw new TooManyRequestsException("This ad is receiving too many requests, please try again shortly");
        }

        List<AdVersion> all = adVersionRepository.findByAdId(ad.getId());
        if (all.isEmpty()) {
            throw new NoSuchElementException("No ad versions found for ad: " + ad.getId());
        }
        AdVersion best = adVariantResolver.resolveVariants(all, request.locale()).getFirst();

        Instant now = Instant.now();
        Instant expiresAt = now.plus(sessionTtl);
        String sessionToken = sessionTokenService.issue(expiresAt);

        AdSession session = new AdSession();
        session.setSiteId(site.getId());
        session.setAdId(ad.getId());
        session.setAdVersionId(best.getId());
        session.setCampaignId(campaignId);
        session.setSessionToken(sessionToken);
        session.setViewerIp(ip);
        session.setDeviceInfo(deviceInfo);
        session.setIssuedAt(now);
        session.setExpiresAt(expiresAt);
        session.setStatus(AdSessionStatus.ACTIVE);
        adSessionRepository.save(session);

        String videoUrl = storageService.presignGetUrl(
                StorageService.extractStorageKey(best.getStorageKey()), Duration.ofHours(2));

        log.info("Created placement session for site={}, adId={}, adVersionId={}, ip={}",
                site.getSiteKey(), ad.getId(), best.getId(), ip);
        return new SessionResponse(sessionToken, ad.getId(), best.getId(), videoUrl, best.getLocale(), best.getDurationSeconds());
    }

    @Transactional
    public EventResponse recordEvent(String sessionToken, EventRequest request) {
        if (!sessionTokenService.isValid(sessionToken)) {
            throw new InvalidSessionException("Session token is invalid or expired");
        }

        AdSession lookup = adSessionRepository.findBySessionToken(sessionToken)
                .orElseThrow(() -> new InvalidSessionException("Session not found"));

        // Locked for the rest of this transaction so two events for the same
        // session can't race past the ordering/single-use checks below.
        AdSession session = adSessionRepository.findByIdForUpdate(lookup.getId())
                .orElseThrow(() -> new InvalidSessionException("Session not found"));

        if (Instant.now().isAfter(session.getExpiresAt())) {
            throw new InvalidSessionException("Session has expired");
        }

        SessionEventType eventType = request.eventType();

        if (eventType == SessionEventType.ERROR) {
            return recordError(session);
        }

        if (session.getStatus() != AdSessionStatus.ACTIVE) {
            throw new EventSequenceException("Session is no longer active");
        }

        // Redis fast-path dedup ahead of the DB round-trip; the
        // session_events UNIQUE(session_id, event_type) constraint is the
        // durable backstop if this check is ever bypassed (e.g. Redis data loss).
        String redisKey = REDIS_EVENT_KEY_PREFIX + session.getId() + ":" + eventType;
        Boolean firstSeen = redis.opsForValue().setIfAbsent(redisKey, "1", sessionTtl);
        if (!Boolean.TRUE.equals(firstSeen)) {
            throw new EventSequenceException("Duplicate event: " + eventType);
        }

        Set<SessionEventType> recorded = sessionEventRepository.findBySessionId(session.getId()).stream()
                .map(SessionEvent::getEventType)
                .collect(Collectors.toSet());

        int index = EVENT_ORDER.indexOf(eventType);
        for (int i = 0; i < index; i++) {
            if (!recorded.contains(EVENT_ORDER.get(i))) {
                throw new EventSequenceException("Out-of-order event: " + eventType + " received before " + EVENT_ORDER.get(i));
            }
        }
        if (recorded.contains(eventType)) {
            throw new EventSequenceException("Duplicate event: " + eventType);
        }

        if (eventType == SessionEventType.IMPRESSION_START) {
            Duration elapsed = Duration.between(session.getIssuedAt(), Instant.now());
            if (elapsed.compareTo(minViewability) < 0) {
                throw new EventSequenceException(
                        "impression_start rejected: only " + elapsed.toMillis() + "ms elapsed since session issuance, "
                                + "minimum viewable duration is " + minViewability.toMillis() + "ms");
            }
        }

        persistEvent(session.getId(), eventType);

        boolean billed = false;
        if (eventType == SessionEventType.IMPRESSION_START) {
            billed = billingService.recordView(session.getAdVersionId(), session.getViewerIp(), session.getDeviceInfo());
            if (!billed) {
                session.setStatus(AdSessionStatus.ERRORED);
                adSessionRepository.save(session);
                log.info("Session {} billing rejected (budget exhausted) for adVersionId={}", session.getId(), session.getAdVersionId());
                return new EventResponse(true, false);
            }
        } else if (eventType == SessionEventType.COMPLETE) {
            session.setStatus(AdSessionStatus.COMPLETED);
            adSessionRepository.save(session);
        }

        return new EventResponse(true, billed);
    }

    private EventResponse recordError(AdSession session) {
        if (session.getStatus() != AdSessionStatus.ACTIVE) {
            throw new EventSequenceException("Session is already terminal");
        }
        persistEvent(session.getId(), SessionEventType.ERROR);
        session.setStatus(AdSessionStatus.ERRORED);
        adSessionRepository.save(session);
        return new EventResponse(true, false);
    }

    private void persistEvent(Long sessionId, SessionEventType eventType) {
        SessionEvent event = new SessionEvent();
        event.setSessionId(sessionId);
        event.setEventType(eventType);
        sessionEventRepository.save(event);
    }
}

package me.psikuvit.betterads.placements;

import me.psikuvit.betterads.billing.BillingService;
import me.psikuvit.betterads.fraud.FraudService;
import me.psikuvit.betterads.placements.dto.EventRequest;
import me.psikuvit.betterads.placements.dto.EventResponse;
import me.psikuvit.betterads.placements.exceptions.EventSequenceException;
import me.psikuvit.betterads.placements.exceptions.InvalidSessionException;
import me.psikuvit.betterads.storage.AdVariantResolver;
import me.psikuvit.betterads.storage.StorageService;
import me.psikuvit.betterads.storage.dto.AdSessionStatus;
import me.psikuvit.betterads.storage.dto.SessionEventType;
import me.psikuvit.betterads.storage.entities.AdSession;
import me.psikuvit.betterads.storage.entities.SessionEvent;
import me.psikuvit.betterads.storage.repositories.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure-logic tests for the state machine and viewability gate described in
 * the Phase 1 plan — no Spring context needed, everything is mocked.
 */
class SessionServiceTest {

    private static final String TOKEN = "valid-token";
    private static final long SESSION_ID = 1L;
    private static final long AD_VERSION_ID = 42L;

    private AdSessionRepository adSessionRepository;
    private SessionEventRepository sessionEventRepository;
    private BillingService billingService;
    private SessionTokenService sessionTokenService;
    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        SiteRepository siteRepository = mock(SiteRepository.class);
        SiteService siteService = mock(SiteService.class);
        AdRepository adRepository = mock(AdRepository.class);
        AdVersionRepository adVersionRepository = mock(AdVersionRepository.class);
        adSessionRepository = mock(AdSessionRepository.class);
        sessionEventRepository = mock(SessionEventRepository.class);
        AdVariantResolver adVariantResolver = mock(AdVariantResolver.class);
        StorageService storageService = mock(StorageService.class);
        FraudService fraudService = mock(FraudService.class);
        billingService = mock(BillingService.class);
        sessionTokenService = mock(SessionTokenService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);

        sessionService = new SessionService(siteRepository, siteService, adRepository, adVersionRepository,
                adSessionRepository, sessionEventRepository, adVariantResolver, storageService, fraudService,
                billingService, sessionTokenService, redis, 15L, 2000L);

        when(sessionTokenService.isValid(TOKEN)).thenReturn(true);
    }

    private AdSession session(Instant issuedAt, AdSessionStatus status) {
        AdSession session = new AdSession();
        session.setId(SESSION_ID);
        session.setAdVersionId(AD_VERSION_ID);
        session.setSessionToken(TOKEN);
        session.setIssuedAt(issuedAt);
        session.setExpiresAt(issuedAt.plus(Duration.ofMinutes(15)));
        session.setStatus(status);
        return session;
    }

    private void stubSession(AdSession s) {
        when(adSessionRepository.findBySessionToken(TOKEN)).thenReturn(Optional.of(s));
        when(adSessionRepository.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(s));
    }

    private void stubRecordedEvents(SessionEventType... types) {
        List<SessionEvent> events = Arrays.stream(types).map(t -> {
            SessionEvent e = new SessionEvent();
            e.setSessionId(SESSION_ID);
            e.setEventType(t);
            return e;
        }).toList();
        when(sessionEventRepository.findBySessionId(SESSION_ID)).thenReturn(events);
    }

    @Test
    void rejectsImpressionStartTooSoonAfterSessionIssuance() {
        stubSession(session(Instant.now(), AdSessionStatus.ACTIVE));
        stubRecordedEvents();

        assertThatThrownBy(() -> sessionService.recordEvent(TOKEN, new EventRequest(SessionEventType.IMPRESSION_START, null)))
                .isInstanceOf(EventSequenceException.class);

        verifyNoInteractions(billingService);
    }

    @Test
    void acceptsImpressionStartAfterViewabilityWindowAndBills() {
        stubSession(session(Instant.now().minus(Duration.ofSeconds(3)), AdSessionStatus.ACTIVE));
        stubRecordedEvents();
        when(billingService.recordView(eq(AD_VERSION_ID), any(), any())).thenReturn(true);

        EventResponse response = sessionService.recordEvent(TOKEN, new EventRequest(SessionEventType.IMPRESSION_START, null));

        assertThat(response.accepted()).isTrue();
        assertThat(response.billed()).isTrue();
        verify(sessionEventRepository).save(any(SessionEvent.class));
    }

    @Test
    void marksSessionErroredWhenBudgetExhausted() {
        stubSession(session(Instant.now().minus(Duration.ofSeconds(3)), AdSessionStatus.ACTIVE));
        stubRecordedEvents();
        when(billingService.recordView(eq(AD_VERSION_ID), any(), any())).thenReturn(false);

        EventResponse response = sessionService.recordEvent(TOKEN, new EventRequest(SessionEventType.IMPRESSION_START, null));

        assertThat(response.billed()).isFalse();
        verify(adSessionRepository).save(argThat(s -> s.getStatus() == AdSessionStatus.ERRORED));
    }

    @Test
    void rejectsOutOfOrderQuartile() {
        stubSession(session(Instant.now().minus(Duration.ofSeconds(5)), AdSessionStatus.ACTIVE));
        stubRecordedEvents(); // impression_start not yet recorded

        assertThatThrownBy(() -> sessionService.recordEvent(TOKEN, new EventRequest(SessionEventType.QUARTILE_25, null)))
                .isInstanceOf(EventSequenceException.class);
    }

    @Test
    void rejectsDuplicateEvent() {
        stubSession(session(Instant.now().minus(Duration.ofSeconds(5)), AdSessionStatus.ACTIVE));
        stubRecordedEvents(SessionEventType.IMPRESSION_START);

        assertThatThrownBy(() -> sessionService.recordEvent(TOKEN, new EventRequest(SessionEventType.IMPRESSION_START, null)))
                .isInstanceOf(EventSequenceException.class);
    }

    @Test
    void allowsErrorFromActiveStateAndMarksTerminal() {
        stubSession(session(Instant.now(), AdSessionStatus.ACTIVE));

        EventResponse response = sessionService.recordEvent(TOKEN, new EventRequest(SessionEventType.ERROR, "playback failed"));

        assertThat(response.accepted()).isTrue();
        verify(adSessionRepository).save(argThat(s -> s.getStatus() == AdSessionStatus.ERRORED));
    }

    @Test
    void rejectsErrorWhenSessionAlreadyTerminal() {
        stubSession(session(Instant.now(), AdSessionStatus.COMPLETED));

        assertThatThrownBy(() -> sessionService.recordEvent(TOKEN, new EventRequest(SessionEventType.ERROR, null)))
                .isInstanceOf(EventSequenceException.class);
    }

    @Test
    void rejectsEventsAfterSessionExpired() {
        stubSession(session(Instant.now().minus(Duration.ofMinutes(20)), AdSessionStatus.ACTIVE));

        assertThatThrownBy(() -> sessionService.recordEvent(TOKEN, new EventRequest(SessionEventType.IMPRESSION_START, null)))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void rejectsInvalidSignature() {
        when(sessionTokenService.isValid("bad-token")).thenReturn(false);

        assertThatThrownBy(() -> sessionService.recordEvent("bad-token", new EventRequest(SessionEventType.IMPRESSION_START, null)))
                .isInstanceOf(InvalidSessionException.class);
    }
}

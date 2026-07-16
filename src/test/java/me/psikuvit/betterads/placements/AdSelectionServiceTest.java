package me.psikuvit.betterads.placements;

import jakarta.servlet.http.HttpServletRequest;
import me.psikuvit.betterads.fraud.FraudService;
import me.psikuvit.betterads.fraud.exceptions.TooManyRequestsException;
import me.psikuvit.betterads.placements.dto.SelectRequest;
import me.psikuvit.betterads.placements.dto.SelectResponse;
import me.psikuvit.betterads.storage.dto.AdStatus;
import me.psikuvit.betterads.storage.dto.PaceStatus;
import me.psikuvit.betterads.storage.dto.SiteStatus;
import me.psikuvit.betterads.storage.entities.Ad;
import me.psikuvit.betterads.storage.entities.Campaign;
import me.psikuvit.betterads.storage.entities.Site;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import me.psikuvit.betterads.storage.repositories.CampaignRepository;
import me.psikuvit.betterads.storage.repositories.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdSelectionServiceTest {

    private static final String SITE_KEY = "site_abc";
    private static final long CAMPAIGN_ID = 1L;

    private SiteRepository siteRepository;
    private SiteService siteService;
    private CampaignRepository campaignRepository;
    private AdRepository adRepository;
    private FraudService fraudService;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOperations;
    private AdSelectionService service;
    private final HttpServletRequest httpRequest = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        siteRepository = mock(SiteRepository.class);
        siteService = mock(SiteService.class);
        campaignRepository = mock(CampaignRepository.class);
        adRepository = mock(AdRepository.class);
        fraudService = mock(FraudService.class);
        redis = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOperations);

        service = new AdSelectionService(siteRepository, siteService, campaignRepository, adRepository,
                fraudService, redis, 24L, 3, 0.1);

        Site site = new Site();
        site.setId(1L);
        site.setSiteKey(SITE_KEY);
        site.setStatus(SiteStatus.ACTIVE);
        when(siteRepository.findBySiteKey(SITE_KEY)).thenReturn(Optional.of(site));
        when(fraudService.isLikelyFraud(any())).thenReturn(false);
        when(fraudService.isCampaignOverVelocity(any())).thenReturn(false);
    }

    private Ad ad(long id) {
        Ad ad = new Ad();
        ad.setId(id);
        ad.setCampaignId(CAMPAIGN_ID);
        ad.setStatus(AdStatus.LIVE);
        return ad;
    }

    private Campaign campaign(BigDecimal budget, BigDecimal spent, Instant startsAt, Instant endsAt) {
        Campaign campaign = new Campaign();
        campaign.setId(CAMPAIGN_ID);
        campaign.setBudget(budget);
        campaign.setSpent(spent);
        campaign.setStartsAt(startsAt);
        campaign.setEndsAt(endsAt);
        return campaign;
    }

    @Test
    void picksTheFirstEligibleAdWhenNoneAreFrequencyCapped() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(null, null, null, null)));
        when(adRepository.findByCampaignIdAndStatus(CAMPAIGN_ID, AdStatus.LIVE)).thenReturn(List.of(ad(10L), ad(20L)));
        when(valueOperations.get(any())).thenReturn(null);

        SelectResponse response = service.selectAd(SITE_KEY, new SelectRequest(CAMPAIGN_ID, "viewer-1", null), httpRequest, "1.2.3.4");

        assertThat(response.adId()).isEqualTo(10L);
        assertThat(response.paceStatus()).isEqualTo(PaceStatus.UNPACED);
    }

    @Test
    void skipsAFrequencyCappedAdInFavorOfAnEligibleOne() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(null, null, null, null)));
        when(adRepository.findByCampaignIdAndStatus(CAMPAIGN_ID, AdStatus.LIVE)).thenReturn(List.of(ad(10L), ad(20L)));
        when(valueOperations.get("placement:freq:viewer-1:10")).thenReturn("3"); // at the cap
        when(valueOperations.get("placement:freq:viewer-1:20")).thenReturn(null);

        SelectResponse response = service.selectAd(SITE_KEY, new SelectRequest(CAMPAIGN_ID, "viewer-1", null), httpRequest, "1.2.3.4");

        assertThat(response.adId()).isEqualTo(20L);
    }

    @Test
    void failsOpenAndStillServesAnAdWhenEveryCandidateIsCapped() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(null, null, null, null)));
        when(adRepository.findByCampaignIdAndStatus(CAMPAIGN_ID, AdStatus.LIVE)).thenReturn(List.of(ad(10L)));
        when(valueOperations.get("placement:freq:viewer-1:10")).thenReturn("5"); // over the cap

        SelectResponse response = service.selectAd(SITE_KEY, new SelectRequest(CAMPAIGN_ID, "viewer-1", null), httpRequest, "1.2.3.4");

        assertThat(response.adId()).isEqualTo(10L);
    }

    @Test
    void fallsBackToIpAsViewerKeyWhenViewerIdOmitted() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(null, null, null, null)));
        when(adRepository.findByCampaignIdAndStatus(CAMPAIGN_ID, AdStatus.LIVE)).thenReturn(List.of(ad(10L)));
        when(valueOperations.get(any())).thenReturn(null);

        service.selectAd(SITE_KEY, new SelectRequest(CAMPAIGN_ID, null, null), httpRequest, "9.9.9.9");

        verify(valueOperations).get("placement:freq:9.9.9.9:10");
    }

    @Test
    void rejectsWhenIpIsLikelyFraud() {
        when(fraudService.isLikelyFraud("1.2.3.4")).thenReturn(true);

        assertThatThrownBy(() -> service.selectAd(SITE_KEY, new SelectRequest(CAMPAIGN_ID, "v1", null), httpRequest, "1.2.3.4"))
                .isInstanceOf(TooManyRequestsException.class);
        verifyNoInteractions(campaignRepository, adRepository);
    }

    @Test
    void rejectsWhenCampaignOverVelocity() {
        when(fraudService.isCampaignOverVelocity(CAMPAIGN_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.selectAd(SITE_KEY, new SelectRequest(CAMPAIGN_ID, "v1", null), httpRequest, "1.2.3.4"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void throwsWhenSiteNotFound() {
        when(siteRepository.findBySiteKey("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.selectAd("missing", new SelectRequest(CAMPAIGN_ID, "v1", null), httpRequest, "1.2.3.4"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void throwsWhenNoLiveAdsInCampaign() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(null, null, null, null)));
        when(adRepository.findByCampaignIdAndStatus(CAMPAIGN_ID, AdStatus.LIVE)).thenReturn(List.of());

        assertThatThrownBy(() -> service.selectAd(SITE_KEY, new SelectRequest(CAMPAIGN_ID, "v1", null), httpRequest, "1.2.3.4"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void paceStatusIsUnpacedWithoutAFlightWindow() {
        Campaign campaign = campaign(new BigDecimal("100"), new BigDecimal("50"), null, null);
        assertThat(service.computePaceStatus(campaign)).isEqualTo(PaceStatus.UNPACED);
    }

    @Test
    void paceStatusIsAheadWhenSpendOutpacesElapsedTime() {
        Instant now = Instant.now();
        // 10% of the flight elapsed, but 80% of budget already spent.
        Campaign campaign = campaign(new BigDecimal("100"), new BigDecimal("80"),
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(9)));
        assertThat(service.computePaceStatus(campaign)).isEqualTo(PaceStatus.AHEAD);
    }

    @Test
    void paceStatusIsBehindWhenSpendLagsElapsedTime() {
        Instant now = Instant.now();
        // 90% of the flight elapsed, only 10% of budget spent.
        Campaign campaign = campaign(new BigDecimal("100"), new BigDecimal("10"),
                now.minus(Duration.ofHours(9)), now.plus(Duration.ofHours(1)));
        assertThat(service.computePaceStatus(campaign)).isEqualTo(PaceStatus.BEHIND);
    }

    @Test
    void paceStatusIsOnPaceWhenSpendTracksElapsedTimeWithinThreshold() {
        Instant now = Instant.now();
        // 50% elapsed, 52% spent -- within the default 0.1 threshold.
        Campaign campaign = campaign(new BigDecimal("100"), new BigDecimal("52"),
                now.minus(Duration.ofHours(5)), now.plus(Duration.ofHours(5)));
        assertThat(service.computePaceStatus(campaign)).isEqualTo(PaceStatus.ON_PACE);
    }
}

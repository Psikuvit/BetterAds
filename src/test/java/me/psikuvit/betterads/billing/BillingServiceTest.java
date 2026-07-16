package me.psikuvit.betterads.billing;

import me.psikuvit.betterads.storage.AdCleanupService;
import me.psikuvit.betterads.storage.dto.CampaignStatus;
import me.psikuvit.betterads.storage.entities.Ad;
import me.psikuvit.betterads.storage.entities.AdVersion;
import me.psikuvit.betterads.storage.entities.Campaign;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import me.psikuvit.betterads.storage.repositories.AdVersionRepository;
import me.psikuvit.betterads.storage.repositories.CampaignRepository;
import me.psikuvit.betterads.storage.repositories.ViewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BillingServiceTest {

    private static final Long AD_VERSION_ID = 1L;
    private static final Long AD_ID = 2L;
    private static final Long CAMPAIGN_ID = 3L;

    private ViewRepository viewRepository;
    private CampaignRepository campaignRepository;
    private AdCleanupService adCleanupService;
    private BillingService billingService;

    @BeforeEach
    void setUp() {
        viewRepository = mock(ViewRepository.class);
        AdVersionRepository adVersionRepository = mock(AdVersionRepository.class);
        AdRepository adRepository = mock(AdRepository.class);
        campaignRepository = mock(CampaignRepository.class);
        adCleanupService = mock(AdCleanupService.class);
        billingService = new BillingService(viewRepository, adVersionRepository, adRepository, campaignRepository, adCleanupService);

        AdVersion version = new AdVersion();
        version.setId(AD_VERSION_ID);
        version.setAdId(AD_ID);
        version.setLocale("en");
        when(adVersionRepository.findById(AD_VERSION_ID)).thenReturn(Optional.of(version));

        Ad ad = new Ad();
        ad.setId(AD_ID);
        ad.setCampaignId(CAMPAIGN_ID);
        when(adRepository.findById(AD_ID)).thenReturn(Optional.of(ad));
    }

    private Campaign campaign(BigDecimal budget, BigDecimal spent) {
        Campaign campaign = new Campaign();
        campaign.setId(CAMPAIGN_ID);
        campaign.setBudget(budget);
        campaign.setSpent(spent);
        campaign.setStatus(CampaignStatus.ACTIVE);
        return campaign;
    }

    @Test
    void recordsViewAndIncrementsSpentWhenWithinBudget() {
        Campaign campaign = campaign(new BigDecimal("10.00"), BigDecimal.ZERO);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        boolean billed = billingService.recordView(AD_VERSION_ID, "1.2.3.4", "some-agent");

        assertThat(billed).isTrue();
        assertThat(campaign.getSpent()).isEqualByComparingTo("0.001"); // default locale rate
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
        verify(viewRepository).save(any());
        verifyNoInteractions(adCleanupService);
    }

    @Test
    void rejectsAndCompletesCampaignWhenBudgetWouldBeExceeded() {
        Campaign campaign = campaign(new BigDecimal("0.0005"), BigDecimal.ZERO);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        boolean billed = billingService.recordView(AD_VERSION_ID, "1.2.3.4", "some-agent");

        assertThat(billed).isFalse();
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.COMPLETED);
        verify(adCleanupService).deleteCampaignAds(CAMPAIGN_ID);
        verifyNoInteractions(viewRepository);
    }

    @Test
    void appliesLocaleRateAndFeatureSurcharge() {
        Campaign campaign = campaign(new BigDecimal("10.00"), BigDecimal.ZERO);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        BigDecimal cost = billingService.rateFor("US", "translation");

        assertThat(cost).isEqualByComparingTo("0.0025"); // 0.0015 (US) + 0.001 (translation)
    }

    @Test
    void unknownFeatureFallsBackToBaseRateOnly() {
        BigDecimal cost = billingService.rateFor("default", "some-unconfigured-feature");

        assertThat(cost).isEqualByComparingTo("0.001");
    }

    @Test
    void returnsFalseWhenAdVersionDoesNotExist() {
        boolean billed = billingService.recordView(999L, "1.2.3.4", "some-agent");

        assertThat(billed).isFalse();
        verifyNoInteractions(viewRepository, adCleanupService);
    }

    @Test
    void exactlyAtBudgetBoundaryIsStillBilled() {
        Campaign campaign = campaign(new BigDecimal("0.001"), BigDecimal.ZERO);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        boolean billed = billingService.recordView(AD_VERSION_ID, "1.2.3.4", "some-agent");

        assertThat(billed).isTrue();
        assertThat(campaign.getSpent()).isEqualByComparingTo("0.001");
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
    }
}

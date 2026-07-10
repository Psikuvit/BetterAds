package me.psikuvit.betterads.billing;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.storage.entities.View;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import me.psikuvit.betterads.storage.repositories.AdVersionRepository;
import me.psikuvit.betterads.storage.repositories.CampaignRepository;
import me.psikuvit.betterads.storage.repositories.ViewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class BillingService {

    private final Map<String, BigDecimal> rates = new ConcurrentHashMap<>();

    private final ViewRepository viewRepository;
    private final AdVersionRepository adVersionRepository;
    private final AdRepository adRepository;
    private final CampaignRepository campaignRepository;

    public BillingService(ViewRepository viewRepository,
                          AdVersionRepository adVersionRepository,
                          AdRepository adRepository,
                          CampaignRepository campaignRepository) {
        this.viewRepository = viewRepository;
        this.adVersionRepository = adVersionRepository;
        this.adRepository = adRepository;
        this.campaignRepository = campaignRepository;

        rates.put("default", new BigDecimal("0.001"));
        rates.put("US", new BigDecimal("0.0015"));
        rates.put("GB", new BigDecimal("0.0014"));
        rates.put("DE", new BigDecimal("0.0013"));
    }

    public BigDecimal rateFor(String locale) {
        return rates.getOrDefault(locale, rates.get("default"));
    }

    public BigDecimal calculateCharge(String locale, long views) {
        return rateFor(locale).multiply(new BigDecimal(views));
    }

    @Transactional
    public void recordView(Long adVersionId, String ip, String deviceInfo) {
        adVersionRepository.findById(adVersionId).map(adVersion ->
                adRepository.findById(adVersion.getAdId()).map(ad ->
                        // Locked for the rest of this transaction: serializes against
                        // concurrent recordView calls and the payment webhook's budget credit.
                        campaignRepository.findByIdForUpdate(ad.getCampaignId()).map(campaign -> {
                                    BigDecimal cost = rateFor(adVersion.getLocale());
                                    BigDecimal newSpent = campaign.getSpent().add(cost);

                                    if (newSpent.compareTo(campaign.getBudget()) > 0) {
                                        log.warn("Campaign {} budget exhausted (budget={}, spent={}), skipping view",
                                                campaign.getId(), campaign.getBudget(), campaign.getSpent());
                                        return false;
                                    }

                                    View view = new View();
                                    view.setAdVersionId(adVersionId);
                                    view.setViewerIp(ip);
                                    view.setDeviceInfo(deviceInfo);
                                    viewRepository.save(view);

                                    campaign.setSpent(newSpent);
                                    campaignRepository.save(campaign);

                                    log.debug("Recorded view for adVersion={}, campaign={}, cost={}, totalSpent={}",
                                            adVersionId, campaign.getId(), cost, newSpent);
                                    return true;
                                }
                        ).orElse(false)).orElse(false));
    }
}

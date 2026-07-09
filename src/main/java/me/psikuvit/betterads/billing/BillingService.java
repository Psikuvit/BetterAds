package me.psikuvit.betterads.billing;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BillingService {

    // Simple per-locale rate lookup; replace with DB/config store later
    private final Map<String, BigDecimal> rates = new ConcurrentHashMap<>();

    public BillingService() {
        rates.put("default", new BigDecimal("0.001"));
        rates.put("US", new BigDecimal("0.0015"));
    }

    public BigDecimal rateFor(String locale) {
        return rates.getOrDefault(locale, rates.get("default"));
    }

    public BigDecimal calculateCharge(String locale, long views) {
        return rateFor(locale).multiply(new BigDecimal(views));
    }

    // recordView would update campaign counters and prevent overspend (TODO: persist)
    public void recordView(Long campaignId, String locale) {
        // TODO: decrement campaign budget and persist view record
    }
}


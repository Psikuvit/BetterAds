package me.psikuvit.betterads.fraud;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FraudService {

    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();

    public boolean isLikelyFraud(String ip) {
        // Very simple rate check: same IP within 1 second is suspicious
        Instant now = Instant.now();
        Instant last = lastSeen.get(ip);
        lastSeen.put(ip, now);
        if (last != null && now.minusMillis(1000).isBefore(last)) return true;
        return false;
    }
}


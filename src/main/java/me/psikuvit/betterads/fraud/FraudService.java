package me.psikuvit.betterads.fraud;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class FraudService {

    // Max views from a single IP per minute before flagging as fraud
    private static final int MAX_VIEWS_PER_MINUTE = 30;
    private static final long WINDOW_MS = 60_000;

    private final Map<String, Deque<Long>> windowByIp = new ConcurrentHashMap<>();

    public boolean isLikelyFraud(String ip) {
        long now = Instant.now().toEpochMilli();
        Deque<Long> timestamps = windowByIp.computeIfAbsent(ip, k -> new ArrayDeque<>());

        synchronized (timestamps) {
            // Drop timestamps outside the rolling window
            long cutoff = now - WINDOW_MS;
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }
            timestamps.addLast(now);
            int count = timestamps.size();
            if (count > MAX_VIEWS_PER_MINUTE) {
                log.warn("Fraud detected: IP {} made {} impressions in last 60s", ip, count);
                return true;
            }
        }
        return false;
    }
}

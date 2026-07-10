package me.psikuvit.betterads.worker;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.storage.dto.AdStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class AdStatusEventPublisher {

    private static final long TIMEOUT_MS = Duration.ofMinutes(5).toMillis();
    private static final long HEARTBEAT_SECONDS = 15;

    private final Map<Long, List<SseEmitter>> emittersByAdId = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    void startHeartbeat() {
        // Reverse proxies (Render/Cloudflare in front of it) tend to treat a
        // long idle SSE connection as dead and kill it with a 502 before any
        // real event ever fires. A periodic comment (ignored by EventSource
        // clients per the SSE spec) keeps the connection visibly alive.
        heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeats, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopHeartbeat() {
        heartbeatExecutor.shutdown();
    }

    private void sendHeartbeats() {
        for (List<SseEmitter> emitters : emittersByAdId.values()) {
            for (SseEmitter emitter : List.copyOf(emitters)) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    emitters.remove(emitter);
                }
            }
        }
    }

    public SseEmitter subscribe(Long adId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> emitters = emittersByAdId.computeIfAbsent(adId, k -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);

        Runnable remove = () -> {
            emitters.remove(emitter);
            emittersByAdId.remove(adId, emitters.isEmpty() ? emitters : null);
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    public void publish(Long adId, AdStatus status) {
        List<SseEmitter> emitters = emittersByAdId.get(adId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : List.copyOf(emitters)) {
            try {
                emitter.send(SseEmitter.event().name("status").data(Map.of("adId", adId, "status", status)));
            } catch (Exception e) {
                log.debug("Removing dead SSE emitter for adId={}: {}", adId, e.getMessage());
                emitters.remove(emitter);
            }
        }
    }
}

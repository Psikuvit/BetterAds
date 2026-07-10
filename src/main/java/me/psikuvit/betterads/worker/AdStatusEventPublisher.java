package me.psikuvit.betterads.worker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@Slf4j
public class AdStatusEventPublisher {

    private static final long TIMEOUT_MS = Duration.ofMinutes(5).toMillis();

    private final Map<Long, List<SseEmitter>> emittersByAdId = new ConcurrentHashMap<>();

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

    public void publish(Long adId, String status) {
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

package me.psikuvit.betterads.storage.repositories;

import me.psikuvit.betterads.storage.dto.SessionEventType;
import me.psikuvit.betterads.storage.entities.SessionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionEventRepository extends JpaRepository<SessionEvent, Long> {
    List<SessionEvent> findBySessionId(Long sessionId);
    boolean existsBySessionIdAndEventType(Long sessionId, SessionEventType eventType);
}

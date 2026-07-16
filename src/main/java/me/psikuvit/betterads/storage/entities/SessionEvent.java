package me.psikuvit.betterads.storage.entities;

import jakarta.persistence.*;
import me.psikuvit.betterads.storage.dto.SessionEventType;

import java.time.Instant;

@Entity
@Table(name = "session_events")
public class SessionEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long sessionId;

    @Enumerated(EnumType.STRING)
    private SessionEventType eventType;

    private Instant recordedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public SessionEventType getEventType() { return eventType; }
    public void setEventType(SessionEventType eventType) { this.eventType = eventType; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
}

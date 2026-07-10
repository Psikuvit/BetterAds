package me.psikuvit.betterads.storage.entities;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "stripe_events")
public class StripeEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String stripeEventId;

    private String eventType;

    private Instant processedAt = Instant.now();

    public StripeEvent() {}

    public StripeEvent(String stripeEventId, String eventType) {
        this.stripeEventId = stripeEventId;
        this.eventType = eventType;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStripeEventId() { return stripeEventId; }
    public void setStripeEventId(String stripeEventId) { this.stripeEventId = stripeEventId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}

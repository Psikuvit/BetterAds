package me.psikuvit.betterads.storage.entities;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "campaigns")
public class Campaign {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long advertiserId;

    private String name;

    private BigDecimal budget = BigDecimal.ZERO;

    private Instant createdAt = Instant.now();

    // getters/setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAdvertiserId() { return advertiserId; }
    public void setAdvertiserId(Long advertiserId) { this.advertiserId = advertiserId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getBudget() { return budget; }
    public void setBudget(BigDecimal budget) { this.budget = budget; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

package me.psikuvit.betterads.storage.entities;

import jakarta.persistence.*;
import me.psikuvit.betterads.storage.dto.CampaignStatus;

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

    private BigDecimal spent = BigDecimal.ZERO;

    // draft -> active -> paused -> completed
    @Enumerated(EnumType.STRING)
    private CampaignStatus status = CampaignStatus.ACTIVE;

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
    public BigDecimal getSpent() { return spent; }
    public void setSpent(BigDecimal spent) { this.spent = spent; }
    public CampaignStatus getStatus() { return status; }
    public void setStatus(CampaignStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

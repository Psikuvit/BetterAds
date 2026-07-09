package me.psikuvit.betterads.storage.entities;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ads")
public class Ad {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long campaignId;

    private String title;

    private String storageKey;

    private String status; // pending, validating, processing, live, rejected

    private String targetLocale;

    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTargetLocale() { return targetLocale; }
    public void setTargetLocale(String targetLocale) { this.targetLocale = targetLocale; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

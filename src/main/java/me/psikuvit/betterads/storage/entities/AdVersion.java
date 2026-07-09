package me.psikuvit.betterads.storage.entities;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ad_versions")
public class AdVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long adId;

    private String locale;

    private String storageKey;

    private Instant createdAt = Instant.now();

    // getters/setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAdId() { return adId; }
    public void setAdId(Long adId) { this.adId = adId; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

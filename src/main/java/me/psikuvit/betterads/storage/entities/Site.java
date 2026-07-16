package me.psikuvit.betterads.storage.entities;

import jakarta.persistence.*;
import me.psikuvit.betterads.storage.dto.SiteStatus;

import java.time.Instant;

@Entity
@Table(name = "sites")
public class Site {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long publisherId;

    private String name;

    @Column(nullable = false, unique = true)
    private String siteKey;

    private String allowedOrigin;

    private String bundleId;

    @Enumerated(EnumType.STRING)
    private SiteStatus status = SiteStatus.ACTIVE;

    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPublisherId() { return publisherId; }
    public void setPublisherId(Long publisherId) { this.publisherId = publisherId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSiteKey() { return siteKey; }
    public void setSiteKey(String siteKey) { this.siteKey = siteKey; }
    public String getAllowedOrigin() { return allowedOrigin; }
    public void setAllowedOrigin(String allowedOrigin) { this.allowedOrigin = allowedOrigin; }
    public String getBundleId() { return bundleId; }
    public void setBundleId(String bundleId) { this.bundleId = bundleId; }
    public SiteStatus getStatus() { return status; }
    public void setStatus(SiteStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

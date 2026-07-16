package me.psikuvit.betterads.storage.entities;

import jakarta.persistence.*;
import me.psikuvit.betterads.storage.dto.AdSessionStatus;

import java.time.Instant;

@Entity
@Table(name = "ad_sessions")
public class AdSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long siteId;

    private Long adId;

    private Long adVersionId;

    private Long campaignId;

    @Column(nullable = false, unique = true)
    private String sessionToken;

    private String viewerIp;

    private String deviceInfo;

    private Instant issuedAt = Instant.now();

    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    private AdSessionStatus status = AdSessionStatus.ACTIVE;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSiteId() { return siteId; }
    public void setSiteId(Long siteId) { this.siteId = siteId; }
    public Long getAdId() { return adId; }
    public void setAdId(Long adId) { this.adId = adId; }
    public Long getAdVersionId() { return adVersionId; }
    public void setAdVersionId(Long adVersionId) { this.adVersionId = adVersionId; }
    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }
    public String getSessionToken() { return sessionToken; }
    public void setSessionToken(String sessionToken) { this.sessionToken = sessionToken; }
    public String getViewerIp() { return viewerIp; }
    public void setViewerIp(String viewerIp) { this.viewerIp = viewerIp; }
    public String getDeviceInfo() { return deviceInfo; }
    public void setDeviceInfo(String deviceInfo) { this.deviceInfo = deviceInfo; }
    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public AdSessionStatus getStatus() { return status; }
    public void setStatus(AdSessionStatus status) { this.status = status; }
}

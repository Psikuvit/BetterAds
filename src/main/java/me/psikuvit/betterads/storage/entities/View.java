package me.psikuvit.betterads.storage.entities;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "views")
public class View {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long adVersionId;
    private Instant viewedAt = Instant.now();
    private String viewerIp;
    private String deviceInfo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAdVersionId() { return adVersionId; }
    public void setAdVersionId(Long adVersionId) { this.adVersionId = adVersionId; }
    public Instant getViewedAt() { return viewedAt; }
    public void setViewedAt(Instant viewedAt) { this.viewedAt = viewedAt; }
    public String getViewerIp() { return viewerIp; }
    public void setViewerIp(String viewerIp) { this.viewerIp = viewerIp; }
    public String getDeviceInfo() { return deviceInfo; }
    public void setDeviceInfo(String deviceInfo) { this.deviceInfo = deviceInfo; }
}
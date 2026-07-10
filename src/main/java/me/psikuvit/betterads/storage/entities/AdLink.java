package me.psikuvit.betterads.storage.entities;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ad_links")
public class AdLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long adId;

    @Column(nullable = false, unique = true)
    private String token;

    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAdId() { return adId; }
    public void setAdId(Long adId) { this.adId = adId; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

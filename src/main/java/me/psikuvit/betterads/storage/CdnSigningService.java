package me.psikuvit.betterads.storage;

import com.amazonaws.services.cloudfront.CloudFrontUrlSigner;
import com.amazonaws.services.cloudfront.util.SignerUtils;
import com.amazonaws.services.cloudfront.util.SignerUtils.Protocol;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;

/**
 * Signs CloudFront URLs for the placements session manifest (Phase 3),
 * preventing hotlinking/scraping of ad inventory now that video URLs are
 * directly reachable from arbitrary publisher pages (no server-rendered
 * iframe step to gate them behind anymore).
 *
 * Config-gated exactly like the existing Stripe integration: blank/disabled
 * by default, logs a startup warning if partially configured, and degrades
 * gracefully -- callers fall back to StorageService.presignGetUrl when this
 * returns empty. No real CloudFront distribution exists to test this
 * against in this environment; verified instead with a unit test using a
 * throwaway RSA key pair.
 */
@Service
@Slf4j
public class CdnSigningService {

    private final String domain;
    private final String keyPairId;
    private final PrivateKey privateKey;

    public CdnSigningService(@Value("${app.cdn.enabled:false}") boolean enabled,
                             @Value("${app.cdn.domain:}") String domain,
                             @Value("${app.cdn.key-pair-id:}") String keyPairId,
                             @Value("${app.cdn.private-key-path:}") String privateKeyPath) {
        this.domain = domain;
        this.keyPairId = keyPairId;
        this.privateKey = loadKeyIfConfigured(enabled, domain, keyPairId, privateKeyPath);
    }

    private static PrivateKey loadKeyIfConfigured(boolean enabled, String domain, String keyPairId, String privateKeyPath) {
        if (!enabled) {
            return null;
        }
        if (domain == null || domain.isBlank() || keyPairId == null || keyPairId.isBlank()
                || privateKeyPath == null || privateKeyPath.isBlank()) {
            log.warn("app.cdn.enabled=true but app.cdn.domain/key-pair-id/private-key-path is incomplete "
                    + "-- CDN signing disabled, falling back to S3 presigned URLs");
            return null;
        }
        try {
            return SignerUtils.loadPrivateKey(new File(privateKeyPath));
        } catch (Exception e) {
            log.warn("Failed to load CloudFront private key from {} -- CDN signing disabled, "
                    + "falling back to S3 presigned URLs", privateKeyPath, e);
            return null;
        }
    }

    /** Empty if CDN signing is disabled/unconfigured -- callers should fall back to S3 presign. */
    public Optional<String> signCdnUrl(String key, Duration ttl) {
        if (privateKey == null) {
            return Optional.empty();
        }
        try {
            String resourcePath = SignerUtils.generateResourcePath(Protocol.https, domain, key);
            Date expiration = new Date(System.currentTimeMillis() + ttl.toMillis());
            return Optional.of(CloudFrontUrlSigner.getSignedURLWithCannedPolicy(resourcePath, keyPairId, privateKey, expiration));
        } catch (Exception e) {
            log.warn("Failed to sign CDN URL for key={}, falling back to S3 presign", key, e);
            return Optional.empty();
        }
    }
}

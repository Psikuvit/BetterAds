package me.psikuvit.betterads.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CdnSigningServiceTest {

    @TempDir
    Path tempDir;

    private String writeThrowawayPrivateKeyDer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        Path keyFile = tempDir.resolve("test-key.der");
        Files.write(keyFile, keyPair.getPrivate().getEncoded());
        return keyFile.toAbsolutePath().toString();
    }

    @Test
    void signsUrlWithExpectedQueryParamsWhenFullyConfigured() throws Exception {
        String keyPath = writeThrowawayPrivateKeyDer();
        CdnSigningService service = new CdnSigningService(true, "cdn.example.com", "APKAEXAMPLE", keyPath);

        Optional<String> signed = service.signCdnUrl("ads/123/video.mp4", Duration.ofHours(2));

        assertThat(signed).isPresent();
        assertThat(signed.get()).startsWith("https://cdn.example.com/ads/123/video.mp4");
        assertThat(signed.get()).contains("Expires=");
        assertThat(signed.get()).contains("Signature=");
        assertThat(signed.get()).contains("Key-Pair-Id=APKAEXAMPLE");
    }

    @Test
    void returnsEmptyWhenDisabled() {
        CdnSigningService service = new CdnSigningService(false, "cdn.example.com", "APKAEXAMPLE", "/nonexistent.der");

        assertThat(service.signCdnUrl("ads/123/video.mp4", Duration.ofHours(2))).isEmpty();
    }

    @Test
    void returnsEmptyWhenEnabledButIncompletelyConfigured() {
        CdnSigningService service = new CdnSigningService(true, "", "APKAEXAMPLE", "/nonexistent.der");

        assertThat(service.signCdnUrl("ads/123/video.mp4", Duration.ofHours(2))).isEmpty();
    }

    @Test
    void returnsEmptyWhenPrivateKeyFileIsUnreadable() {
        CdnSigningService service = new CdnSigningService(true, "cdn.example.com", "APKAEXAMPLE", "/nonexistent/path/key.der");

        assertThat(service.signCdnUrl("ads/123/video.mp4", Duration.ofHours(2))).isEmpty();
    }
}

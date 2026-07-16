package me.psikuvit.betterads.placements;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionTokenServiceTest {

    private static final String SECRET = "test-session-token-secret-at-least-32-chars-long";

    private final SessionTokenService service = new SessionTokenService(SECRET);

    @Test
    void issuedTokenIsValidBeforeExpiry() {
        String token = service.issue(Instant.now().plus(Duration.ofMinutes(5)));

        assertThat(service.isValid(token)).isTrue();
    }

    @Test
    void expiredTokenIsRejected() {
        String token = service.issue(Instant.now().minus(Duration.ofMinutes(1)));

        assertThat(service.isValid(token)).isFalse();
    }

    @Test
    void tamperedSignatureIsRejected() {
        String token = service.issue(Instant.now().plus(Duration.ofMinutes(5)));
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");

        assertThat(service.isValid(tampered)).isFalse();
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        SessionTokenService other = new SessionTokenService("a-completely-different-secret-of-32-plus-chars");
        String token = other.issue(Instant.now().plus(Duration.ofMinutes(5)));

        assertThat(service.isValid(token)).isFalse();
    }

    @Test
    void malformedTokenIsRejected() {
        assertThat(service.isValid("not-a-real-token")).isFalse();
        assertThat(service.isValid("")).isFalse();
        assertThat(service.isValid(null)).isFalse();
    }

    @Test
    void twoIssuedTokensAreNotEqual() {
        Instant expiry = Instant.now().plus(Duration.ofMinutes(5));
        String tokenA = service.issue(expiry);
        String tokenB = service.issue(expiry);

        assertThat(tokenA).isNotEqualTo(tokenB);
    }

    @Test
    void constructorFailsFastOnBlankSecret() {
        assertThatThrownBy(() -> new SessionTokenService(""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SessionTokenService(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructorFailsFastOnTooShortSecret() {
        assertThatThrownBy(() -> new SessionTokenService("too-short"))
                .isInstanceOf(IllegalStateException.class);
    }
}

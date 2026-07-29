package me.psikuvit.betterads.auth;

import me.psikuvit.betterads.auth.exceptions.AuthenticationException;
import me.psikuvit.betterads.email.EmailService;
import me.psikuvit.betterads.fraud.exceptions.TooManyRequestsException;
import me.psikuvit.betterads.security.TokenHasher;
import me.psikuvit.betterads.storage.entities.PasswordResetCode;
import me.psikuvit.betterads.storage.entities.PasswordResetToken;
import me.psikuvit.betterads.storage.entities.User;
import me.psikuvit.betterads.storage.repositories.PasswordResetCodeRepository;
import me.psikuvit.betterads.storage.repositories.PasswordResetTokenRepository;
import me.psikuvit.betterads.storage.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ForgotPasswordServiceTest {

    private static final String SECRET = "test-reset-code-secret-at-least-32-characters-long";
    private static final String EMAIL = "user@example.com";
    private static final Long USER_ID = 1L;
    private static final String IP = "1.2.3.4";

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private PasswordResetCodeRepository passwordResetCodeRepository;
    private PasswordResetTokenRepository passwordResetTokenRepository;
    private EmailService emailService;
    private ForgotPasswordRateLimiter rateLimiter;
    private ForgotPasswordService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        passwordResetCodeRepository = mock(PasswordResetCodeRepository.class);
        passwordResetTokenRepository = mock(PasswordResetTokenRepository.class);
        emailService = mock(EmailService.class);
        rateLimiter = mock(ForgotPasswordRateLimiter.class);
        when(rateLimiter.isCodeRequestAllowed(any(), any())).thenReturn(true);
        when(rateLimiter.isVerifyAllowed(any())).thenReturn(true);
        when(passwordResetCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(passwordResetTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new ForgotPasswordService(userRepository, passwordEncoder, passwordResetCodeRepository,
                passwordResetTokenRepository, new TokenHasher(), emailService, rateLimiter,
                SECRET, 300_000L, 600_000L);
    }

    private User user() {
        User user = new User(EMAIL, "hashed", me.psikuvit.betterads.storage.dto.Role.ADVERTISER);
        user.setId(USER_ID);
        return user;
    }

    @Test
    void requestCodeDoesNothingObservableForUnknownEmail() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        service.requestCode(EMAIL, IP);

        verifyNoInteractions(emailService);
        verify(passwordResetCodeRepository, never()).save(any());
    }

    @Test
    void requestCodeThrowsWhenRateLimited() {
        when(rateLimiter.isCodeRequestAllowed(EMAIL, IP)).thenReturn(false);

        assertThatThrownBy(() -> service.requestCode(EMAIL, IP))
                .isInstanceOf(TooManyRequestsException.class);
        verifyNoInteractions(userRepository, emailService);
    }

    @Test
    void requestCodeSendsEmailAndInvalidatesAnyExistingActiveCode() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
        PasswordResetCode existing = new PasswordResetCode();
        existing.setUserId(USER_ID);
        when(passwordResetCodeRepository.findAllByUserIdAndUsedAtIsNullAndInvalidatedAtIsNull(USER_ID))
                .thenReturn(List.of(existing));

        service.requestCode(EMAIL, IP);

        assertThat(existing.getInvalidatedAt()).isNotNull();
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordResetCode(eq(EMAIL), codeCaptor.capture());
        assertThat(codeCaptor.getValue()).matches("\\d{6}");

        ArgumentCaptor<PasswordResetCode> savedCaptor = ArgumentCaptor.forClass(PasswordResetCode.class);
        verify(passwordResetCodeRepository, atLeastOnce()).save(savedCaptor.capture());
        PasswordResetCode saved = savedCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getExpiresAt()).isAfter(Instant.now());
        assertThat(saved.getExpiresAt()).isBefore(Instant.now().plusMillis(300_001));
    }

    @Test
    void verifyCodeThrowsForUnknownEmailWithoutTouchingCodeRepository() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyCode(EMAIL, "123456", IP))
                .isInstanceOf(AuthenticationException.class);
        verifyNoInteractions(passwordResetCodeRepository);
    }

    @Test
    void verifyCodeThrowsWhenRateLimited() {
        when(rateLimiter.isVerifyAllowed(IP)).thenReturn(false);

        assertThatThrownBy(() -> service.verifyCode(EMAIL, "123456", IP))
                .isInstanceOf(TooManyRequestsException.class);
        verifyNoInteractions(userRepository);
    }

    @Test
    void correctCodeIssuesSessionTokenAndMarksCodeUsed() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
        TokenHasher hasher = new TokenHasher();
        String rawCode = "654321";
        PasswordResetCode stored = new PasswordResetCode();
        stored.setUserId(USER_ID);
        stored.setCodeHash(hasher.hmac(USER_ID + ":" + rawCode, SECRET));
        stored.setExpiresAt(Instant.now().plusSeconds(60));
        when(passwordResetCodeRepository.findByCodeHash(stored.getCodeHash())).thenReturn(Optional.of(stored));

        String sessionToken = service.verifyCode(EMAIL, rawCode, IP);

        assertThat(sessionToken).isNotBlank();
        assertThat(stored.getUsedAt()).isNotNull();
        verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
    }

    @Test
    void wrongCodeInvalidatesTheActiveCodeSoItCannotBeRetried() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
        // Hash lookup for the wrong guess finds nothing directly...
        when(passwordResetCodeRepository.findByCodeHash(any())).thenReturn(Optional.empty());
        // ...but there IS a currently active code that must be killed as a result.
        PasswordResetCode active = new PasswordResetCode();
        active.setUserId(USER_ID);
        active.setAttempts(0);
        when(passwordResetCodeRepository.findAllByUserIdAndUsedAtIsNullAndInvalidatedAtIsNull(USER_ID))
                .thenReturn(List.of(active));

        assertThatThrownBy(() -> service.verifyCode(EMAIL, "000000", IP))
                .isInstanceOf(AuthenticationException.class);

        assertThat(active.getInvalidatedAt()).isNotNull();
        assertThat(active.getAttempts()).isEqualTo(1);
    }

    @Test
    void expiredCodeIsRejectedEvenWithCorrectValue() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
        TokenHasher hasher = new TokenHasher();
        String rawCode = "111222";
        PasswordResetCode stored = new PasswordResetCode();
        stored.setUserId(USER_ID);
        stored.setCodeHash(hasher.hmac(USER_ID + ":" + rawCode, SECRET));
        stored.setExpiresAt(Instant.now().minusSeconds(1));
        when(passwordResetCodeRepository.findByCodeHash(stored.getCodeHash())).thenReturn(Optional.of(stored));
        when(passwordResetCodeRepository.findAllByUserIdAndUsedAtIsNullAndInvalidatedAtIsNull(USER_ID))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.verifyCode(EMAIL, rawCode, IP))
                .isInstanceOf(AuthenticationException.class);
        assertThat(stored.getUsedAt()).isNull();
    }

    @Test
    void resetPasswordSpendsAValidSessionTokenExactlyOnce() {
        TokenHasher hasher = new TokenHasher();
        String rawSessionToken = hasher.generateOpaqueToken();
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(USER_ID);
        token.setTokenHash(hasher.hash(rawSessionToken));
        token.setExpiresAt(Instant.now().plusSeconds(60));
        when(passwordResetTokenRepository.findByTokenHash(hasher.hash(rawSessionToken)))
                .thenReturn(Optional.of(token));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(passwordEncoder.encode("newSecurePassword")).thenReturn("hashed-new-password");

        service.resetPassword(rawSessionToken, "newSecurePassword");

        assertThat(token.getUsedAt()).isNotNull();
        verify(userRepository).save(argThat(u -> "hashed-new-password".equals(u.getPasswordHash())));
    }

    @Test
    void resetPasswordRejectsAnAlreadyUsedSessionToken() {
        TokenHasher hasher = new TokenHasher();
        String rawSessionToken = hasher.generateOpaqueToken();
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(USER_ID);
        token.setTokenHash(hasher.hash(rawSessionToken));
        token.setExpiresAt(Instant.now().plusSeconds(60));
        token.setUsedAt(Instant.now().minusSeconds(30));
        when(passwordResetTokenRepository.findByTokenHash(hasher.hash(rawSessionToken)))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.resetPassword(rawSessionToken, "newSecurePassword"))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void constructorFailsFastOnBlankOrShortSecret() {
        assertThatThrownBy(() -> new ForgotPasswordService(userRepository, passwordEncoder,
                passwordResetCodeRepository, passwordResetTokenRepository, new TokenHasher(), emailService,
                rateLimiter, "", 300_000L, 600_000L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ForgotPasswordService(userRepository, passwordEncoder,
                passwordResetCodeRepository, passwordResetTokenRepository, new TokenHasher(), emailService,
                rateLimiter, "too-short", 300_000L, 600_000L))
                .isInstanceOf(IllegalStateException.class);
    }
}

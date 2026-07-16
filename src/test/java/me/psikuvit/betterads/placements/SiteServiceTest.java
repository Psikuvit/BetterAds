package me.psikuvit.betterads.placements;

import me.psikuvit.betterads.storage.entities.Site;
import me.psikuvit.betterads.storage.repositories.SiteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SiteServiceTest {

    private final SiteService siteService = new SiteService(mock(SiteRepository.class));

    private Site site(String allowedOrigin, String bundleId) {
        Site site = new Site();
        site.setSiteKey("site_test");
        site.setAllowedOrigin(allowedOrigin);
        site.setBundleId(bundleId);
        return site;
    }

    @Test
    void allowsMatchingOriginHeader() {
        Site site = site("https://publisher.example.com", null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://publisher.example.com");

        assertThatCode(() -> siteService.validateOrigin(site, request, null)).doesNotThrowAnyException();
    }

    @Test
    void allowsMatchingOriginRegardlessOfPath() {
        Site site = site("https://publisher.example.com", null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://publisher.example.com:443");

        // Scheme+host is what's compared, not an exact string match, so a
        // trailing port or path on either side shouldn't matter.
        assertThatCode(() -> siteService.validateOrigin(site, request, null)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMismatchedOrigin() {
        Site site = site("https://publisher.example.com", null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://evil.example.com");

        assertThatThrownBy(() -> siteService.validateOrigin(site, request, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void fallsBackToRefererWhenOriginHeaderMissing() {
        Site site = site("https://publisher.example.com", null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Referer", "https://publisher.example.com/some/page");

        assertThatCode(() -> siteService.validateOrigin(site, request, null)).doesNotThrowAnyException();
    }

    @Test
    void rejectsWhenNeitherOriginNorRefererPresent() {
        Site site = site("https://publisher.example.com", null);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> siteService.validateOrigin(site, request, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void skipsOriginCheckWhenNoneRegistered() {
        Site site = site(null, null);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatCode(() -> siteService.validateOrigin(site, request, null)).doesNotThrowAnyException();
    }

    @Test
    void allowsMatchingBundleId() {
        Site site = site(null, "com.publisher.app");

        assertThatCode(() -> siteService.validateOrigin(site, new MockHttpServletRequest(), "com.publisher.app"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMismatchedBundleId() {
        Site site = site(null, "com.publisher.app");

        assertThatThrownBy(() -> siteService.validateOrigin(site, new MockHttpServletRequest(), "com.other.app"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rejectsMissingBundleIdClaimWhenOneIsRegistered() {
        Site site = site(null, "com.publisher.app");

        assertThatThrownBy(() -> siteService.validateOrigin(site, new MockHttpServletRequest(), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void generatesUniqueNonSecretSiteKeyOnRegister() {
        SiteRepository repository = mock(SiteRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SiteService service = new SiteService(repository);

        Site a = service.register(1L, "My Site", "https://a.example.com", null);
        Site b = service.register(1L, "My Site", "https://a.example.com", null);

        assertThat(a.getSiteKey()).isNotEqualTo(b.getSiteKey());
        assertThat(a.getSiteKey()).startsWith("site_");
    }
}

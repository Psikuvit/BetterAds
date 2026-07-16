package me.psikuvit.betterads.security;

import me.psikuvit.betterads.config.CorsProperties;
import me.psikuvit.betterads.storage.entities.AdSession;
import me.psikuvit.betterads.storage.entities.Site;
import me.psikuvit.betterads.storage.repositories.AdSessionRepository;
import me.psikuvit.betterads.storage.repositories.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DynamicCorsConfigurationSourceTest {

    private SiteRepository siteRepository;
    private AdSessionRepository adSessionRepository;
    private DynamicCorsConfigurationSource source;

    @BeforeEach
    void setUp() {
        CorsProperties corsProperties = new CorsProperties();
        corsProperties.setAllowedOrigins(List.of("http://localhost:3000"));
        siteRepository = mock(SiteRepository.class);
        adSessionRepository = mock(AdSessionRepository.class);
        source = new DynamicCorsConfigurationSource(corsProperties, siteRepository, adSessionRepository);
    }

    private Site site(String allowedOrigin) {
        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("site_abc");
        site.setAllowedOrigin(allowedOrigin);
        return site;
    }

    @Test
    void resolvesRegisteredSiteOriginForSessionPath() {
        when(siteRepository.findBySiteKey("site_abc")).thenReturn(Optional.of(site("https://publisher.example.com")));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/placements/site_abc/session");

        CorsConfiguration config = source.getCorsConfiguration(request);

        assertThat(config.getAllowedOrigins()).contains("http://localhost:3000", "https://publisher.example.com");
    }

    @Test
    void resolvesRegisteredSiteOriginForSelectPath() {
        when(siteRepository.findBySiteKey("site_abc")).thenReturn(Optional.of(site("https://publisher.example.com")));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/placements/site_abc/select");

        CorsConfiguration config = source.getCorsConfiguration(request);

        assertThat(config.getAllowedOrigins()).contains("https://publisher.example.com");
    }

    @Test
    void resolvesSiteOriginForEventsPathViaSession() {
        AdSession session = new AdSession();
        session.setSiteId(1L);
        when(adSessionRepository.findBySessionToken("tok_xyz")).thenReturn(Optional.of(session));
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site("https://publisher.example.com")));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/placements/session/tok_xyz/events");

        CorsConfiguration config = source.getCorsConfiguration(request);

        assertThat(config.getAllowedOrigins()).contains("https://publisher.example.com");
    }

    @Test
    void fallsBackToStaticOriginsForUnrelatedPathWithoutHittingTheDatabase() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/campaigns");

        CorsConfiguration config = source.getCorsConfiguration(request);

        assertThat(config.getAllowedOrigins()).containsExactly("http://localhost:3000");
        verifyNoInteractions(siteRepository, adSessionRepository);
    }

    @Test
    void fallsBackToStaticOriginsWhenSiteNotFound() {
        when(siteRepository.findBySiteKey("site_missing")).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/placements/site_missing/session");

        CorsConfiguration config = source.getCorsConfiguration(request);

        assertThat(config.getAllowedOrigins()).containsExactly("http://localhost:3000");
    }

    @Test
    void fallsBackToStaticOriginsWhenSiteHasNoAllowedOriginRegistered() {
        when(siteRepository.findBySiteKey("site_abc")).thenReturn(Optional.of(site(null)));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/placements/site_abc/session");

        CorsConfiguration config = source.getCorsConfiguration(request);

        assertThat(config.getAllowedOrigins()).containsExactly("http://localhost:3000");
    }
}

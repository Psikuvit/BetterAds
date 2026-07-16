package me.psikuvit.betterads.security;

import jakarta.servlet.http.HttpServletRequest;
import me.psikuvit.betterads.config.CorsProperties;
import me.psikuvit.betterads.storage.entities.Site;
import me.psikuvit.betterads.storage.repositories.AdSessionRepository;
import me.psikuvit.betterads.storage.repositories.SiteRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CORS is static (CorsProperties' allow-list) for every path except the
 * placements API, which by design must accept calls from arbitrary
 * publisher domains registered via POST /api/sites. For those two paths
 * only, this resolves the calling Site's registered allowedOrigin and adds
 * it to the allowed-origin list for that request -- every other path keeps
 * exactly the static behavior SecurityConfig had before.
 *
 * Known limitation: Site.allowedOrigin is a single string (Phase 1's
 * schema) -- a site with multiple environments/domains isn't supported yet;
 * that would need a schema change, not done here.
 */
@Component
public class DynamicCorsConfigurationSource implements CorsConfigurationSource {

    private static final Pattern SITE_KEY_PATH = Pattern.compile("^/api/v1/placements/([^/]+)/(?:session|select)$");
    private static final Pattern EVENTS_PATH = Pattern.compile("^/api/v1/placements/session/([^/]+)/events$");

    private final CorsProperties corsProperties;
    private final SiteRepository siteRepository;
    private final AdSessionRepository adSessionRepository;

    public DynamicCorsConfigurationSource(CorsProperties corsProperties, SiteRepository siteRepository,
                                          AdSessionRepository adSessionRepository) {
        this.corsProperties = corsProperties;
        this.siteRepository = siteRepository;
        this.adSessionRepository = adSessionRepository;
    }

    @Override
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        CorsConfiguration config = staticConfig();
        String dynamicOrigin = resolveDynamicOrigin(request.getRequestURI());
        if (dynamicOrigin != null) {
            config.addAllowedOrigin(dynamicOrigin);
        }
        return config;
    }

    private CorsConfiguration staticConfig() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(new ArrayList<>(corsProperties.getAllowedOrigins()));
        config.setAllowedMethods(corsProperties.getAllowedMethods());
        config.setAllowedHeaders(corsProperties.getAllowedHeaders());
        config.setExposedHeaders(corsProperties.getExposedHeaders());
        config.setAllowCredentials(corsProperties.isAllowCredentials());
        config.setMaxAge(corsProperties.getMaxAge());
        return config;
    }

    private String resolveDynamicOrigin(String uri) {
        Matcher siteKeyMatcher = SITE_KEY_PATH.matcher(uri);
        if (siteKeyMatcher.matches()) {
            String siteKey = siteKeyMatcher.group(1);
            return siteRepository.findBySiteKey(siteKey)
                    .map(Site::getAllowedOrigin)
                    .filter(origin -> origin != null && !origin.isBlank())
                    .orElse(null);
        }

        Matcher eventsMatcher = EVENTS_PATH.matcher(uri);
        if (eventsMatcher.matches()) {
            String sessionToken = eventsMatcher.group(1);
            return adSessionRepository.findBySessionToken(sessionToken)
                    .flatMap(session -> siteRepository.findById(session.getSiteId()))
                    .map(Site::getAllowedOrigin)
                    .filter(origin -> origin != null && !origin.isBlank())
                    .orElse(null);
        }

        return null;
    }
}

package me.psikuvit.betterads.storage;

import me.psikuvit.betterads.storage.entities.AdVersion;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves which AdVersion(s) to serve for a requested locale:
 * 1. Exact locale match, if the requester specified one and it exists.
 * 2. The platform default locale, if present — a stable, predictable
 *    fallback rather than whatever order the DB happens to return.
 * 3. Whatever exists at all, as a last resort, so a viewer always sees something.
 */
@Component
public class AdVariantResolver {

    private static final String DEFAULT_LOCALE = "en";

    public List<AdVersion> resolveVariants(List<AdVersion> all, String requestedLocale) {
        if (requestedLocale != null && !requestedLocale.isBlank()) {
            List<AdVersion> exact = all.stream()
                    .filter(v -> requestedLocale.equalsIgnoreCase(v.getLocale()))
                    .toList();
            if (!exact.isEmpty()) {
                return exact;
            }
        }

        List<AdVersion> defaultLocale = all.stream()
                .filter(v -> DEFAULT_LOCALE.equalsIgnoreCase(v.getLocale()))
                .toList();
        if (!defaultLocale.isEmpty()) {
            return defaultLocale;
        }

        return all;
    }
}

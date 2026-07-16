package me.psikuvit.betterads.storage;

import me.psikuvit.betterads.storage.entities.AdVersion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdVariantResolverTest {

    private final AdVariantResolver resolver = new AdVariantResolver();

    private AdVersion version(String locale) {
        AdVersion v = new AdVersion();
        v.setLocale(locale);
        return v;
    }

    @Test
    void returnsExactLocaleMatchCaseInsensitively() {
        List<AdVersion> all = List.of(version("en"), version("FR"), version("de"));

        List<AdVersion> result = resolver.resolveVariants(all, "fr");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getLocale()).isEqualTo("FR");
    }

    @Test
    void fallsBackToDefaultLocaleWhenRequestedLocaleAbsent() {
        List<AdVersion> all = List.of(version("en"), version("de"));

        List<AdVersion> result = resolver.resolveVariants(all, "fr");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getLocale()).isEqualTo("en");
    }

    @Test
    void fallsBackToWhateverExistsWhenNoDefaultLocaleEither() {
        List<AdVersion> all = List.of(version("de"), version("es"));

        List<AdVersion> result = resolver.resolveVariants(all, "fr");

        assertThat(result).containsExactlyElementsOf(all);
    }

    @Test
    void blankRequestedLocaleSkipsExactMatchAndUsesDefault() {
        List<AdVersion> all = List.of(version("en"), version("de"));

        List<AdVersion> result = resolver.resolveVariants(all, "  ");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getLocale()).isEqualTo("en");
    }

    @Test
    void nullRequestedLocaleUsesDefault() {
        List<AdVersion> all = List.of(version("de"), version("en"));

        List<AdVersion> result = resolver.resolveVariants(all, null);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getLocale()).isEqualTo("en");
    }
}

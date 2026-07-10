package me.psikuvit.betterads.ai;

public interface TranslationService {
    /**
     * Translate or produce a localized variant for the given storageKey and target locale.
     * Returns the storage key for the generated variant (may be same as input for mock).
     */
    String translate(String storageKey, String targetLocale);

    /**
     * Name of the premium feature this provider bills as, or null if it's a
     * free/dev provider (mock, local). Used by BillingService to surcharge
     * views of AdVersions produced by real, cost-incurring AI providers.
     */
    default String featureName() {
        return null;
    }
}

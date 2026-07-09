package me.psikuvit.betterads.ai;

public interface TranslationService {
    /**
     * Translate or produce a localized variant for the given storageKey and target locale.
     * Returns the storage key for the generated variant (may be same as input for mock).
     */
    String translate(String storageKey, String targetLocale);
}

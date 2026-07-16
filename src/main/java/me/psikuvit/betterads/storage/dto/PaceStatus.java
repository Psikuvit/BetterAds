package me.psikuvit.betterads.storage.dto;

/**
 * Informational pacing signal for a campaign (AdSelectionService) — whether
 * spend is ahead of, on, or behind the schedule implied by its
 * starts_at/ends_at flight window. UNPACED if no window is configured.
 * This is exposed, not enforced: it does not affect which ad is selected or
 * whether one is served.
 */
public enum PaceStatus {
    AHEAD,
    ON_PACE,
    BEHIND,
    UNPACED
}
